package com.nexus.voxysync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 配置文件（config/voxysync.json，服务端与客户端共用一个文件）。
 *
 * <p>字段说明：</p>
 * <ul>
 *   <li>enableVoxySync —— 服务端是否开启 Voxy 区域同步（默认 false）。</li>
 *   <li>syncMode —— "radius"（默认，只同步玩家周围 {@code radiusBlocks} 内的区域）
 *       或 "all"（全图；发送完整 MCA 数据，存在透视级泄露风险，等效于"显式开启"全图）。</li>
 *   <li>radiusBlocks —— radius 模式半径（方块），默认 2000。</li>
 *   <li>speedLimitKBps —— 每个玩家限速（KB/s），默认 1024；&lt;=0 不限速。</li>
 *   <li>maxPacketSize —— 单个区域分片最大字节数（默认 262144，即 256KB）。</li>
 *   <li>autoStartOnJoin —— 客户端进服后自动请求同步当前维度（默认 true）。</li>
 * </ul>
 */
public final class VoxySyncConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("voxysync.json");

    public static final Config INSTANCE = new Config();

    public static class Config {
        public boolean enableVoxySync = false;
        public String syncMode = "radius";
        public int radiusBlocks = 2000;
        public int speedLimitKBps = 1024;
        public int maxPacketSize = 262144;
        public boolean autoStartOnJoin = true;
    }

    private VoxySyncConfig() {
    }

    public static void load() {
        if (Files.exists(CONFIG_PATH)) {
            try {
                Config loaded = GSON.fromJson(Files.readString(CONFIG_PATH), Config.class);
                if (loaded != null) {
                    VoxySyncConfig.copyFrom(loaded);
                }
            } catch (Exception e) {
                VoxySyncMod.LOGGER.error("加载 config/voxysync.json 失败，使用默认配置", e);
            }
        }
        if (!"radius".equals(INSTANCE.syncMode) && !"all".equals(INSTANCE.syncMode)) {
            INSTANCE.syncMode = "radius";
        }
        // 回写一次：保证配置文件始终含有全部键，便于用户阅读/修改
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(INSTANCE));
        } catch (IOException e) {
            VoxySyncMod.LOGGER.error("保存 config/voxysync.json 失败", e);
        }
    }

    public static void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(INSTANCE));
        } catch (IOException e) {
            VoxySyncMod.LOGGER.error("保存 config/voxysync.json 失败", e);
        }
    }

    public static void copyFrom(Config other) {
        INSTANCE.enableVoxySync = other.enableVoxySync;
        INSTANCE.syncMode = other.syncMode;
        INSTANCE.radiusBlocks = other.radiusBlocks;
        INSTANCE.speedLimitKBps = other.speedLimitKBps;
        INSTANCE.maxPacketSize = other.maxPacketSize;
        INSTANCE.autoStartOnJoin = other.autoStartOnJoin;
    }
}
