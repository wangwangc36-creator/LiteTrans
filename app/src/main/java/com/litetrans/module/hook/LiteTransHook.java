package com.litetrans.module.hook;

import android.app.Application;
import android.content.Context;
import android.text.Editable;
import android.widget.EditText;
import android.widget.TextView;

import com.litetrans.module.util.Protocol;
import com.litetrans.module.util.QuickFilter;
import com.litetrans.module.util.StyledText;
import com.litetrans.module.util.TextEnvelope;

import java.lang.reflect.Method;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Lightweight TextView hook.
 *
 * Important compatibility rule: do not use XposedHelpers at runtime. Some LSPosed forks keep
 * XposedBridge's core hookMethod API but do not expose the complete XposedHelpers compatibility
 * surface. Methods are resolved with normal Java reflection and installed with
 * XposedBridge.hookMethod(Member, XC_MethodHook).
 */
public final class LiteTransHook implements IXposedHookLoadPackage {
    private static final AtomicLong GENERATION = new AtomicLong(1L);
    private static final ThreadLocal<Integer> SET_TEXT_DEPTH = new ThreadLocal<>();

    private static final Object VIEW_STATE_LOCK = new Object();
    private static final WeakHashMap<TextView, ViewState> VIEW_STATES = new WeakHashMap<>();

    static final class ViewState {
        long generation;
        String source;
        boolean bypass;
    }

    static ViewState stateFor(TextView view) {
        synchronized (VIEW_STATE_LOCK) {
            ViewState state = VIEW_STATES.get(view);
            if (state == null) {
                state = new ViewState();
                VIEW_STATES.put(view, state);
            }
            return state;
        }
    }

    private static final XC_MethodHook TEXT_HOOK = new XC_MethodHook() {
        @Override protected void beforeHookedMethod(MethodHookParam param) {
            if (!(param.thisObject instanceof TextView)) return;
            if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof CharSequence)) return;

            int depth = currentDepth();
            SET_TEXT_DEPTH.set(depth + 1);
            if (depth > 0) return;

            TextView view = (TextView) param.thisObject;
            try {
                ViewState state = stateFor(view);
                synchronized (state) {
                    if (state.bypass) {
                        state.bypass = false;
                        return;
                    }
                    state.generation = GENERATION.getAndIncrement();
                    state.source = null;
                }

                CharSequence original = (CharSequence) param.args[0];
                if (original == null || original.length() == 0) return;
                if (view instanceof EditText || original instanceof Editable) return;
                if (param.args.length > 1 && param.args[1] == TextView.BufferType.EDITABLE) return;

                TextEnvelope envelope = TextEnvelope.from(original);
                if (!QuickFilter.shouldTranslate(view, original, envelope.core)) return;

                final long generation;
                synchronized (state) {
                    state.source = envelope.core;
                    generation = state.generation;
                }

                TranslationClient client = TranslationClient.get(view.getContext());
                String cached = client.peek(envelope.core);
                if (cached != null) {
                    if (!cached.equals(envelope.core)) {
                        param.args[0] = StyledText.rebuild(original, envelope.wrap(cached));
                    }
                    return;
                }
                client.request(envelope.core, view, generation, original, envelope);
            } catch (Throwable t) {
                XposedBridge.log("[LiteTrans] TextView callback error: " + t);
            }
        }

        @Override protected void afterHookedMethod(MethodHookParam param) {
            if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof CharSequence)) return;
            int depth = currentDepth();
            if (depth <= 1) SET_TEXT_DEPTH.remove();
            else SET_TEXT_DEPTH.set(depth - 1);
        }
    };

    private static int currentDepth() {
        Integer value = SET_TEXT_DEPTH.get();
        return value == null ? 0 : value;
    }

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || lpparam.packageName == null) return;
        if (Protocol.MODULE_PACKAGE.equals(lpparam.packageName)) return;
        if ("android".equals(lpparam.packageName)
                || "com.android.systemui".equals(lpparam.packageName)
                || lpparam.packageName.contains("inputmethod")) return;

        int heartbeatInstalled = installHeartbeat(lpparam.packageName);
        int textHooks = installTextViewHooks();
        XposedBridge.log("[LiteTrans] " + lpparam.packageName
                + " bridge=hookMethod heartbeat=" + heartbeatInstalled
                + " TextView hooks=" + textHooks);
    }

    private static int installHeartbeat(final String packageName) {
        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            attach.setAccessible(true);
            XposedBridge.hookMethod(attach, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Context context = (Context) param.args[0];
                        TranslationClient.get(context).reportHookLoaded();
                    } catch (Throwable t) {
                        XposedBridge.log("[LiteTrans] heartbeat failed " + packageName + ": " + t);
                    }
                }
            });
            return 1;
        } catch (Throwable t) {
            XposedBridge.log("[LiteTrans] Application.attach hook failed in " + packageName + ": " + t);
            return 0;
        }
    }

    private static int installTextViewHooks() {
        int installed = 0;
        installed += hookTextMethod(CharSequence.class);
        installed += hookTextMethod(CharSequence.class, TextView.BufferType.class);
        installed += hookTextMethod(CharSequence.class, TextView.BufferType.class, boolean.class, int.class);
        return installed;
    }

    private static int hookTextMethod(Class<?>... parameterTypes) {
        try {
            Method method = TextView.class.getDeclaredMethod("setText", parameterTypes);
            method.setAccessible(true);
            XposedBridge.hookMethod(method, TEXT_HOOK);
            return 1;
        } catch (Throwable t) {
            XposedBridge.log("[LiteTrans] setText reflection hook failed params="
                    + parameterTypes.length + ": " + t);
            return 0;
        }
    }
}
