package com.nexus.voxysync.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 每日同步完成记录（config/voxysync-daily.json）：玩家 UUID -> 完成日期（yyyy-MM-dd）。
 * 用于“每人每天最多同步一次”：当天成功（含无需同步）即记录，次日自动解锁；
 * 失败/手动中止不记录（可重试）。管理员 /voxysync sync 旁路不受限。
 */
public final class VoxySyncDailyState {
    private static final Logger LOGGER = LoggerFactory.getLogger(VoxySyncDailyState.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Map<String, String> done = new HashMap<>();

    public VoxySyncDailyState(Path file) {
        this.file = file;
        load();
    }

    /** 键 = 玩家UUID + "/" + 维度id：每个维度每天独立一次（跨维度不互锁） */
    private static String key(UUID playerId, String dimensionId) {
        return playerId + "/" + dimensionId;
    }

    public boolean isDoneToday(UUID playerId, String dimensionId, LocalDate today) {
        return today.toString().equals(done.get(key(playerId, dimensionId)));
    }

    public synchronized void markDone(UUID playerId, String dimensionId, LocalDate today) {
        done.put(key(playerId, dimensionId), today.toString());
        save();
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            Map<String, String> loaded = GSON.fromJson(Files.readString(file),
                    new com.google.gson.reflect.TypeToken<Map<String, String>>() {}.getType());
            if (loaded != null) {
                done.clear();
                for (Map.Entry<String, String> entry : loaded.entrySet()) {
                    // 旧格式（仅 UUID 无维度）作废，避免误锁新维度
                    if (entry.getKey().contains("/")) {
                        done.put(entry.getKey(), entry.getValue());
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("加载 voxy-sync-daily.json 失败，忽略", e);
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(done));
        } catch (IOException e) {
            LOGGER.warn("保存 voxy-sync-daily.json 失败", e);
        }
    }
}
