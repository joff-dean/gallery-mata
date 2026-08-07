package com.gallerymata.app;

import java.util.ArrayList;
import java.util.List;

final class Album {
    final String relativePath;
    final String name;
    final List<MediaItem> items = new ArrayList<>();

    Album(String relativePath, String name) {
        this.relativePath = MediaItem.normalizePath(relativePath);
        this.name = name == null || name.trim().isEmpty()
                ? MediaItem.displayNameForPath(this.relativePath) : name;
    }

    String label() {
        return name + "  ·  " + items.size() + "개\n" + relativePath;
    }
}
