package com.mapsyncer.util;

import java.util.concurrent.ConcurrentHashMap;

public final class BoundedStringPool {
    private static final int MAX_ENTRIES = 8_192;
    private static final int MAX_LENGTH = 128;
    private static final ConcurrentHashMap<String, String> POOL = new ConcurrentHashMap<>();

    private BoundedStringPool() {
    }

    public static String canonicalize(String value) {
        if (value == null || value.length() > MAX_LENGTH) {
            return value;
        }

        String existing = POOL.putIfAbsent(value, value);
        if (existing != null) {
            return existing;
        }

        if (POOL.size() > MAX_ENTRIES) {
            POOL.clear();
        }
        return value;
    }
}
