package com.litetrans.module.service;

import android.util.LruCache;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.litetrans.module.util.QuickFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Single-process Google ML Kit engine. All expensive work stays outside hooked apps.
 */
final class TranslationEngine {
    private static final int SERVICE_CACHE_ENTRIES = 4096;

    private final Object modelLock = new Object();
    private final Object cacheLock = new Object();
    private final LruCache<String, String> cache = new LruCache<>(SERVICE_CACHE_ENTRIES);

    private final LanguageIdentifier languageIdentifier;
    private final Translator englishToChinese;
    private final Translator spanishToChinese;
    private volatile boolean modelsReady;

    TranslationEngine() {
        languageIdentifier = LanguageIdentification.getClient(
                new LanguageIdentificationOptions.Builder()
                        .setConfidenceThreshold(0.34f)
                        .build());

        englishToChinese = Translation.getClient(new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.CHINESE)
                .build());
        spanishToChinese = Translation.getClient(new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.SPANISH)
                .setTargetLanguage(TranslateLanguage.CHINESE)
                .build());
    }

    boolean ensureModelsReady() {
        if (modelsReady) return true;
        synchronized (modelLock) {
            if (modelsReady) return true;
            try {
                DownloadConditions conditions = new DownloadConditions.Builder().build();
                // Never blocks a hooked app's UI thread: this runs in LiteTrans' worker process.
                Tasks.await(englishToChinese.downloadModelIfNeeded(conditions), 120, TimeUnit.SECONDS);
                Tasks.await(spanishToChinese.downloadModelIfNeeded(conditions), 120, TimeUnit.SECONDS);
                modelsReady = true;
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    ArrayList<String> translateBatch(List<String> texts) {
        ArrayList<String> out = new ArrayList<>(texts.size());
        boolean ready = ensureModelsReady();
        for (String text : texts) {
            if (text == null || text.isEmpty()) {
                out.add(text == null ? "" : text);
                continue;
            }
            if (!ready) {
                out.add(text);
                continue;
            }
            out.add(translateOne(text));
        }
        return out;
    }

    private String translateOne(String text) {
        synchronized (cacheLock) {
            String hit = cache.get(text);
            if (hit != null) return hit;
        }

        String result = text;
        try {
            if (QuickFilter.containsCjk(text)) return remember(text, text);

            SourceLanguage sourceLanguage = detectSource(text);
            if (sourceLanguage == SourceLanguage.ENGLISH) {
                result = Tasks.await(englishToChinese.translate(text), 8, TimeUnit.SECONDS);
            } else if (sourceLanguage == SourceLanguage.SPANISH) {
                result = Tasks.await(spanishToChinese.translate(text), 8, TimeUnit.SECONDS);
            }
        } catch (Throwable ignored) {
            result = text;
        }

        if (result == null || result.trim().isEmpty()) result = text;
        return remember(text, result);
    }

    private SourceLanguage detectSource(String text) {
        try {
            String code = Tasks.await(languageIdentifier.identifyLanguage(text), 2, TimeUnit.SECONDS);
            if (code != null) {
                if (code.equals("en") || code.startsWith("en-")) return SourceLanguage.ENGLISH;
                if (code.equals("es") || code.startsWith("es-")) return SourceLanguage.SPANISH;
            }
        } catch (Throwable ignored) {
        }

        // Do not guess for short/ambiguous labels: translating another Latin language would
        // violate LiteTrans' strict English/Spanish-only scope.
        return SourceLanguage.OTHER;
    }

    private String remember(String source, String translation) {
        synchronized (cacheLock) {
            cache.put(source, translation);
        }
        return translation;
    }

    void clearCache() {
        synchronized (cacheLock) {
            cache.evictAll();
        }
    }

    void close() {
        try { languageIdentifier.close(); } catch (Throwable ignored) {}
        try { englishToChinese.close(); } catch (Throwable ignored) {}
        try { spanishToChinese.close(); } catch (Throwable ignored) {}
    }

    private enum SourceLanguage {
        ENGLISH, SPANISH, OTHER
    }
}
