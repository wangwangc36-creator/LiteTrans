package com.litetrans.module.ui;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.litetrans.module.ipc.VisibilityBridge;
import com.litetrans.module.service.TranslationService;
import com.litetrans.module.util.Protocol;

import java.util.ArrayList;
import java.util.Arrays;

public final class MainActivity extends Activity {
    private TextView status;
    private TextView diagnostics;
    private TextView selfTestResult;
    private Button prepareButton;
    private Button reconnectButton;
    private Button selfTestButton;
    private Messenger service;
    private boolean bound;
    private boolean binding;
    private boolean selfTestPending;
    private int visibilityGrantCount;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Messenger replyMessenger = new Messenger(
            new Handler(Looper.getMainLooper(), this::handleReply));

    private final Runnable statsPoll = new Runnable() {
        @Override public void run() {
            requestStats();
            uiHandler.postDelayed(this, 2000L);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        visibilityGrantCount = VisibilityBridge.grantToInstalledApps(this);
        setTitle("LiteTrans");
        setContentView(buildUi());
        bindTranslator();
        uiHandler.post(statsPoll);
    }

    private ScrollView buildUi() {
        int p = dp(20);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, p, p, p);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(text("LiteTrans", 30, true));
        TextView subtitle = text("英语 / 西班牙语 → 简体中文", 18, true);
        subtitle.setPadding(0, dp(8), 0, dp(4));
        root.addView(subtitle);

        TextView engine = text(
                "翻译引擎：Google ML Kit 本地翻译\n" +
                "运行方式：LSPosed Hook + 独立翻译进程\n" +
                "IPC 包可见性授权：" + visibilityGrantCount + " 个已安装应用",
                15, false);
        engine.setPadding(0, 0, 0, dp(18));
        root.addView(engine);

        status = text("正在连接翻译服务…", 16, true);
        status.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(status, full());

        prepareButton = button("准备英 / 西 / 中文翻译模型", v -> warmup());
        prepareButton.setEnabled(false);
        root.addView(prepareButton, top(12));

        reconnectButton = button("重新连接翻译服务", v -> bindTranslator());
        root.addView(reconnectButton, top(8));

        selfTestButton = button("运行翻译引擎自检", v -> runSelfTest());
        selfTestButton.setEnabled(false);
        root.addView(selfTestButton, top(8));

        selfTestResult = text("翻译引擎自检：尚未运行", 14, false);
        selfTestResult.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(selfTestResult, full());

        diagnostics = text(
                "Hook 运行诊断\n\n" +
                "LSPosed 已注入 App：0\n" +
                "最后注入 App：—\n" +
                "目标 App 文本请求：0\n" +
                "真正翻译改变：0\n\n" +
                "Alpha4 会在 Application.attach 时发送独立心跳；它不依赖 TextView。",
                14, false);
        diagnostics.setPadding(dp(12), dp(16), dp(12), dp(16));
        root.addView(diagnostics, full());

        Button refreshVisibility = button("重新授予 IPC 可见性", v -> {
            visibilityGrantCount = VisibilityBridge.grantToInstalledApps(this);
            status.setText("✓ 已重新授予 " + visibilityGrantCount + " 个应用 LiteTrans 包可见性\n请强制停止并重新打开目标 App");
        });
        root.addView(refreshVisibility, top(8));

        Button clear = button("清空 LiteTrans 内存缓存", v -> clearCache());
        root.addView(clear, top(8));

        TextView steps = text(
                "Alpha4 诊断方法\n\n" +
                "1. 顶部应显示 IPC 包可见性授权数量大于 0。\n" +
                "2. 在 LSPosed 勾选目标 App 后，强制停止并重新打开目标 App。\n" +
                "3. 回到这里看“LSPosed 已注入 App”。只要这个数字增加，就证明模块已经进入目标 App，和 TextView/Compose 无关。\n" +
                "4. 若已注入 App > 0，但文本请求仍为 0，才说明目标页面主要是 Compose/Canvas/WebView。\n\n" +
                "Alpha4 修复 Android 11+ 包可见性导致目标 App 无法 bindService 到 LiteTrans 的问题。",
                15, false);
        steps.setPadding(0, dp(22), 0, dp(18));
        root.addView(steps);

        TextView attribution = text("Translation powered by Google ML Kit", 12, false);
        attribution.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(attribution, full());
        return scroll;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        v.setLineSpacing(0, 1.12f);
        return v;
    }

