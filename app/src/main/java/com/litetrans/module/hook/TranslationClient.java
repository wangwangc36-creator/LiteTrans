package com.litetrans.module.hook;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;
import android.util.LruCache;
import android.widget.TextView;

import com.litetrans.module.util.Protocol;
import com.litetrans.module.util.StyledText;
import com.litetrans.module.util.TextEnvelope;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Per-host-process client. UI hot path remains RAM lookup + enqueue only. */
public final class TranslationClient {
    private static volatile TranslationClient INSTANCE;
    private static final int HOST_CACHE_ENTRIES = 2048;
    private static final int MAX_BATCH = 48;
    private static final long BATCH_WINDOW_MS = 24L;
    private static final long REQUEST_EXPIRY_MS = 300_000L;
    private static final long REBIND_DELAY_MS = 2_000L;

    private final Context appContext;
    private final String hostPackage;
    private final Object lock = new Object();
    private final LruCache<String, String> cache = new LruCache<>(HOST_CACHE_ENTRIES);
    private final LinkedHashSet<String> queue = new LinkedHashSet<>();
    private final Map<String, List<PendingTarget>> waiters = new HashMap<>();
    private final HandlerThread ioThread;
    private final Handler ioHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Messenger replyMessenger;

    private volatile Messenger service;
    private volatile boolean binding;
    private volatile boolean bound;
    private boolean flushScheduled;
    private boolean rebindScheduled;
    private boolean helloRequested;

    private TranslationClient(Context context) {
        Context application = context.getApplicationContext();
        this.appContext = application != null ? application : context;
        this.hostPackage = this.appContext.getPackageName();
        ioThread = new HandlerThread("LiteTrans-IPC", Process.THREAD_PRIORITY_BACKGROUND);
        ioThread.start();
        ioHandler = new Handler(ioThread.getLooper());
        replyMessenger = new Messenger(new Handler(ioThread.getLooper(), this::handleReply));
    }

    public static TranslationClient get(Context context) {
        TranslationClient local = INSTANCE;
        if (local != null) return local;
        synchronized (TranslationClient.class) {
            local = INSTANCE;
            if (local == null) {
                local = new TranslationClient(context);
                INSTANCE = local;
            }
        }
        return local;
    }

    /** Called once from Application.attach so diagnostics can distinguish injection from text coverage. */
    public void reportHookLoaded() {
        synchronized (this) { helloRequested = true; }
        ensureBound();
        if (bound) ioHandler.post(this::sendHello);
    }

    public String peek(String source) {
        synchronized (lock) { return cache.get(source); }
    }

    public void request(String source, TextView view, long generation,
                        CharSequence original, TextEnvelope envelope) {
        String already;
        synchronized (lock) {
            already = cache.get(source);
            if (already == null) {
                List<PendingTarget> targets = waiters.get(source);
                if (targets == null) {
                    targets = new ArrayList<>();
                    waiters.put(source, targets);
                    queue.add(source);
                    scheduleExpiryLocked(source);
                }
                targets.add(new PendingTarget(view, generation, original, envelope));
                scheduleFlushLocked();
            }
        }
        if (already != null) {
            applyAsync(new PendingTarget(view, generation, original, envelope), source, already);
            return;
        }
        ensureBound();
    }

    private void sendHello() {
        Messenger target = service;
        if (target == null) return;
        synchronized (this) {
            if (!helloRequested) return;
        }
        Message msg = Message.obtain(null, Protocol.MSG_HOOK_HELLO);
        Bundle data = new Bundle();
        data.putString(Protocol.KEY_HOST_PACKAGE, hostPackage);
        msg.setData(data);
        try {
            target.send(msg);
            synchronized (this) { helloRequested = false; }
        } catch (RemoteException e) {
            service = null;
            bound = false;
            scheduleRebind();
        }
    }

    private void scheduleFlushLocked() {
        if (flushScheduled) return;
        flushScheduled = true;
        ioHandler.postDelayed(this::flushNow, BATCH_WINDOW_MS);
    }

    private void scheduleExpiryLocked(String source) {
        ioHandler.postDelayed(() -> {
            synchronized (lock) {
                waiters.remove(source);
                queue.remove(source);
            }
        }, REQUEST_EXPIRY_MS);
    }

