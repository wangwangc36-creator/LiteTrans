package com.litetrans.module.util;

import android.text.Annotation;
import android.text.NoCopySpan;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.CharacterStyle;
import android.text.style.ParagraphStyle;
import android.text.style.ReplacementSpan;

public final class StyledText {
    private StyledText() {}

    public static CharSequence rebuild(CharSequence original, String translated) {
        if (!(original instanceof Spanned) || translated == null) return translated;

        Spanned source = (Spanned) original;
        SpannableString out = new SpannableString(translated);
        int oldLen = Math.max(1, source.length());
        int newLen = translated.length();

        Object[] spans = source.getSpans(0, source.length(), Object.class);
        for (Object span : spans) {
            if (!isSafeSpan(span)) continue;
            try {
                int oldStart = source.getSpanStart(span);
                int oldEnd = source.getSpanEnd(span);
                int flags = source.getSpanFlags(span);
                if (oldStart < 0 || oldEnd < oldStart) continue;

                int newStart;
                int newEnd;
                if (oldStart == 0 && oldEnd == source.length()) {
                    newStart = 0;
                    newEnd = newLen;
                } else {
                    newStart = Math.round((oldStart / (float) oldLen) * newLen);
                    newEnd = Math.round((oldEnd / (float) oldLen) * newLen);
                    newStart = Math.max(0, Math.min(newLen, newStart));
                    newEnd = Math.max(newStart, Math.min(newLen, newEnd));
                }
                out.setSpan(span, newStart, newEnd, flags);
            } catch (Throwable ignored) {
                // Styling must never be allowed to break host-app rendering.
            }
        }
        return out;
    }

    private static boolean isSafeSpan(Object span) {
        if (span instanceof NoCopySpan) return false;
        if (span instanceof ReplacementSpan) return false;
        return span instanceof CharacterStyle
                || span instanceof ParagraphStyle
                || span instanceof Annotation;
    }
}