    private Button button(String label, android.view.View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(listener);
        return b;
    }

    private LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams top(int dp) {
        LinearLayout.LayoutParams lp = full();
        lp.topMargin = dp(dp);
        return lp;
    }

    private void bindTranslator() {
        if (bound || binding) return;
        status.setText("正在连接翻译服务…");
        prepareButton.setEnabled(false);
        selfTestButton.setEnabled(false);
        reconnectButton.setEnabled(false);
        Intent intent = new Intent(this, TranslationService.class);
        try {
            boolean accepted = bindService(intent, connection, Context.BIND_AUTO_CREATE);
            binding = accepted;
            if (!accepted) {
                reconnectButton.setEnabled(true);
                status.setText("翻译服务连接失败：bindService() 返回 false");
            }
        } catch (Throwable t) {
            binding = false;
            reconnectButton.setEnabled(true);
            status.setText("翻译服务连接失败：" + t.getClass().getSimpleName() +
                    (t.getMessage() == null ? "" : "\n" + t.getMessage()));
        }
    }

    private void markDisconnected(String message) {
        service = null;
        binding = false;
        bound = false;
        prepareButton.setEnabled(false);
        selfTestButton.setEnabled(false);
        reconnectButton.setEnabled(true);
        status.setText(message);
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            binding = false;
            if (binder == null) {
                markDisconnected("翻译服务连接失败：Binder 为空");
                return;
            }
            service = new Messenger(binder);
            bound = true;
            reconnectButton.setEnabled(false);
            prepareButton.setEnabled(true);
            selfTestButton.setEnabled(true);
            status.setText("✓ 翻译服务已连接，正在准备模型…");
            warmup();
            requestStats();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            markDisconnected("翻译服务已断开，请点“重新连接翻译服务”");
        }
        @Override public void onBindingDied(ComponentName name) {
            markDisconnected("翻译服务进程异常退出，请点“重新连接翻译服务”");
        }
        @Override public void onNullBinding(ComponentName name) {
            markDisconnected("翻译服务返回空 Binder，请重新安装或重启 LiteTrans");
        }
    };

    private void warmup() {
        Messenger target = service;
        if (target == null) return;
        status.setText("正在准备 Google 英语 / 西班牙语 / 中文模型…\n首次下载需要联网，之后可离线使用。");
        prepareButton.setEnabled(false);
        Message msg = Message.obtain(null, Protocol.MSG_WARMUP);
        msg.replyTo = replyMessenger;
        try { target.send(msg); }
        catch (RemoteException e) { markDisconnected("模型准备请求发送失败：翻译服务已断开"); }
    }

    private void runSelfTest() {
        Messenger target = service;
        if (target == null || selfTestPending) return;
        selfTestPending = true;
        selfTestButton.setEnabled(false);
        selfTestResult.setText("翻译引擎自检：正在翻译…");
        Message msg = Message.obtain(null, Protocol.MSG_TRANSLATE_BATCH);
        Bundle data = new Bundle();
        data.putStringArrayList(Protocol.KEY_TEXTS, new ArrayList<>(Arrays.asList(
                "Hello, how are you today?",
                "Hola, ¿cómo estás hoy?")));
        data.putString(Protocol.KEY_HOST_PACKAGE, Protocol.SELF_TEST_HOST);
        msg.setData(data);
        msg.replyTo = replyMessenger;
        try { target.send(msg); }
        catch (RemoteException e) {
            selfTestPending = false;
            selfTestButton.setEnabled(true);
            markDisconnected("自检发送失败：翻译服务已断开");
        }
    }

    private void requestStats() {
        Messenger target = service;
        if (target == null) return;
        Message msg = Message.obtain(null, Protocol.MSG_STATS);
        msg.replyTo = replyMessenger;
        try { target.send(msg); } catch (RemoteException ignored) {}
    }

    private void clearCache() {
        Messenger target = service;
        if (target == null) return;
        Message msg = Message.obtain(null, Protocol.MSG_CLEAR_CACHE);
        msg.replyTo = replyMessenger;
        try { target.send(msg); } catch (RemoteException ignored) {}
    }

    private boolean handleReply(Message msg) {
        if (msg.what == Protocol.MSG_WARMUP_RESULT) {
            boolean ok = msg.getData().getBoolean(Protocol.KEY_OK, false);
            if (ok) {
                status.setText("✓ 翻译模型已就绪\n现在可离线执行英语 / 西班牙语 → 简体中文翻译");
                selfTestButton.setEnabled(true);
            } else {
                String error = msg.getData().getString(Protocol.KEY_ERROR, "模型准备失败");
                status.setText("⚠ " + error);
            }
            prepareButton.setEnabled(true);
            return true;
        }
        if (msg.what == Protocol.MSG_TRANSLATE_RESULT && selfTestPending) {
            selfTestPending = false;
            selfTestButton.setEnabled(true);
            ArrayList<String> src = msg.getData().getStringArrayList(Protocol.KEY_TEXTS);
            ArrayList<String> dst = msg.getData().getStringArrayList(Protocol.KEY_TRANSLATIONS);
            if (src != null && dst != null && src.size() >= 2 && dst.size() >= 2) {
                boolean changed = !src.get(0).equals(dst.get(0)) || !src.get(1).equals(dst.get(1));
                selfTestResult.setText((changed ? "✓" : "⚠") + " 翻译引擎自检\n\n" +
                        src.get(0) + "\n→ " + dst.get(0) + "\n\n" +
                        src.get(1) + "\n→ " + dst.get(1));
            } else {
                selfTestResult.setText("⚠ 翻译引擎自检没有收到有效结果");
            }
            return true;
        }
        if (msg.what == Protocol.MSG_STATS_RESULT) {
            Bundle d = msg.getData();
            long hooks = d.getLong(Protocol.KEY_HOOK_COUNT, 0);
            String lastHook = d.getString(Protocol.KEY_LAST_HOOK_HOST, "—");
            long batches = d.getLong(Protocol.KEY_BATCH_COUNT, 0);
            long texts = d.getLong(Protocol.KEY_TEXT_COUNT, 0);
            long changed = d.getLong(Protocol.KEY_CHANGED_COUNT, 0);
            String host = d.getString(Protocol.KEY_LAST_HOST, "—");
            String source = d.getString(Protocol.KEY_LAST_SOURCE, "—");
            String translated = d.getString(Protocol.KEY_LAST_TRANSLATION, "—");
            diagnostics.setText(
                    "Hook 运行诊断\n\n" +
                    "LSPosed 已注入 App：" + hooks + "\n" +
                    "最后注入 App：" + lastHook + "\n" +
                    "目标 App 批次：" + batches + "\n" +
                    "目标 App 文本请求：" + texts + "\n" +
                    "真正翻译改变：" + changed + "\n" +
                    "最后请求 App：" + host + "\n" +
                    "最后原文：" + source + "\n" +
                    "最后译文：" + translated + "\n\n" +
                    (hooks == 0
                            ? "尚未收到 LSPosed 注入心跳。请确认目标 App 已勾选并强制停止后重开。"
                            : texts == 0
                                ? "✓ LSPosed 注入已确认，但当前页面还没有标准 TextView 文本请求。"
                                : "✓ LSPosed 注入与翻译 IPC 均已工作。"));
            return true;
        }
        if (msg.what == Protocol.MSG_CLEAR_CACHE_RESULT) {
            status.setText("✓ 翻译服务内存缓存已清空");
            return true;
        }
        return false;
    }

    @Override protected void onDestroy() {
        uiHandler.removeCallbacks(statsPoll);
        if (bound || binding) {
            try { unbindService(connection); } catch (Throwable ignored) {}
        }
        bound = false;
        binding = false;
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