    private void flushNow() {
        final ArrayList<String> batch = new ArrayList<>(MAX_BATCH);
        synchronized (lock) {
            flushScheduled = false;
            if (queue.isEmpty()) return;
            for (String text : queue) {
                batch.add(text);
                if (batch.size() >= MAX_BATCH) break;
            }
            queue.removeAll(batch);
        }

        Messenger target = service;
        if (target == null) {
            synchronized (lock) { queue.addAll(batch); }
            ensureBound();
            return;
        }

        Message msg = Message.obtain(null, Protocol.MSG_TRANSLATE_BATCH);
        Bundle data = new Bundle();
        data.putStringArrayList(Protocol.KEY_TEXTS, batch);
        data.putString(Protocol.KEY_HOST_PACKAGE, hostPackage);
        msg.setData(data);
        msg.replyTo = replyMessenger;
        try {
            target.send(msg);
        } catch (RemoteException e) {
            service = null;
            bound = false;
            synchronized (lock) { queue.addAll(batch); }
            scheduleRebind();
            return;
        }

        synchronized (lock) {
            if (!queue.isEmpty()) scheduleFlushLocked();
        }
    }

    private boolean handleReply(Message msg) {
        if (msg.what != Protocol.MSG_TRANSLATE_RESULT) return true;
        Bundle data = msg.getData();
        ArrayList<String> sources = data.getStringArrayList(Protocol.KEY_TEXTS);
        ArrayList<String> translations = data.getStringArrayList(Protocol.KEY_TRANSLATIONS);
        if (sources == null || translations == null) return true;
        int count = Math.min(sources.size(), translations.size());
        for (int i = 0; i < count; i++) {
            String source = sources.get(i);
            String translated = translations.get(i);
            if (source == null) continue;
            if (translated == null || translated.isEmpty()) translated = source;
            final List<PendingTarget> targets;
            synchronized (lock) {
                cache.put(source, translated);
                targets = waiters.remove(source);
            }
            if (targets != null) {
                for (PendingTarget pending : targets) applyAsync(pending, source, translated);
            }
        }
        return true;
    }

    private void applyAsync(PendingTarget pending, String source, String translated) {
        mainHandler.post(() -> {
            TextView view = pending.view.get();
            if (view == null) return;
            try {
                Object gen = XposedHelpers.getAdditionalInstanceField(view, LiteTransHook.GENERATION_KEY);
                Object currentSource = XposedHelpers.getAdditionalInstanceField(view, LiteTransHook.SOURCE_KEY);
                if (!(gen instanceof Long) || ((Long) gen).longValue() != pending.generation) return;
                if (!(currentSource instanceof String) || !source.equals(currentSource)) return;
                if (translated.equals(source)) return;
                String wrapped = pending.envelope.wrap(translated);
                CharSequence styled = StyledText.rebuild(pending.original, wrapped);
                XposedHelpers.setAdditionalInstanceField(view, LiteTransHook.BYPASS_KEY, Boolean.TRUE);
                view.setText(styled);
            } catch (Throwable ignored) {}
        });
    }

    private void ensureBound() {
        if (bound || binding) return;
        synchronized (this) {
            if (bound || binding) return;
            binding = true;
            try {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(Protocol.MODULE_PACKAGE, Protocol.SERVICE_CLASS));
                boolean ok = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE);
                if (!ok) {
                    binding = false;
                    XposedBridge.log("[LiteTrans] bindService=false host=" + hostPackage);
                    scheduleRebind();
                }
            } catch (Throwable t) {
                binding = false;
                XposedBridge.log("[LiteTrans] bindService failed host=" + hostPackage + " error=" + t);
                scheduleRebind();
            }
        }
    }

    private void scheduleRebind() {
        synchronized (this) {
            if (rebindScheduled) return;
            rebindScheduled = true;
        }
        ioHandler.postDelayed(() -> {
            synchronized (TranslationClient.this) { rebindScheduled = false; }
            ensureBound();
        }, REBIND_DELAY_MS);
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = new Messenger(binder);
            binding = false;
            bound = true;
            ioHandler.post(() -> {
                sendHello();
                flushNow();
            });
        }
        @Override public void onServiceDisconnected(ComponentName name) { markDead(); }
        @Override public void onBindingDied(ComponentName name) { markDead(); }
        @Override public void onNullBinding(ComponentName name) { markDead(); }
        private void markDead() {
            service = null;
            binding = false;
            bound = false;
            scheduleRebind();
        }
    };

    private static final class PendingTarget {
        final WeakReference<TextView> view;
        final long generation;
        final CharSequence original;
        final TextEnvelope envelope;
        PendingTarget(TextView view, long generation, CharSequence original, TextEnvelope envelope) {
            this.view = new WeakReference<>(view);
            this.generation = generation;
            this.original = original;
            this.envelope = envelope;
        }
    }
}
