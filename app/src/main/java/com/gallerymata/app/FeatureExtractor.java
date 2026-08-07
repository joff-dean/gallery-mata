package com.gallerymata.app;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.util.Size;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

final class FeatureExtractor implements AutoCloseable {
    private static final int VECTOR_SIZE = 80;
    private final Context context;
    private final TextRecognizer recognizer;

    FeatureExtractor(Context context) {
        this.context = context.getApplicationContext();
        recognizer = TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());
    }

    FeatureRecord extract(MediaItem item) throws Exception {
        Bitmap representative = null;
        try {
            if (item.video) return extractVideo(item);
            representative = context.getContentResolver().loadThumbnail(item.uri, new Size(768, 768), null);
            if (representative == null) throw new IllegalStateException("썸네일을 읽을 수 없습니다");
            float[] vector = visualVector(representative, item.width, item.height);
            Set<String> tokens = tokens(recognize(representative) + " " + item.name);
            long hash = differenceHash(representative);
            return new FeatureRecord(vector, tokens, hash);
        } finally {
            if (representative != null && !representative.isRecycled()) representative.recycle();
        }
    }

    private FeatureRecord extractVideo(MediaItem item) throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Bitmap middle = null;
        try {
            retriever.setDataSource(context, item.uri);
            long durationUs = Math.max(1_000_000L, item.durationMs * 1000L);
            long[] positions = {durationUs / 10, durationUs / 2, durationUs * 9 / 10};
            float[] average = new float[VECTOR_SIZE];
            int count = 0;
            for (long position : positions) {
                Bitmap frame = retriever.getFrameAtTime(position, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (frame == null) continue;
                float[] vector = visualVector(frame, item.width, item.height);
                for (int i = 0; i < average.length; i++) average[i] += vector[i];
                count++;
                if (position == positions[1]) middle = frame;
                else frame.recycle();
            }
            if (count == 0) throw new IllegalStateException("영상 프레임을 읽을 수 없습니다");
            for (int i = 0; i < average.length; i++) average[i] /= count;
            normalize(average);
            if (middle == null) {
                middle = retriever.getFrameAtTime(durationUs / 2, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }
            Set<String> text = tokens(item.name + " " + (middle == null ? "" : recognize(middle)));
            long hash = middle == null ? 0L : differenceHash(middle);
            return new FeatureRecord(average, text, hash);
        } finally {
            if (middle != null && !middle.isRecycled()) middle.recycle();
            retriever.release();
        }
    }

    private String recognize(Bitmap bitmap) {
        try {
            Text result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 15, TimeUnit.SECONDS);
            return result == null ? "" : result.getText();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static float[] visualVector(Bitmap source, int originalWidth, int originalHeight) {
        Bitmap bitmap = Bitmap.createScaledBitmap(source, 96, 96, true);
        float[] vector = new float[VECTOR_SIZE];
        double saturation = 0;
        double edge = 0;
        int lastLum = 0;

        for (int y = 0; y < 96; y++) {
            for (int x = 0; x < 96; x++) {
                int color = bitmap.getPixel(x, y);
                int r = Color.red(color);
                int g = Color.green(color);
                int b = Color.blue(color);
                int cell = (y / 24) * 4 + (x / 24);
                vector[cell * 3] += r / 255f;
                vector[cell * 3 + 1] += g / 255f;
                vector[cell * 3 + 2] += b / 255f;
                vector[48 + Math.min(3, r / 64)]++;
                vector[52 + Math.min(3, g / 64)]++;
                vector[56 + Math.min(3, b / 64)]++;
                int lum = (r * 30 + g * 59 + b * 11) / 100;
                vector[60 + Math.min(7, lum / 32)]++;
                vector[68 + y / 24] += lum / 255f;
                vector[72 + x / 24] += lum / 255f;
                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));
                saturation += max == 0 ? 0 : (max - min) / (double) max;
                if (x > 0) edge += Math.abs(lum - lastLum) / 255.0;
                lastLum = lum;
            }
        }
        for (int i = 0; i < 48; i++) vector[i] /= 576f;
        for (int i = 48; i < 68; i++) vector[i] /= 9216f;
        for (int i = 68; i < 76; i++) vector[i] /= 2304f;
        vector[76] = (float) (saturation / 9216.0);
        vector[77] = (float) (edge / (96.0 * 95.0));
        float ratio = originalHeight <= 0 ? source.getWidth() / (float) Math.max(1, source.getHeight())
                : originalWidth / (float) originalHeight;
        vector[78] = Math.min(3f, ratio) / 3f;
        vector[79] = Math.min(3f, 1f / Math.max(0.01f, ratio)) / 3f;
        normalize(vector);
        if (bitmap != source) bitmap.recycle();
        return vector;
    }

    private static void normalize(float[] vector) {
        double sum = 0;
        for (float value : vector) sum += value * value;
        double norm = Math.sqrt(sum);
        if (norm == 0) return;
        for (int i = 0; i < vector.length; i++) vector[i] /= (float) norm;
    }

    static Set<String> tokens(String text) {
        Set<String> result = new HashSet<>();
        if (text == null) return result;
        String normalized = text.toLowerCase(Locale.ROOT).replaceAll("[^0-9a-z가-힣]+", " ").trim();
        if (normalized.isEmpty()) return result;
        for (String word : normalized.split("\\s+")) {
            if (word.length() >= 2) result.add(word);
            if (word.length() >= 4) {
                for (int i = 0; i < word.length() - 1; i++) result.add("#" + word.substring(i, i + 2));
            }
        }
        return result;
    }

    private static long differenceHash(Bitmap source) {
        Bitmap bitmap = Bitmap.createScaledBitmap(source, 9, 8, true);
        long hash = 0L;
        int bit = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int left = luminance(bitmap.getPixel(x, y));
                int right = luminance(bitmap.getPixel(x + 1, y));
                if (left > right) hash |= (1L << bit);
                bit++;
            }
        }
        if (bitmap != source) bitmap.recycle();
        return hash;
    }

    private static int luminance(int color) {
        return (Color.red(color) * 30 + Color.green(color) * 59 + Color.blue(color) * 11) / 100;
    }

    @Override public void close() {
        recognizer.close();
    }
}
