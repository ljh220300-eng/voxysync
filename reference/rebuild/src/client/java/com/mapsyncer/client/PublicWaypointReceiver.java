package com.mapsyncer.client;

import com.mapsyncer.network.PacketHandler;
import com.mapsyncer.util.ChatUtils;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PublicWaypointReceiver {
    private static final Logger LOGGER = LoggerFactory.getLogger(PublicWaypointReceiver.class);
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "mapsyncer-public-waypoints");
        thread.setDaemon(true);
        return thread;
    });
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean MANUAL_REQUEST_PENDING = new AtomicBoolean();

    private PublicWaypointReceiver() {
    }

    public static void handle(PacketHandler.PublicWaypointsPayload payload, ClientPlayNetworking.Context context) {
        Path serverDir = XaeroMapIntegrator.getCurrentServerDirectory();
        handle(payload, serverDir, MANUAL_REQUEST_PENDING.getAndSet(false));
    }

    public static void requestManualSync() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        if (!ClientPlayNetworking.canSend(PacketHandler.PublicWaypointsRequestPayload.TYPE)) {
            PublicWaypointClientState.set(PublicWaypointClientState.Status.FAILED, 0);
            mc.player.sendSystemMessage(ChatUtils.error("mapsyncer.waypoints.unsupported"));
            return;
        }
        PublicWaypointClientState.set(PublicWaypointClientState.Status.SYNCING, 0);
        MANUAL_REQUEST_PENDING.set(true);
        ClientPlayNetworking.send(new PacketHandler.PublicWaypointsRequestPayload());
    }

    private static void handle(PacketHandler.PublicWaypointsPayload payload, Path serverDir, boolean manual) {
        if (serverDir == null) {
            finish(PublicWaypointClientState.Status.MISSING_DIR, payload.waypoints().size(), manual,
                    "mapsyncer.waypoints.skipped");
            return;
        }
        String key = serverDir.toAbsolutePath() + "|" + payload.hash();
        if (!IN_FLIGHT.add(key)) {
            return;
        }
        PublicWaypointClientState.set(PublicWaypointClientState.Status.SYNCING, payload.waypoints().size());
        WORKER.execute(() -> {
            try {
                applyWaypoints(payload, serverDir, manual);
            } finally {
                IN_FLIGHT.remove(key);
            }
        });
    }

    private static void applyWaypoints(PacketHandler.PublicWaypointsPayload payload, Path serverDir, boolean manual) {
        if (serverDir == null) {
            finish(PublicWaypointClientState.Status.MISSING_DIR, payload.waypoints().size(), manual,
                    "mapsyncer.waypoints.skipped");
            return;
        }

        String cachedHash = ClientPublicWaypointCache.loadHash(serverDir);
        if (payload.hash().equals(cachedHash)) {
            finish(PublicWaypointClientState.Status.UP_TO_DATE, payload.waypoints().size(), manual,
                    "mapsyncer.waypoints.up_to_date");
            return;
        }

        Path waypointsFile = XaeroWaypointFiles.findWaypointsFile(serverDir);
        if (waypointsFile == null) {
            finish(PublicWaypointClientState.Status.MISSING_DIR, payload.waypoints().size(), true,
                    "mapsyncer.waypoints.skipped");
            return;
        }

        try {
            Files.createDirectories(waypointsFile.getParent());
            Path tmp = waypointsFile.resolveSibling(waypointsFile.getFileName().toString() + ".mapsyncer.tmp");
            mergeToTempFile(payload, waypointsFile, tmp);
            moveWithRetry(tmp, waypointsFile);
            ClientPublicWaypointCache.save(serverDir, payload.hash(), "synced");
            LOGGER.info("Applied {} public waypoints to {}", payload.waypoints().size(), waypointsFile);
            finish(PublicWaypointClientState.Status.SYNCED, payload.waypoints().size(), manual,
                    "mapsyncer.waypoints.synced");
        } catch (FileSystemException e) {
            LOGGER.info("Public waypoint file is busy: {}", waypointsFile, e);
            finish(PublicWaypointClientState.Status.FILE_BUSY, payload.waypoints().size(), true,
                    "mapsyncer.waypoints.file_busy");
        } catch (Exception e) {
            LOGGER.error("Failed to apply public waypoints", e);
            finish(PublicWaypointClientState.Status.FAILED, payload.waypoints().size(), true,
                    "mapsyncer.waypoints.failed");
        }
    }

    private static void mergeToTempFile(PacketHandler.PublicWaypointsPayload payload, Path waypointsFile, Path tmp)
            throws IOException {
        Charset charset = StandardCharsets.UTF_8;
        try (var writer = Files.newBufferedWriter(tmp, charset)) {
            if (Files.exists(waypointsFile)) {
                try (var reader = Files.newBufferedReader(waypointsFile, charset)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!payload.replaceGroup() || !isManagedGroupLine(line, payload.groupName())) {
                            writer.write(line);
                            writer.newLine();
                        }
                    }
                }
            }
            for (PacketHandler.PublicWaypoint waypoint : payload.waypoints()) {
                writer.write(toXaeroLine(waypoint, payload.groupName()));
                writer.newLine();
            }
        }
    }

    private static void moveWithRetry(Path tmp, Path target) throws IOException, InterruptedException {
        try {
            moveAtomically(tmp, target);
        } catch (FileSystemException e) {
            Thread.sleep(50L);
            moveAtomically(tmp, target);
        }
    }

    private static void moveAtomically(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveFailed) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean isManagedGroupLine(String line, String groupName) {
        String[] parts = line.split(":", -1);
        return parts.length >= 10 && groupName.equals(parts[parts.length - 2]);
    }

    private static String toXaeroLine(PacketHandler.PublicWaypoint waypoint, String fallbackGroup) {
        String set = sanitize(waypoint.set().isBlank() ? fallbackGroup : waypoint.set());
        return String.join(":",
                "waypoint",
                sanitize(waypoint.name()),
                sanitize(waypoint.initial()),
                String.valueOf(waypoint.x()),
                String.valueOf(waypoint.y()),
                String.valueOf(waypoint.z()),
                String.valueOf(waypoint.color()),
                String.valueOf(waypoint.disabled()),
                sanitize(waypoint.type()),
                set,
                sanitize(waypoint.dimension())
        );
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "_";
        }
        return value.replace(':', '_').replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static void finish(PublicWaypointClientState.Status status, int count, boolean notify, String messageKey) {
        PublicWaypointClientState.set(status, count);
        if (notify) {
            notifyClient(messageKey);
        }
    }

    private static void notifyClient(String key) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.sendSystemMessage(ChatUtils.message(key));
            }
        });
    }
}
