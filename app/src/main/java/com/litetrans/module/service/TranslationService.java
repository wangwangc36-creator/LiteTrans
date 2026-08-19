package com.litetrans.module.service;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;

import com.google.mlkit.common.MlKit;
import com.litetrans.module.util.Protocol;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shared translator + lightweight runtime diagnostics. */
public final class TranslationService extends Service {
    private static final int MAX_BATCH = 48;
    private static final int MAX_TEXT_CHARS = 4000;

    private final ExecutorService workers = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "LiteTrans-Worker");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    private final Object statsLock = new Object();
    private final Set<String> hookedPackages = new HashSet<>();
    private long batchCount;
    private long textCount;
    private long changedCount;
    private String lastHookHost = "—";
    private String lastHost = "—";
    private String lastSource = "—";
    private String lastTranslation = "—";

    private volatile TranslationEngine engine;
    private volatile String initializationError;
    private Messenger messenger;

    @Override public void onCreate() {
        super.onCreate();
        messenger = new Messenger(new Handler(Looper.getMainLooper(), this::handleMessage));
        try {
            MlKit.initialize(getApplicationContext());
            engine = new TranslationEngine();
            workers.execute(() -> {
                TranslationEngine local = engine;
                if (local != null) local.ensureModelsReady();
            });
        } catch (Throwable t) {
            initializationError = describe(t);
            engine = null;
        }
    }

    @Override public IBinder onBind(Intent intent) {
        return messenger == null ? null : messenger.getBinder();
    }

    private boolean handleMessage(Message msg) {
        if (msg == null) return true;
        switch (msg.what) {
            case Protocol.MSG_TRANSLATE_BATCH: handleTranslate(msg); return true;
            case Protocol.MSG_WARMUP: handleWarmup(msg); return true;
            case Protocol.MSG_CLEAR_CACHE: handleClearCache(msg); return true;
            case Protocol.MSG_STATS: handleStats(msg); return true;
            case Protocol.MSG_HOOK_HELLO: handleHookHello(msg); return true;
            default: return false;
        }
    }

    private void handleHookHello(Message msg) {
        Bundle data = msg.getData();
        String host = data == null ? null : data.getString(Protocol.KEY_HOST_PACKAGE);
        if (host == null || host.trim().isEmpty()) return;
        synchronized (statsLock) {
            hookedPackages.add(host);
            lastHookHost = host;
        }
    }

    private void handleTranslate(Message msg) {
        final Messenger replyTo = msg.replyTo;
        if (replyTo == null) return;
        Bundle data = msg.getData();
        ArrayList<String> raw = data == null ? null : data.getStringArrayList(Protocol.KEY_TEXTS);
        if (raw == null || raw.isEmpty()) return;
        String host = data == null ? null : data.getString(Protocol.KEY_HOST_PACKAGE);
        if (host == null || host.isEmpty()) host = "unknown";
        final boolean selfTest = Protocol.SELF_TEST_HOST.equals(host);
        final String finalHost = host;

        final ArrayList<String> texts = new ArrayList<>(Math.min(MAX_BATCH, raw.size()));
        for (int i = 0; i < raw.size() && texts.size() < MAX_BATCH; i++) {
            String text = raw.get(i);
            if (text == null) text = "";
            if (text.length() > MAX_TEXT_CHARS) text = text.substring(0, MAX_TEXT_CHARS);
            texts.add(text);
        }

        if (!selfTest) {
            synchronized (statsLock) {
                hookedPackages.add(finalHost);
                lastHookHost = finalHost;
                batchCount++;
                textCount += texts.size();
                lastHost = finalHost;
                if (!texts.isEmpty()) lastSource = compact(texts.get(texts.size() - 1));
            }
        }

        workers.execute(() -> {
            TranslationEngine local = engine;
            ArrayList<String> translations = local == null
                    ? new ArrayList<>(texts)
                    : local.translateBatch(texts);

            if (!selfTest) {
                synchronized (statsLock) {
                    int count = Math.min(texts.size(), translations.size());
                    for (int i = 0; i < count; i++) {
                        String src = texts.get(i);
                        String dst = translations.get(i);
                        if (dst != null && !dst.equals(src)) changedCount++;
                        if (i == count - 1) {
                            lastSource = compact(src);
                            lastTranslation = compact(dst);
                        }
                    }
                }
            }

            Message response = Message.obtain(null, Protocol.MSG_TRANSLATE_RESULT);
            Bundle result = new Bundle();
            result.putStringArrayList(Protocol.KEY_TEXTS, texts);
            result.putStringArrayList(Protocol.KEY_TRANSLATIONS, translations);
            response.setData(result);
            safeSend(replyTo, response);
        });
    }

    private void handleWarmup(Message msg) {
        final Messenger replyTo = msg.replyTo;
        if (replyTo == null) return;
        workers.execute(() -> {
            TranslationEngine local = engine;
            boolean ok = local != null && local.ensureModelsReady();
            Message response = Message.obtain(null, Protocol.MSG_WARMUP_RESULT);
            Bundle data = new Bundle();
            data.putBoolean(Protocol.KEY_OK, ok);
            if (!ok) {
                String error = initializationError;
                if (error == null || error.isEmpty()) error = "模型下载失败，请检查网络后重试";
                else error = "翻译服务初始化失败：" + error;
                data.putString(Protocol.KEY_ERROR, error);
            }
            response.setData(data);
            safeSend(replyTo, response);
        });
    }

    private void handleStats(Message msg) {
        Messenger replyTo = msg.replyTo;
        if (replyTo == null) return;
        Message response = Message.obtain(null, Protocol.MSG_STATS_RESULT);
        Bundle data = new Bundle();
        synchronized (statsLock) {
            data.putLong(Protocol.KEY_HOOK_COUNT, hookedPackages.size());
            data.putString(Protocol.KEY_LAST_HOOK_HOST, lastHookHost);
            data.putLong(Protocol.KEY_BATCH_COUNT, batchCount);
            data.putLong(Protocol.KEY_TEXT_COUNT, textCount);
            data.putLong(Protocol.KEY_CHANGED_COUNT, changedCount);
            data.putString(Protocol.KEY_LAST_HOST, lastHost);
            data.putString(Protocol.KEY_LAST_SOURCE, lastSource);
            data.putString(Protocol.KEY_LAST_TRANSLATION, lastTranslation);
        }
        response.setData(data);
        safeSend(replyTo, response);
    }

    private void handleClearCache(Message msg) {
        final Messenger replyTo = msg.replyTo;
        workers.execute(() -> {
            TranslationEngine local = engine;
            if (local != null) local.clearCache();
            if (replyTo != null) {
                Message response = Message.obtain(null, Protocol.MSG_CLEAR_CACHE_RESULT);
                Bundle data = new Bundle();
                data.putBoolean(Protocol.KEY_OK, true);
                response.setData(data);
                safeSend(replyTo, response);
            }
        });
    }

    private static String compact(String value) {
        if (value == null) return "—";
        value = value.replace('\n', ' ').trim();
        return value.length() <= 90 ? value : value.substring(0, 90) + "…";
    }

    private static String describe(Throwable t) {
        if (t == null) return "Unknown error";
        String name = t.getClass().getSimpleName();
        String message = t.getMessage();
        return message == null || message.trim().isEmpty() ? name : name + ": " + message;
    }

    private static void safeSend(Messenger target, Message message) {
        try { target.send(message); } catch (Throwable ignored) {}
    }

    @Override public void onDestroy() {
        workers.shutdownNow();
        TranslationEngine local = engine;
        if (local != null) local.close();
        engine = null;
        super.onDestroy();
    }
}
