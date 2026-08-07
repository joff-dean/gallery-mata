package com.gallerymata.app;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class AlbumRepository {
    private final Context context;

    AlbumRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    List<Album> scan() {
        Map<String, Album> albums = new LinkedHashMap<>();
        query(false, albums);
        query(true, albums);
        List<Album> result = new ArrayList<>(albums.values());
        result.removeIf(album -> album.items.isEmpty());
        result.sort((a, b) -> {
            int bySize = Integer.compare(b.items.size(), a.items.size());
            return bySize != 0 ? bySize : a.name.compareToIgnoreCase(b.name);
        });
        return result;
    }

    private void query(boolean videos, Map<String, Album> albums) {
        ContentResolver resolver = context.getContentResolver();
        Uri collection = videos
                ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
        String durationColumn = videos ? MediaStore.Video.VideoColumns.DURATION : null;
        List<String> projectionList = new ArrayList<>();
        Collections.addAll(projectionList,
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.WIDTH,
                MediaStore.MediaColumns.HEIGHT,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME);
        if (durationColumn != null) projectionList.add(durationColumn);

        try (Cursor cursor = resolver.query(collection,
                projectionList.toArray(new String[0]), null, null,
                MediaStore.MediaColumns.DATE_MODIFIED + " DESC")) {
            if (cursor == null) return;
            int idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            int pathCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH);
            int mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE);
            int widthCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH);
            int heightCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT);
            int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);
            int modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED);
            int albumCol = cursor.getColumnIndexOrThrow(MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME);
            int durationCol = durationColumn == null ? -1 : cursor.getColumnIndexOrThrow(durationColumn);

            while (cursor.moveToNext()) {
                long id = cursor.getLong(idCol);
                String path = MediaItem.normalizePath(cursor.getString(pathCol));
                String albumName = cursor.getString(albumCol);
                Album album = albums.computeIfAbsent(path, key -> new Album(key, albumName));
                album.items.add(new MediaItem(
                        ContentUris.withAppendedId(collection, id), id,
                        cursor.getString(nameCol), path, albumName,
                        cursor.getString(mimeCol), videos,
                        cursor.getInt(widthCol), cursor.getInt(heightCol),
                        durationCol < 0 ? 0 : cursor.getLong(durationCol),
                        cursor.getLong(sizeCol), cursor.getLong(modifiedCol)));
            }
        } catch (SecurityException ignored) {
            // The UI explains limited/denied access and lets the user request it again.
        }
    }

    static boolean looksLikeSource(Album album) {
        String value = (album.name + " " + album.relativePath).toLowerCase(Locale.ROOT)
                .replace(" ", "").replace("-", "").replace("_", "");
        return value.contains("screenshot") || value.contains("screenshots")
                || value.contains("screenrecord") || value.contains("화면캡처")
                || value.contains("화면녹화") || value.contains("download")
                || value.contains("다운로드");
    }
}
