# Architecture

## Pipeline

1. `AlbumRepository` queries image and video collections from `MediaStore`.
2. `FeatureExtractor` creates an 80-dimensional visual descriptor, OCR tokens, and a 64-bit difference hash. Videos use three representative frames.
3. `FeatureStore` caches features by content URI and modified timestamp.
4. `ClassifierEngine` samples up to 24 examples per target album and scores each source item against balanced album prototypes.
5. `ResultAdapter` keeps uncertain results unchecked and allows the destination album to be overridden.
6. `MainActivity` requests scoped write access, updates `RELATIVE_PATH`, and journals successful moves for undo.

## Score

When OCR tokens exist:

```text
score = visual_top3_mean * 0.72 + token_coverage * 0.28
```

A near-duplicate perceptual hash match adds a bounded boost. The displayed confidence also considers the margin between the first- and second-ranked album. Only confidence of at least 0.82 with a sufficient margin is selected automatically.

## Limits

- Up to 24 representative training items are sampled per album.
- Up to 300 source items are analyzed per selected source folder in one run.
- All work is serialized to limit memory and ML model pressure; thumbnail loading uses a separate two-thread pool.
