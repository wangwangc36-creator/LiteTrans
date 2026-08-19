package com.litetrans.module.hook;

import android.text.Editable;
import android.widget.EditText;
import android.widget.TextView;

import com.litetrans.module.util.Protocol;
import com.litetrans.module.util.QuickFilter;
import com.litetrans.module.util.StyledText;
import com.litetrans.module.util.TextEnvelope;

import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Lightweight TextView hook with compatibility fallbacks.
 * Hooks only the framework TextView class; no Dex scan and no synchronous translation.
 */
public final class LiteTransHook implements IXposedHookLoadPackage {
    static final String BYPASS_KEY = "litetrans:bypass";
    static final String GENERATION_KEY = "litetrans:generation";
    static final String SOURCE_KEY = "litetrans:source";

    private static final AtomicLong GENERATION = new AtomicLong(1L);
    private static final ThreadLocal<Integer> SET_TEXT_DEPTH = new ThreadLocal<>();

    private static final XC_MethodHook TEXT_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (!(param.thisObject instanceof TextView)) return;
            if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof CharSequence)) return;

            int depth = currentDepth();
            SET_TEXT_DEPTH.set(depth + 1);
            if (depth > 0) return;

            TextView view = (TextView) param.thisObject;
            try {
                Object bypass = XposedHelpers.getAdditionalInstanceField(view, BYPASS_KEY);
                if (Boolean.TRUE.equals(bypass)) {
                    XposedHelpers.removeAdditionalInstanceField(view, BYPASS_KEY);
                    return;
                }

                long generation = GENERATION.getAndIncrement();
                XposedHelpers.setAdditionalInstanceField(view, GENERATION_KEY, generation);
                XposedHelpers.removeAdditionalInstanceField(view, SOURCE_KEY);

                CharSequence original = (CharSequence) param.args[0];
                if (original == null || original.length() == 0) return;
                if (view instanceof EditText || original instanceof Editable) return;
                if (param.args.length > 1 && param.args[1] == TextView.BufferType.EDITABLE) return;

                TextEnvelope envelope = TextEnvelope.from(original);
                if (!QuickFilter.shouldTranslate(view, original, envelope.core)) return;

                TranslationClient client = TranslationClient.get(view.getContext());
                String cached = client.peek(envelope.core);
                XposedHelpers.setAdditionalInstanceField(view, SOURCE_KEY, envelope.core);

                if (cached != null) {
                    if (!cached.equals(envelope.core)) {
                        param.args[0] = StyledText.rebuild(original, envelope.wrap(cached));
                    }
                    return;
                }

                client.request(envelope.core, view, generation, original, envelope);
            } catch (Throwable ignored) {
                // Never destabilize the host app.
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
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

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || lpparam.packageName == null) return;
        if (Protocol.MODULE_PACKAGE.equals(lpparam.packageName)) return;
        if ("android".equals(lpparam.packageName)
                || "com.android.systemui".equals(lpparam.packageName)
                || lpparam.packageName.contains("inputmethod")) return;

        int installed = 0;
        installed += tryHook(lpparam, new Object[]{CharSequence.class, TEXT_HOOK});
        installed += tryHook(lpparam, new Object[]{CharSequence.class, TextView.BufferType.class, TEXT_HOOK});
        installed += tryHook(lpparam, new Object[]{CharSequence.class, TextView.BufferType.class,
                boolean.class, int.class, TEXT_HOOK});

        XposedBridge.log("[LiteTrans] " + lpparam.packageName + " TextView setText hooks=" + installed);
    }

    private static int tryHook(XC_LoadPackage.LoadPackageParam lpparam, Object[] signature) {
        try {
            XposedHelpers.findAndHookMethod(
                    "android.widget.TextView",
                    lpparam.classLoader,
                    "setText",
                    signature);
            return 1;
        } catch (Throwable ignored) {
            return 0;
        }
    }
}
