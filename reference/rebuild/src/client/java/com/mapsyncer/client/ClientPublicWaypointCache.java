package com.mapsyncer.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

final class ClientPublicWaypointCache {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientPublicWaypointCache.class);
    private static final String FILE_NAME = "public_waypoints.cache";
    private static final String KEY_HASH = "hash";
    private static final String KEY_STATUS = "status";
    private static final String KEY_UPDATED = "updatedAtMillis";

    private ClientPublicWaypointCache() {
    }

    static String loadHash(Path serverDir) {
        Properties props = load(serverDir);
        return props.getProperty(KEY_HASH, "");
    }

    static void save(Path serverDir, String hash, String status) {
        if (serverDir == null) {
            return;
        }
        try {
            Files.createDirectories(serverDir);
            Properties props = new Properties();
            props.setProperty(KEY_HASH, hash == null ? "" : hash);
            props.setProperty(KEY_STATUS, status == null ? "" : status);
            props.setProperty(KEY_UPDATED, String.valueOf(System.currentTimeMillis()));
            try (var out = Files.newOutputStream(cacheFile(serverDir))) {
                props.store(out, "MapSyncer public waypoint cache");
            }
        } catch (IOException e) {
            LOGGER.debug("Failed to save public waypoint cache", e);
        }
    }

    private static Properties load(Path serverDir) {
        Properties props = new Properties();
        if (serverDir == null) {
            return props;
        }
        Path file = cacheFile(serverDir);
        if (!Files.exists(file)) {
            return props;
        }
        try (var in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            LOGGER.debug("Failed to load public waypoint cache", e);
        }
        return props;
    }

    private static Path cacheFile(Path serverDir) {
        return serverDir.resolve(FILE_NAME);
    }
}
