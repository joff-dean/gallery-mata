package com.gallerymata.app;

import android.net.Uri;

final class MediaItem {
    final Uri uri;
    final long id;
    final String name;
    final String relativePath;
    final String albumName;
    final String mimeType;
    final boolean video;
    final int width;
    final int height;
    final long durationMs;
    final long size;
    final long modifiedSeconds;

    MediaItem(Uri uri, long id, String name, String relativePath, String albumName,
              String mimeType, boolean video, int width, int height,
              long durationMs, long size, long modifiedSeconds) {
        this.uri = uri;
        this.id = id;
        this.name = name == null ? "이름 없음" : name;
        this.relativePath = normalizePath(relativePath);
        this.albumName = albumName == null || albumName.trim().isEmpty()
                ? displayNameForPath(this.relativePath) : albumName;
        this.mimeType = mimeType == null ? "" : mimeType;
        this.video = video;
        this.width = width;
        this.height = height;
        this.durationMs = durationMs;
        this.size = size;
        this.modifiedSeconds = modifiedSeconds;
    }

    static String normalizePath(String value) {
        if (value == null || value.trim().isEmpty()) return "기타/";
        return value.endsWith("/") ? value : value + "/";
    }

    static String displayNameForPath(String value) {
        String path = normalizePath(value);
        String trimmed = path.substring(0, path.length() - 1);
        int slash = trimmed.lastIndexOf('/');
        return slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
    }
}
