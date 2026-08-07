package com.gallerymata.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class ClassifierEngine {
    interface Progress {
        void update(int done, int total, String message);
    }

    static final class Suggestion {
        final MediaItem item;
        Album target;
        final float confidence;
        final float similarity;
        boolean selected;

        Suggestion(MediaItem item, Album target, float confidence, float similarity, boolean selected) {
            this.item = item;
            this.target = target;
            this.confidence = confidence;
            this.similarity = similarity;
            this.selected = selected;
        }

        String confidenceLabel() {
            if (confidence >= 0.82f) return "높은 확신";
            if (confidence >= 0.62f) return "확인 필요";
            return "낮은 확신";
        }
    }

    private static final class Model {
        final Album album;
        final List<FeatureRecord> examples = new ArrayList<>();
        final Set<String> tokens = new HashSet<>();

        Model(Album album) { this.album = album; }
    }

    private final FeatureStore store;
    private final FeatureExtractor extractor;

    ClassifierEngine(FeatureStore store, FeatureExtractor extractor) {
        this.store = store;
        this.extractor = extractor;
    }

    List<Suggestion> analyze(List<Album> targets, List<Album> sources, Progress progress) throws Exception {
        int trainingTotal = 0;
        for (Album album : targets) trainingTotal += Math.min(24, album.items.size());
        int sourceTotal = 0;
        for (Album album : sources) sourceTotal += Math.min(300, album.items.size());
        int total = trainingTotal + sourceTotal;
        int done = 0;

        List<Model> models = new ArrayList<>();
        for (Album album : targets) {
            Model model = new Model(album);
            for (MediaItem item : sample(album.items, 24)) {
                FeatureRecord feature = feature(item);
                model.examples.add(feature);
                model.tokens.addAll(feature.tokens);
                done++;
                progress.update(done, total, "기준 학습 · " + album.name);
            }
            if (!model.examples.isEmpty()) models.add(model);
        }
        if (models.isEmpty()) throw new IllegalStateException("학습할 수 있는 기준 앨범이 없습니다.");

        List<Suggestion> result = new ArrayList<>();
        for (Album source : sources) {
            int count = 0;
            for (MediaItem item : source.items) {
                if (count++ >= 300) break;
                FeatureRecord candidate;
                try {
                    candidate = feature(item);
                } catch (Exception error) {
                    done++;
                    progress.update(done, total, "건너뜀 · " + item.name);
                    continue;
                }
                List<ScoredModel> scored = new ArrayList<>();
                for (Model model : models) scored.add(new ScoredModel(model, score(candidate, model)));
                scored.sort((a, b) -> Float.compare(b.score, a.score));
                ScoredModel best = scored.get(0);
                float second = scored.size() > 1 ? scored.get(1).score : Math.max(0, best.score - 0.2f);
                float margin = Math.max(0f, best.score - second);
                float confidence = clamp(0.20f + margin * 3.2f + Math.max(0f, best.score - 0.72f) * 1.7f);
                boolean selected = confidence >= 0.82f && margin >= 0.08f;
                result.add(new Suggestion(item, best.model.album, confidence, best.score, selected));
                done++;
                progress.update(done, total, "분류 중 · " + item.name);
            }
        }
        result.sort(Comparator.comparingDouble((Suggestion s) -> s.confidence).reversed());
        return result;
    }

    private FeatureRecord feature(MediaItem item) throws Exception {
        FeatureRecord cached = store.get(item.uri.toString(), item.modifiedSeconds);
        if (cached != null) return cached;
        FeatureRecord fresh = extractor.extract(item);
        store.put(item.uri.toString(), item.modifiedSeconds, fresh);
        return fresh;
    }

    private static List<MediaItem> sample(List<MediaItem> items, int limit) {
        if (items.size() <= limit) return items;
        List<MediaItem> sampled = new ArrayList<>();
        double step = items.size() / (double) limit;
        for (int i = 0; i < limit; i++) sampled.add(items.get(Math.min(items.size() - 1, (int) Math.floor(i * step))));
        return sampled;
    }

    private static float score(FeatureRecord candidate, Model model) {
        List<Float> similarities = new ArrayList<>();
        int nearestHashDistance = 64;
        for (FeatureRecord example : model.examples) {
            similarities.add(cosine(candidate.vector, example.vector));
            nearestHashDistance = Math.min(nearestHashDistance,
                    Long.bitCount(candidate.perceptualHash ^ example.perceptualHash));
        }
        similarities.sort(Collections.reverseOrder());
        int k = Math.min(3, similarities.size());
        float visual = 0;
        for (int i = 0; i < k; i++) visual += similarities.get(i);
        visual /= Math.max(1, k);
        float text = tokenCoverage(candidate.tokens, model.tokens);
        float score = candidate.tokens.isEmpty() ? visual : visual * 0.72f + text * 0.28f;
        if (nearestHashDistance <= 5) score = Math.min(1f, score + 0.16f);
        return score;
    }

    private static float cosine(float[] a, float[] b) {
        int length = Math.min(a.length, b.length);
        float sum = 0;
        for (int i = 0; i < length; i++) sum += a[i] * b[i];
        return Math.max(0f, Math.min(1f, sum));
    }

    private static float tokenCoverage(Set<String> candidate, Set<String> album) {
        if (candidate.isEmpty() || album.isEmpty()) return 0f;
        int overlap = 0;
        for (String token : candidate) if (album.contains(token)) overlap++;
        return Math.min(1f, overlap / (float) Math.max(3, candidate.size()));
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static final class ScoredModel {
        final Model model;
        final float score;
        ScoredModel(Model model, float score) { this.model = model; this.score = score; }
    }
}
