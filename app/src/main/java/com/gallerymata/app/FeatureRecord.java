package com.gallerymata.app;

import java.util.Collections;
import java.util.Set;

final class FeatureRecord {
    final float[] vector;
    final Set<String> tokens;
    final long perceptualHash;

    FeatureRecord(float[] vector, Set<String> tokens, long perceptualHash) {
        this.vector = vector;
        this.tokens = tokens == null ? Collections.emptySet() : tokens;
        this.perceptualHash = perceptualHash;
    }
}
