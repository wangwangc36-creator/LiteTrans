package com.litetrans.module.service;

import android.util.LruCache;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

final class TranslationEngine {
    private final Object lock = new Object();
    private final LruCache<String, String> cache = new LruCache<>(4096);

    private final Translator englishToChinese = Translation.getClient(
            new TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.ENGLISH)
                    .setTargetLanguage(TranslateLanguage.CHINESE)
                    .build());

    private final Translator spanishToChinese = Translation.getClient(
            new TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.SPANISH)
                    .setTargetLanguage(TranslateLanguage.CHINESE)
                    .build());

    private volatile boolean ready;

    boolean ensureModelsReady() {
        if (ready) return true;
        synchronized (lock) {
            if (ready) return true;
            try {
                DownloadConditions c = new DownloadConditions.Builder().build();
                Tasks.await(englishToChinese.downloadModelIfNeeded(c), 120, TimeUnit.SECONDS);
                Tasks.await(spanishToChinese.downloadModelIfNeeded(c), 120, TimeUnit.SECONDS);
                ready = true;
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    ArrayList<String> translateBatch(List<String> texts) {
        ArrayList<String> out = new ArrayList<>(texts.size());
        ensureModelsReady();
        for (String text : texts) {
            out.add(translateOne(text));
        }
        return out;
    }

    private String translateOne(String text) {
        if (text == null || text.trim().isEmpty()) return text;
        synchronized (cache) {
            String hit = cache.get(text);
            if (hit != null) return hit;
        }

        String result = text;
        try {
            String lower = text.toLowerCase(Locale.ROOT);
            if (looksSpanish(lower)) {
                result = Tasks.await(spanishToChinese.translate(text), 8, TimeUnit.SECONDS);
            } else {
                // English is the default fallback for Latin text in LiteTrans scope.
                result = Tasks.await(englishToChinese.translate(text), 8, TimeUnit.SECONDS);
            }
        } catch (Throwable ignored) {
            result = text;
        }

        if (result == null || result.trim().isEmpty()) result = text;
        synchronized (cache) {
            cache.put(text, result);
        }
        return result;
    }

    private boolean looksSpanish(String s) {
        return s.contains("ñ") || s.contains("¿") || s.contains("¡")
                || s.contains(" el ") || s.startsWith("el ")
                || s.contains(" la ") || s.startsWith("la ")
                || s.contains(" de ") || s.contains(" que ")
                || s.contains(" y ") || s.contains(" es ");
    }

    void clearCache() {
        synchronized (cache) { cache.evictAll(); }
    }

    void close() {
        try { englishToChinese.close(); } catch (Throwable ignored) {}
        try { spanishToChinese.close(); } catch (Throwable ignored) {}
    }
}
