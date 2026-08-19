# Changelog

## 0.1.0-alpha1

- English / Spanish to Simplified Chinese only.
- Standard Android TextView LSPosed hook.
- Zero synchronous translation in host UI thread.
- 24 ms request coalescing, max 48 strings per Binder batch.
- Dedicated `:translator` process using Google ML Kit.
- Bundled language identification model.
- On-device translation model warmup and offline use after download.
- Host-process LRU cache (2048) + service-process LRU cache (4096).
- RecyclerView generation/source validation to discard stale results.
- Basic span/style preservation.
- Hard safety exclusions for Android, SystemUI, and input methods.
