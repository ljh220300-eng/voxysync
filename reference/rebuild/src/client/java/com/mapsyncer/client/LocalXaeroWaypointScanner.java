package com.mapsyncer.client;

import com.mapsyncer.network.PacketHandler;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class LocalXaeroWaypointScanner {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalXaeroWaypointScanner.class);
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "mapsyncer-local-waypoint-scan");
        thread.setDaemon(true);
        return thread;
    });

    private LocalXaeroWaypointScanner() {
    }

    static void scanAsync() {
        PublicWaypointImportClientState.setScanning();
        Minecraft mc = Minecraft.getInstance();
        int fallbackX = mc.player == null ? 0 : mc.player.getBlockX();
        int fallbackY = mc.player == null ? 64 : mc.player.getBlockY();
        int fallbackZ = mc.player == null ? 0 : mc.player.getBlockZ();
        String fallbackDimension = MapSyncerCommand.currentDimensionId(mc);
        Path serverDir = XaeroMapIntegrator.getCurrentServerDirectory();
        WORKER.execute(() -> PublicWaypointImportClientState.setScanResult(
                scan(serverDir, fallbackX, fallbackY, fallbackZ, fallbackDimension)));
    }

    private static Result scan(Path serverDir, int fallbackX, int fallbackY, int fallbackZ, String fallbackDimension) {
        if (serverDir == null) {
            return new Result(Status.NO_FILE, List.of(), 0, "");
        }

        Path waypointsFile = XaeroWaypointFiles.findWaypointsFile(serverDir);
        if (waypointsFile == null || !Files.exists(waypointsFile)) {
            return new Result(Status.NO_FILE, List.of(), 0,
                    waypointsFile == null ? "" : waypointsFile.toString());
        }

        List<LocalWaypoint> waypoints = new ArrayList<>();
        int skipped = 0;
        try (var reader = Files.newBufferedReader(waypointsFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("waypoint:")) {
                    continue;
                }
                LocalWaypoint waypoint = parseLine(line, fallbackX, fallbackY, fallbackZ, fallbackDimension);
                if (waypoint == null) {
                    skipped++;
                } else {
                    waypoints.add(waypoint);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to scan local Xaero waypoints from {}", waypointsFile, e);
            return new Result(Status.FAILED, List.of(), skipped, e.getMessage());
        } catch (Exception e) {
            LOGGER.warn("Unexpected failure while scanning local Xaero waypoints", e);
            return new Result(Status.FAILED, List.of(), skipped, e.getMessage());
        }

        if (waypoints.isEmpty()) {
            return new Result(Status.EMPTY, List.of(), skipped, waypointsFile.toString());
        }
        return new Result(Status.READY, waypoints, skipped, waypointsFile.toString());
    }

    private static LocalWaypoint parseLine(String line, int fallbackX, int fallbackY, int fallbackZ,
                                           String fallbackDimension) {
        if (line == null || !line.startsWith("waypoint:")) {
            return null;
        }
        String[] parts = line.split(":", -1);
        if (parts.length < 11) {
            return null;
        }

        String name = clean(parts[1], "Waypoint");
        String initial = clean(parts[2], name.substring(0, Math.min(1, name.length())));
        int x = parseIntOrDefault(parts[3], fallbackX);
        int y = parseIntOrDefault(parts[4], fallbackY == 0 ? 64 : fallbackY);
        int z = parseIntOrDefault(parts[5], fallbackZ);
        int color = parseIntOrDefault(parts[6], 0);
        boolean disabled = Boolean.parseBoolean(clean(parts[7], "false"));
        String type = clean(parts[8], "0");
        String set = clean(parts[9], "");
        String dimension = clean(parts[10], fallbackDimension);

        return new LocalWaypoint(name, initial, x, y, z, color, disabled, type, set, dimension);
    }

    static int parseIntOrDefault(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || "~".equals(trimmed)) {
            return fallback;
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String clean(String value, String fallback) {
        String cleaned = value == null || value.isBlank() ? fallback : value.trim();
        return cleaned.replace(':', '_').replace('\n', ' ').replace('\r', ' ');
    }

    record LocalWaypoint(String name, String initial, int x, int y, int z, int color,
                         boolean disabled, String type, String set, String dimension) {
        PacketHandler.PublicWaypoint toPublicWaypoint() {
            return new PacketHandler.PublicWaypoint(name, initial, x, y, z, color, disabled, type, set, dimension);
        }
    }

    record Result(Status status, List<LocalWaypoint> waypoints, int skippedCount, String detail) {
    }

    enum Status {
        READY,
        NO_FILE,
        EMPTY,
        FAILED
    }
}
