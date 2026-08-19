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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shared translator service. Hooked apps only perform lightweight Binder IPC; all ML work
 * remains in this module-owned process and on worker threads.
 */
public final class TranslationService extends Service {
    private static final int MAX_BATCH = 48;
    private static final int MAX_TEXT_CHARS = 4000;

    private final ExecutorService workers = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "LiteTrans-Worker");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    private volatile TranslationEngine engine;
    private volatile String initializationError;
    private Messenger messenger;

    @Override
    public void onCreate() {
        super.onCreate();

        // Publish a Binder even when ML Kit initialization fails so MainActivity can report
        // a useful error instead of remaining forever at "connecting".
        messenger = new Messenger(new Handler(Looper.getMainLooper(), this::handleMessage));

        try {
            // TranslationService runs in :translator. MlKitInitProvider normally initializes
            // only the app's default process, so explicitly initialize ML Kit here before any
            // LanguageIdentification/Translation client is constructed.
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

    @Override
    public IBinder onBind(Intent intent) {
        return messenger == null ? null : messenger.getBinder();
    }

    private boolean handleMessage(Message msg) {
        if (msg == null) return true;
        switch (msg.what) {
            case Protocol.MSG_TRANSLATE_BATCH:
                handleTranslate(msg);
                return true;
            case Protocol.MSG_WARMUP:
                handleWarmup(msg);
                return true;
            case Protocol.MSG_CLEAR_CACHE:
                handleClearCache(msg);
                return true;
            default:
                return false;
        }
    }

    private void handleTranslate(Message msg) {
        final Messenger replyTo = msg.replyTo;
        if (replyTo == null) return;

        Bundle data = msg.getData();
        ArrayList<String> raw = data == null ? null : data.getStringArrayList(Protocol.KEY_TEXTS);
        if (raw == null || raw.isEmpty()) return;

        final ArrayList<String> texts = new ArrayList<>(Math.min(MAX_BATCH, raw.size()));
        for (int i = 0; i < raw.size() && texts.size() < MAX_BATCH; i++) {
            String text = raw.get(i);
            if (text == null) text = "";
            if (text.length() > MAX_TEXT_CHARS) text = text.substring(0, MAX_TEXT_CHARS);
            texts.add(text);
        }

        workers.execute(() -> {
            TranslationEngine local = engine;
            ArrayList<String> translations;
            if (local == null) {
                // Keep host apps stable if translator initialization failed. MainActivity will
                // expose the actual failure and restarting LiteTrans will retry initialization.
                translations = new ArrayList<>(texts);
            } else {
                translations = local.translateBatch(texts);
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
                if (error == null || error.isEmpty()) {
                    error = "模型下载失败，请检查网络后重试";
                } else {
                    error = "翻译服务初始化失败：" + error;
                }
                data.putString(Protocol.KEY_ERROR, error);
            }
            response.setData(data);
            safeSend(replyTo, response);
        });
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

    private static String describe(Throwable t) {
        if (t == null) return "Unknown error";
        String name = t.getClass().getSimpleName();
        String message = t.getMessage();
        return message == null || message.trim().isEmpty() ? name : name + ": " + message;
    }

    private static void safeSend(Messenger target, Message message) {
        try {
            target.send(message);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onDestroy() {
        workers.shutdownNow();
        TranslationEngine local = engine;
        if (local != null) local.close();
        engine = null;
        super.onDestroy();
    }
}
