package com.litetrans.module.util;

public final class Protocol {
    private Protocol() {}

    public static final int MSG_TRANSLATE_BATCH = 1;
    public static final int MSG_TRANSLATE_RESULT = 2;
    public static final int MSG_WARMUP = 3;
    public static final int MSG_WARMUP_RESULT = 4;
    public static final int MSG_CLEAR_CACHE = 5;
    public static final int MSG_CLEAR_CACHE_RESULT = 6;

    public static final String KEY_TEXTS = "texts";
    public static final String KEY_TRANSLATIONS = "translations";
    public static final String KEY_OK = "ok";
    public static final String KEY_ERROR = "error";

    public static final String MODULE_PACKAGE = "com.litetrans.module";
    public static final String SERVICE_CLASS = "com.litetrans.module.service.TranslationService";
}
