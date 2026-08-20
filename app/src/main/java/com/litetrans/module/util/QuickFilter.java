package com.litetrans.module.util;

import android.text.Editable;
import android.widget.EditText;
import android.widget.TextView;

import java.util.regex.Pattern;

public final class QuickFilter {
    private QuickFilter() {}

    private static final Pattern URLISH = Pattern.compile(
            "(?i)^(?:https?://|www\\.|[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}|[a-z0-9_.-]+\\.(?:com|net|org|cl|io|dev)(?:/.*)?)$");

    public static boolean shouldTranslate(TextView view, CharSequence text, String core) {
        if (view == null || text == null || core == null) return false;
        if (view instanceof EditText || text instanceof Editable) return false;
        if (core.length() < 1 || core.length() > 4000) return false;
        if (containsCjk(core)) return false;
        if (!containsLetter(core)) return false;
        if (URLISH.matcher(core.trim()).matches()) return false;

        int letters = 0;
        int symbols = 0;
        for (int i = 0; i < core.length(); i++) {
            char c = core.charAt(i);
            if (Character.isLetter(c)) letters++;
            else if (!Character.isWhitespace(c) && !Character.isDigit(c)) symbols++;
        }
        return letters >= 1 && symbols < core.length();
    }

    public static boolean containsCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            Character.UnicodeBlock block = Character.UnicodeBlock.of(s.charAt(i));
            if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsLetter(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetter(s.charAt(i))) return true;
        }
        return false;
    }
}
