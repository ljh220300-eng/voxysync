package com.mapsyncer.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ClientConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientConfig.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("mapsyncer-client.json");

    public static final Values VALUES = new Values();

    private ClientConfig() {
    }

    public static void load() {
        if (Files.exists(CONFIG_PATH)) {
            try {
                Values loaded = GSON.fromJson(Files.readString(CONFIG_PATH), Values.class);
                if (loaded != null) {
                    VALUES.copyFrom(loaded);
                }
            } catch (IOException e) {
                LOGGER.warn("Failed to load client config, using defaults", e);
            }
        }
        save();
    }

    public static void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(VALUES));
        } catch (IOException e) {
            LOGGER.warn("Failed to save client config", e);
        }
    }

    public static class Values {
        public boolean autoSyncOnJoin = false;
        public boolean showSyncHud = true;
        public int syncProgressChatIntervalPercent = 0;
        public int autoSyncDelaySeconds = 3;

        void copyFrom(Values other) {
            this.autoSyncOnJoin = other.autoSyncOnJoin;
            this.showSyncHud = other.showSyncHud;
            this.syncProgressChatIntervalPercent = Math.max(0, other.syncProgressChatIntervalPercent);
            this.autoSyncDelaySeconds = Math.max(1, other.autoSyncDelaySeconds);
        }
    }
}
