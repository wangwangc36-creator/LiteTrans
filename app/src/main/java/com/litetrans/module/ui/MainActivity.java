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

        TextView engine = text("翻译引擎：Google ML Kit 本地翻译\n运行方式：LSPosed 超轻 Hook + 独立翻译进程", 15, false);
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
                "目标 App 文本请求：0\n" +
                "真正翻译改变：0\n" +
                "最后 App：—\n" +
                "最后原文：—\n" +
                "最后译文：—\n\n" +
                "如果你打开已勾选 App 后这里仍然一直是 0，说明该 App 没走标准 TextView Hook，或 LSPosed 没把 Hook 注入该 App。",
                14, false);
        diagnostics.setPadding(dp(12), dp(16), dp(12), dp(16));
        root.addView(diagnostics, full());

        Button clear = button("清空 LiteTrans 内存缓存", v -> clearCache());
        root.addView(clear, top(8));

        TextView steps = text(
                "实机诊断方法\n\n" +
                "1. 等顶部显示“翻译模型已就绪”。\n" +
                "2. 点“运行翻译引擎自检”，应看到英文和西班牙语被翻成中文。\n" +
                "3. 在 LSPosed 中勾选一个目标 App，强制停止后重新打开。\n" +
                "4. 回到 LiteTrans 看“目标 App 文本请求”是否增加。\n\n" +
                "Alpha3 改为同时 Hook TextView.setText 的 1 / 2 / 4 参数入口，并用递归保护避免重复翻译；仍然不扫描 Dex、不阻塞 UI 线程。\n\n" +
                "注意：Jetpack Compose、Canvas 自绘、部分 Telegram/Instagram/X 页面和 WebView 可能完全不使用 Android TextView。这种情况下诊断请求会保持 0，下一步需要单独做 Compose/Canvas/WebView 适配。",
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
        if (target == null) {
            status.setText("翻译服务尚未连接");
            reconnectButton.setEnabled(true);
            return;
        }
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
            long batches = d.getLong(Protocol.KEY_BATCH_COUNT, 0);
            long texts = d.getLong(Protocol.KEY_TEXT_COUNT, 0);
            long changed = d.getLong(Protocol.KEY_CHANGED_COUNT, 0);
            String host = d.getString(Protocol.KEY_LAST_HOST, "—");
            String source = d.getString(Protocol.KEY_LAST_SOURCE, "—");
            String translated = d.getString(Protocol.KEY_LAST_TRANSLATION, "—");
            diagnostics.setText(
                    "Hook 运行诊断\n\n" +
                    "目标 App 批次：" + batches + "\n" +
                    "目标 App 文本请求：" + texts + "\n" +
                    "真正翻译改变：" + changed + "\n" +
                    "最后 App：" + host + "\n" +
                    "最后原文：" + source + "\n" +
                    "最后译文：" + translated + "\n\n" +
                    (texts == 0
                            ? "目前没有任何目标 App 文本到达翻译服务。请打开 LSPosed 已勾选的 App 再回来查看。"
                            : "✓ 至少有一个目标 App 已经通过 Hook 把文本送到了 LiteTrans。"));
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
