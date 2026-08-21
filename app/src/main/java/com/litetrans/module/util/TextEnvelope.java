package com.litetrans.module.util;

public final class TextEnvelope {
    public final String prefix;
    public final String core;
    public final String suffix;

    private TextEnvelope(String prefix, String core, String suffix) {
        this.prefix = prefix;
        this.core = core;
        this.suffix = suffix;
    }

    public static TextEnvelope from(CharSequence input) {
        String s = input == null ? "" : input.toString();
        int start = 0;
        int end = s.length();
        while (start < end && Character.isWhitespace(s.charAt(start))) start++;
        while (end > start && Character.isWhitespace(s.charAt(end - 1))) end--;
        return new TextEnvelope(s.substring(0, start), s.substring(start, end), s.substring(end));
    }

    public String wrap(String translatedCore) {
        return prefix + translatedCore + suffix;
    }
}
