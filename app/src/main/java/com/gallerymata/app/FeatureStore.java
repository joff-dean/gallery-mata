package com.gallerymata.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

final class FeatureStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "gallery_mata.db";

    FeatureStore(Context context) {
        super(context, DB_NAME, null, 1);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE features (uri TEXT PRIMARY KEY, modified INTEGER NOT NULL, vector BLOB NOT NULL, tokens TEXT NOT NULL, phash INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE moves (id INTEGER PRIMARY KEY AUTOINCREMENT, batch_id INTEGER NOT NULL, uri TEXT NOT NULL, old_path TEXT NOT NULL, new_path TEXT NOT NULL, moved_at INTEGER NOT NULL, undone INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX moves_batch_idx ON moves(batch_id, undone)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS features");
        db.execSQL("DROP TABLE IF EXISTS moves");
        onCreate(db);
    }

    FeatureRecord get(String uri, long modified) {
        try (Cursor cursor = getReadableDatabase().query(
                "features", new String[]{"vector", "tokens", "phash"},
                "uri=? AND modified=?", new String[]{uri, Long.toString(modified)},
                null, null, null)) {
            if (!cursor.moveToFirst()) return null;
            byte[] blob = cursor.getBlob(0);
            ByteBuffer buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
            float[] vector = new float[blob.length / 4];
            for (int i = 0; i < vector.length; i++) vector[i] = buffer.getFloat();
            Set<String> tokens = decodeTokens(cursor.getString(1));
            return new FeatureRecord(vector, tokens, cursor.getLong(2));
        }
    }

    void put(String uri, long modified, FeatureRecord record) {
        ByteBuffer buffer = ByteBuffer.allocate(record.vector.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : record.vector) buffer.putFloat(value);
        ContentValues values = new ContentValues();
        values.put("uri", uri);
        values.put("modified", modified);
        values.put("vector", buffer.array());
        values.put("tokens", String.join("\u001F", record.tokens));
        values.put("phash", record.perceptualHash);
        getWritableDatabase().insertWithOnConflict("features", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    long nextBatchId() {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT COALESCE(MAX(batch_id), 0) + 1 FROM moves", null)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 1L;
        }
    }

    void recordMove(long batchId, String uri, String oldPath, String newPath) {
        ContentValues values = new ContentValues();
        values.put("batch_id", batchId);
        values.put("uri", uri);
        values.put("old_path", oldPath);
        values.put("new_path", newPath);
        values.put("moved_at", System.currentTimeMillis());
        values.put("undone", 0);
        getWritableDatabase().insert("moves", null, values);
    }

    java.util.List<MoveRecord> latestUndoBatch() {
        java.util.ArrayList<MoveRecord> result = new java.util.ArrayList<>();
        long batch = -1;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT MAX(batch_id) FROM moves WHERE undone=0", null)) {
            if (c.moveToFirst() && !c.isNull(0)) batch = c.getLong(0);
        }
        if (batch < 0) return result;
        try (Cursor c = getReadableDatabase().query("moves",
                new String[]{"uri", "old_path", "new_path"}, "batch_id=? AND undone=0",
                new String[]{Long.toString(batch)}, null, null, "id ASC")) {
            while (c.moveToNext()) result.add(new MoveRecord(c.getString(0), c.getString(1), c.getString(2), batch));
        }
        return result;
    }

    void markBatchUndone(long batchId) {
        ContentValues values = new ContentValues();
        values.put("undone", 1);
        getWritableDatabase().update("moves", values, "batch_id=?", new String[]{Long.toString(batchId)});
    }

    private static Set<String> decodeTokens(String encoded) {
        if (encoded == null || encoded.trim().isEmpty()) return new HashSet<>();
        return new HashSet<>(Arrays.asList(encoded.split("\u001F", -1)));
    }

    static final class MoveRecord {
        final String uri;
        final String oldPath;
        final String newPath;
        final long batchId;

        MoveRecord(String uri, String oldPath, String newPath, long batchId) {
            this.uri = uri;
            this.oldPath = oldPath;
            this.newPath = newPath;
            this.batchId = batchId;
        }
    }
}
