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

public final class MainActivity extends Activity {
    private TextView status;
    private Button prepareButton;
    private Messenger service;
    private boolean bound;

    private final Messenger replyMessenger = new Messenger(
            new Handler(Looper.getMainLooper(), this::handleReply));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("LiteTrans");
        setContentView(buildUi());
        bindTranslator();
    }

    private ScrollView buildUi() {
        int p = dp(20);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, p, p, p);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("LiteTrans", 30, true);
        root.addView(title);

        TextView subtitle = text("英语 / 西班牙语 → 简体中文", 18, true);
        subtitle.setPadding(0, dp(8), 0, dp(4));
        root.addView(subtitle);

        TextView engine = text("翻译引擎：Google ML Kit 本地翻译\n运行方式：LSPosed 超轻 Hook + 独立翻译进程", 15, false);
        engine.setPadding(0, 0, 0, dp(18));
        root.addView(engine);

        status = text("正在连接翻译服务…", 16, true);
        status.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        prepareButton = new Button(this);
        prepareButton.setText("准备英 / 西 / 中文翻译模型");
        prepareButton.setEnabled(false);
        prepareButton.setOnClickListener(v -> warmup());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonParams.topMargin = dp(12);
        root.addView(prepareButton, buttonParams);

        Button clear = new Button(this);
        clear.setText("清空 LiteTrans 内存缓存");
        clear.setOnClickListener(v -> clearCache());
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clearParams.topMargin = dp(8);
        root.addView(clear, clearParams);

        TextView steps = text(
                "使用方法\n\n" +
                "1. 在 LSPosed 中启用 LiteTrans。\n" +
                "2. 只勾选你需要翻译的普通应用；不要勾选 Android / 系统界面 / 输入法。\n" +
                "3. 强制停止并重新打开目标应用。\n" +
                "4. 第一次使用先让模型准备完成；之后翻译完全在本机运行。\n\n" +
                "性能策略\n\n" +
                "• TextView 主线程绝不等待翻译。\n" +
                "• 24ms 合并可见文字请求，减少滚动时 Binder/模型调用。\n" +
                "• 两级 RAM 缓存：目标 App 进程 + 翻译服务进程。\n" +
                "• RecyclerView 复用使用 generation 校验，过期结果直接丢弃。\n" +
                "• 不扫描 Dex、不 Hook StaticLayout、不写 SQLite 热路径。\n\n" +
                "当前 Alpha 版重点覆盖标准 Android TextView。Jetpack Compose / WebView 会在实机确认流畅度后单独适配。",
                15, false);
        steps.setPadding(0, dp(22), 0, dp(18));
        root.addView(steps);

        TextView attribution = text("Translation powered by Google ML Kit", 12, false);
        attribution.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(attribution, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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

    private void bindTranslator() {
        Intent intent = new Intent(this, TranslationService.class);
        try {
            bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (Throwable t) {
            status.setText("翻译服务连接失败：" + t.getClass().getSimpleName());
        }
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = new Messenger(binder);
            bound = true;
            prepareButton.setEnabled(true);
            warmup();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
            prepareButton.setEnabled(false);
            status.setText("翻译服务已断开");
        }
    };

    private void warmup() {
        Messenger target = service;
        if (target == null) {
            status.setText("翻译服务尚未连接");
            return;
        }
        status.setText("正在准备 Google 英语 / 西班牙语 / 中文模型…\n首次下载需要联网，之后可离线使用。");
        prepareButton.setEnabled(false);
        Message msg = Message.obtain(null, Protocol.MSG_WARMUP);
        msg.replyTo = replyMessenger;
        try {
            target.send(msg);
        } catch (RemoteException e) {
            status.setText("模型准备请求发送失败，请重试");
            prepareButton.setEnabled(true);
        }
    }

    private void clearCache() {
        Messenger target = service;
        if (target == null) return;
        Message msg = Message.obtain(null, Protocol.MSG_CLEAR_CACHE);
        msg.replyTo = replyMessenger;
        try {
            target.send(msg);
        } catch (RemoteException ignored) {
        }
    }

    private boolean handleReply(Message msg) {
        if (msg.what == Protocol.MSG_WARMUP_RESULT) {
            boolean ok = msg.getData().getBoolean(Protocol.KEY_OK, false);
            if (ok) {
                status.setText("✓ 翻译模型已就绪\n现在可离线执行英语 / 西班牙语 → 简体中文翻译");
            } else {
                String error = msg.getData().getString(Protocol.KEY_ERROR, "模型准备失败");
                status.setText("⚠ " + error);
            }
            prepareButton.setEnabled(true);
            return true;
        }
        if (msg.what == Protocol.MSG_CLEAR_CACHE_RESULT) {
            status.setText("✓ 翻译服务内存缓存已清空");
            return true;
        }
        return false;
    }

    @Override
    protected void onDestroy() {
        if (bound) {
            try { unbindService(connection); } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
