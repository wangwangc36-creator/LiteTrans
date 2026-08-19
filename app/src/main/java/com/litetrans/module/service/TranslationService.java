package com.litetrans.module.service;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;

import com.litetrans.module.util.Protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Exported only because hooked apps run under their own UIDs and need one shared translator.
 * It exposes no files/accounts/network API; requests are bounded and translated on-device.
 */
public final class TranslationService extends Service {
    private static final int MAX_BATCH = 48;
    private static final int MAX_TEXT_CHARS = 4000;

    private final ExecutorService workers = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "LiteTrans-Worker");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    private TranslationEngine engine;
    private Messenger messenger;

    @Override
    public void onCreate() {
        super.onCreate();
        engine = new TranslationEngine();
        messenger = new Messenger(new Handler(Looper.getMainLooper(), this::handleMessage));
        workers.execute(engine::ensureModelsReady);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return messenger.getBinder();
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
            ArrayList<String> translations = engine.translateBatch(texts);
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
            boolean ok = engine.ensureModelsReady();
            Message response = Message.obtain(null, Protocol.MSG_WARMUP_RESULT);
            Bundle data = new Bundle();
            data.putBoolean(Protocol.KEY_OK, ok);
            if (!ok) data.putString(Protocol.KEY_ERROR, "模型下载失败，请检查网络后重试");
            response.setData(data);
            safeSend(replyTo, response);
        });
    }

    private void handleClearCache(Message msg) {
        final Messenger replyTo = msg.replyTo;
        workers.execute(() -> {
            engine.clearCache();
            if (replyTo != null) {
                Message response = Message.obtain(null, Protocol.MSG_CLEAR_CACHE_RESULT);
                Bundle data = new Bundle();
                data.putBoolean(Protocol.KEY_OK, true);
                response.setData(data);
                safeSend(replyTo, response);
            }
        });
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
        if (engine != null) engine.close();
        super.onDestroy();
    }
}
