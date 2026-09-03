package com.mapsyncer.client.voxy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mapsyncer.network.PacketHandler;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class VoxySyncCache {
    private static final Logger LOGGER = LoggerFactory.getLogger(VoxySyncCache.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type CACHE_TYPE = new TypeToken<Map<String, CacheEntry>>() {}.getType();

    private final Path cacheFile;
    private final Map<String, CacheEntry> cache = new HashMap<>();

    public VoxySyncCache(Path baseDir) {
        this.cacheFile = baseDir.resolve("voxy-sync-cache.json");
        load();
    }

    public static VoxySyncCache create(Minecraft client) {
        Path baseDir = client.gameDirectory.toPath().resolve("mapsyncer");
        return new VoxySyncCache(baseDir);
    }

    public synchronized Map<String, PacketHandler.VoxyRegionMeta> snapshotForDimension(String dimensionId) {
        Map<String, PacketHandler.VoxyRegionMeta> snapshot = new HashMap<>();
        String prefix = dimensionId + "/";
        for (Map.Entry<String, CacheEntry> entry : cache.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                CacheEntry value = entry.getValue();
                snapshot.put(entry.getKey(), new PacketHandler.VoxyRegionMeta(value.timestampSeconds, value.sizeBytes));
            }
        }
        return snapshot;
    }

    public synchronized void update(String dimensionId, String fileName, long timestampSeconds, long sizeBytes) {
        cache.put(dimensionId + "/" + fileName, new CacheEntry(timestampSeconds, sizeBytes));
    }

    public synchronized void save() {
        try {
            Files.createDirectories(cacheFile.getParent());
            Files.writeString(cacheFile, GSON.toJson(cache));
        } catch (IOException e) {
            LOGGER.warn("Failed to save Voxy sync cache", e);
        }
    }

    private synchronized void load() {
        if (!Files.exists(cacheFile)) {
            return;
        }
        try {
            Map<String, CacheEntry> loaded = GSON.fromJson(Files.readString(cacheFile), CACHE_TYPE);
            if (loaded != null) {
                cache.clear();
                cache.putAll(loaded);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to load Voxy sync cache, ignoring it", e);
        }
    }

    private static class CacheEntry {
        long timestampSeconds;
        long sizeBytes;

        CacheEntry(long timestampSeconds, long sizeBytes) {
            this.timestampSeconds = timestampSeconds;
            this.sizeBytes = sizeBytes;
        }
    }
}
