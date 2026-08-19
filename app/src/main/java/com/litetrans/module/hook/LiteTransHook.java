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
 * Minimal LSPosed hook: TextView only, zero synchronous translation and no Dex scan.
 */
public final class LiteTransHook implements IXposedHookLoadPackage {
    static final String BYPASS_KEY = "litetrans:bypass";
    static final String GENERATION_KEY = "litetrans:generation";
    static final String SOURCE_KEY = "litetrans:source";

    private static final AtomicLong GENERATION = new AtomicLong(1L);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || lpparam.packageName == null) return;
        if (Protocol.MODULE_PACKAGE.equals(lpparam.packageName)) return;

        // Hard safety exclusions. The module is intended for user apps, not boot-critical UI.
        if ("android".equals(lpparam.packageName)
                || "com.android.systemui".equals(lpparam.packageName)
                || lpparam.packageName.contains("inputmethod")) {
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(
                    "android.widget.TextView",
                    lpparam.classLoader,
                    "setText",
                    CharSequence.class,
                    TextView.BufferType.class,
                    boolean.class,
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof TextView)) return;
                            TextView view = (TextView) param.thisObject;

                            Object bypass = XposedHelpers.getAdditionalInstanceField(view, BYPASS_KEY);
                            if (Boolean.TRUE.equals(bypass)) {
                                XposedHelpers.removeAdditionalInstanceField(view, BYPASS_KEY);
                                return;
                            }

                            // Every host setText invalidates older async results, even if this text is skipped.
                            long generation = GENERATION.getAndIncrement();
                            XposedHelpers.setAdditionalInstanceField(view, GENERATION_KEY, generation);
                            XposedHelpers.removeAdditionalInstanceField(view, SOURCE_KEY);

                            CharSequence original = (CharSequence) param.args[0];
                            if (original == null || original.length() == 0) return;
                            if (view instanceof EditText || original instanceof Editable) return;
                            if (param.args[1] == TextView.BufferType.EDITABLE) return;

                            TextEnvelope envelope = TextEnvelope.from(original);
                            if (!QuickFilter.shouldTranslate(view, original, envelope.core)) return;

                            TranslationClient client = TranslationClient.get(view.getContext());
                            String cached = client.peek(envelope.core);
                            XposedHelpers.setAdditionalInstanceField(view, SOURCE_KEY, envelope.core);

                            if (cached != null) {
                                param.args[0] = StyledText.rebuild(original, envelope.wrap(cached));
                                return;
                            }

                            // Do not block this setText call. The host app renders immediately.
                            client.request(envelope.core, view, generation, original, envelope);
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log("[LiteTrans] TextView hook failed in " + lpparam.packageName + ": " + t);
        }
    }
}
