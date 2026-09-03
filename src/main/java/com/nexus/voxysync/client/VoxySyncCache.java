package com.nexus.voxysync.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.nexus.voxysync.network.VoxyPackets;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 客户端区域缓存（维度 → 文件名 → 时间戳/大小），用于增量同步：
 * 下次进服时把这些元数据发给服务端，一致的文件将被跳过。
 * 改编自 MapSyncer-rebuild 的 VoxySyncCache（GPL-3.0）。
 */
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
        Path baseDir = client.gameDirectory.toPath().resolve("voxysync");
        return new VoxySyncCache(baseDir);
    }

    public synchronized Map<String, VoxyPackets.RegionMeta> snapshotForDimension(String dimensionId) {
        Map<String, VoxyPackets.RegionMeta> snapshot = new HashMap<>();
        String prefix = dimensionId + "/";
        for (Map.Entry<String, CacheEntry> entry : cache.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                CacheEntry value = entry.getValue();
                // 只带文件名（服务端拼维度前缀），尽量减小 C2S 包
                snapshot.put(entry.getKey().substring(prefix.length()),
                        new VoxyPackets.RegionMeta(value.timestampSeconds, value.sizeBytes));
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
            LOGGER.warn("保存 voxy-sync-cache.json 失败", e);
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
            LOGGER.warn("加载 voxy-sync-cache.json 失败，忽略该缓存", e);
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
