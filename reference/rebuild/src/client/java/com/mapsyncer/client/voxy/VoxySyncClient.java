package com.mapsyncer.client.voxy;

import com.mapsyncer.client.MapSyncerCommand;
import com.mapsyncer.client.MapPacketReceiver;
import com.mapsyncer.network.PacketHandler;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class VoxySyncClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(VoxySyncClient.class);

    private static volatile boolean serverEnabled;
    private static volatile String serverReason = "unknown";
    private static volatile boolean syncing;
    private static volatile String syncId = "";
    private static volatile String dimensionId = "";
    private static volatile int processedRegions;
    private static volatile int totalRegions;
    private static volatile long processedBytes;
    private static volatile long totalBytes;
    private static volatile String status = "";
    private static volatile long completedAt;
    private static volatile boolean capabilityRequested;
    private static volatile boolean deleteStagingOnFailure;

    private static Path stagingRoot;
    private static Path regionDir;
    private static VoxySyncCache cache;
    private static final Map<String, RegionAssembly> assemblies = new ConcurrentHashMap<>();
    private static final ExecutorService VOXY_IO_WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "mapsyncer-voxy-client-io");
        thread.setDaemon(true);
        return thread;
    });

    private VoxySyncClient() {
    }

    public static void requestCapability() {
        if (capabilityRequested) {
            return;
        }
        try {
            if (ClientPlayNetworking.canSend(PacketHandler.VoxyCapabilityRequestPayload.TYPE)) {
                capabilityRequested = true;
                ClientPlayNetworking.send(new PacketHandler.VoxyCapabilityRequestPayload());
            } else {
                serverEnabled = false;
                serverReason = "server_disabled";
            }
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            serverEnabled = false;
            serverReason = "no_connection";
            capabilityRequested = false;
        }
    }

    public static void handleCapability(PacketHandler.VoxyCapabilityPayload payload, ClientPlayNetworking.Context context) {
        context.client().execute(() -> {
            serverEnabled = payload.enabled();
            serverReason = payload.reason();
        });
    }

    public static void startCurrentDimensionSync(Minecraft client) {
        if (client == null || client.player == null || client.level == null || syncing) {
            return;
        }
        if (!canStart(client)) {
            requestCapability();
            return;
        }

        String dim = MapSyncerCommand.currentDimensionId(client);
        cache = VoxySyncCache.create(client);
        Map<String, PacketHandler.VoxyRegionMeta> clientMeta = cache.snapshotForDimension(dim);
        cleanupLocalState();
        syncing = true;
        syncId = "";
        dimensionId = dim;
        status = Component.translatable("mapsyncer.gui.voxy.requesting").getString();
        completedAt = 0;
        deleteStagingOnFailure = false;
        try {
            ClientPlayNetworking.send(new PacketHandler.VoxySyncRequestPayload(dim, clientMeta));
        } catch (IllegalArgumentException | IllegalStateException e) {
            LOGGER.warn("Failed to request Voxy sync", e);
            fail("no_connection");
        }
    }

    public static void handleStart(PacketHandler.VoxySyncStartPayload payload, ClientPlayNetworking.Context context) {
        Minecraft client = context.client();
        VOXY_IO_WORKER.execute(() -> handleStartOnWorker(payload, client));
    }

    private static void handleStartOnWorker(PacketHandler.VoxySyncStartPayload payload, Minecraft client) {
        cleanupLocalState();
        syncing = true;
        syncId = payload.syncId();
        dimensionId = payload.dimensionId();
        processedRegions = 0;
        totalRegions = payload.totalRegions();
        processedBytes = 0;
        totalBytes = payload.totalBytes();
        status = Component.translatable("mapsyncer.gui.voxy.downloading").getString();
        completedAt = 0;
        deleteStagingOnFailure = true;

        try {
            stagingRoot = client.gameDirectory.toPath()
                    .resolve("mapsyncer")
                    .resolve("voxy-staging")
                    .resolve(safeName(dimensionId))
                    .resolve(syncId);
            regionDir = stagingRoot.resolve("region");
            deleteDirectory(stagingRoot);
            Files.createDirectories(regionDir);
        } catch (IOException e) {
            LOGGER.error("Failed to prepare Voxy staging directory", e);
            fail("client_io_failed");
        }
    }

    public static void handlePart(PacketHandler.VoxyRegionPartPayload payload, ClientPlayNetworking.Context context) {
        VOXY_IO_WORKER.execute(() -> handlePartOnWorker(payload));
    }

    private static void handlePartOnWorker(PacketHandler.VoxyRegionPartPayload payload) {
        if (!payload.syncId().equals(syncId) || !syncing || regionDir == null) {
            return;
        }
        if (!payload.dimensionId().equals(dimensionId)) {
            fail("dimension_changed");
            return;
        }

        try {
            String fileName = regionFileName(payload.regionX(), payload.regionZ());
            RegionAssembly assembly = assemblies.computeIfAbsent(fileName,
                    ignored -> new RegionAssembly(payload.totalParts(), payload.totalBytes()));
            if (!assembly.matches(payload.totalParts(), payload.totalBytes())) {
                throw new IOException("Voxy region metadata changed mid-transfer for " + fileName);
            }
            assembly.writePart(regionDir.resolve(fileName + ".part"), payload.partIndex(), payload.byteOffset(), payload.data());

            if (assembly.isComplete()) {
                Path partPath = regionDir.resolve(fileName + ".part");
                Path finalPath = regionDir.resolve(fileName);
                if (Files.size(partPath) != payload.totalBytes()) {
                    throw new IOException("Voxy region size mismatch for " + fileName);
                }
                moveCompletedRegion(partPath, finalPath);
                if (cache != null) {
                    cache.update(payload.dimensionId(), fileName, payload.timestampSeconds(), payload.totalBytes());
                }
                assemblies.remove(fileName);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to write Voxy region part", e);
            fail("client_io_failed");
        }
    }

    public static void handleProgress(PacketHandler.VoxySyncProgressPayload payload, ClientPlayNetworking.Context context) {
        context.client().execute(() -> {
            if (!payload.syncId().equals(syncId)) {
                return;
            }
            processedRegions = payload.processedRegions();
            totalRegions = payload.totalRegions();
            processedBytes = payload.processedBytes();
            totalBytes = payload.totalBytes();
            status = payload.status();
        });
    }

    public static void handleComplete(PacketHandler.VoxySyncCompletePayload payload, ClientPlayNetworking.Context context) {
        Minecraft client = context.client();
        VOXY_IO_WORKER.execute(() -> {
            if (!payload.syncId().isBlank() && !syncId.isBlank() && !payload.syncId().equals(syncId)) {
                return;
            }
            if (!payload.success()) {
                status = payload.message();
                syncing = false;
                completedAt = System.currentTimeMillis();
                cleanupFailedSyncFiles();
                return;
            }

            if (cache != null) {
                cache.save();
            }
            if (payload.transferredRegions() == 0) {
                status = "completed";
                syncing = false;
                completedAt = System.currentTimeMillis();
                cleanupPartialFiles();
                return;
            }
            if (!assemblies.isEmpty()) {
                status = "client_io_failed";
                syncing = false;
                completedAt = System.currentTimeMillis();
                cleanupFailedSyncFiles();
                return;
            }
            Path importRegionDir = regionDir;
            client.execute(() -> finishImportOnClient(client, importRegionDir));
        });
    }

    private static void finishImportOnClient(Minecraft client, Path importRegionDir) {
        status = Component.translatable("mapsyncer.gui.voxy.importing").getString();
        try {
            IVoxyBridge bridge = VoxyBridgeLoader.getBridge();
            if (bridge == null) {
                status = "not_enabled";
                cleanupFailedSyncFiles();
            } else if (importRegionDir == null || !Files.isDirectory(importRegionDir)) {
                status = "completed";
            } else if (!bridge.startImport(client, importRegionDir)) {
                status = "import_busy";
                cleanupFailedSyncFiles();
            } else {
                status = Component.translatable("mapsyncer.gui.voxy.import_started").getString();
                deleteStagingOnFailure = false;
            }
        } catch (Exception e) {
            LOGGER.error("Failed to start Voxy import", e);
            status = "import_failed";
            cleanupFailedSyncFiles();
        } finally {
            syncing = false;
            completedAt = System.currentTimeMillis();
        }
    }

    public static void reset() {
        boolean shouldDeleteStaging = syncing && deleteStagingOnFailure;
        serverEnabled = false;
        serverReason = "unknown";
        capabilityRequested = false;
        status = "";
        completedAt = 0;
        syncing = false;
        syncId = "";
        deleteStagingOnFailure = shouldDeleteStaging;
        VOXY_IO_WORKER.execute(() -> {
            cleanupFailedSyncFiles();
            cleanupLocalState();
        });
    }

    public static boolean isSyncing() {
        return syncing;
    }

    public static int getPercent() {
        return totalRegions > 0 ? Math.min(100, processedRegions * 100 / totalRegions) : 0;
    }

    public static String getDisplayStatus() {
        if (status == null || status.isBlank()) {
            return "";
        }
        if (isStatusReason(status)) {
            return Component.translatable("mapsyncer.gui.voxy.reason." + status).getString();
        }
        return status;
    }

    public static int getProcessedRegions() {
        return processedRegions;
    }

    public static int getTotalRegions() {
        return totalRegions;
    }

    public static long getCompletedAt() {
        return completedAt;
    }

    public static boolean canStart(Minecraft client) {
        return getUnavailableReason(client).isBlank();
    }

    public static String getUnavailableReason(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            return "no_connection";
        }
        if (!MapPacketReceiver.isServerInstalled()) {
            return "server_disabled";
        }
        if (MapPacketReceiver.isSyncInProgress()) {
            return "syncing";
        }
        if (syncing) {
            return "syncing";
        }
        if (!VoxyBridgeLoader.isVoxyInstalled()) {
            return "not_installed";
        }
        if (!VoxyBridgeLoader.isVoxyReady(client)) {
            return "not_enabled";
        }
        if (!serverEnabled) {
            requestCapability();
            return serverReason == null || serverReason.isBlank() ? "server_disabled" : serverReason;
        }
        return "";
    }

    private static void fail(String reason) {
        status = reason;
        syncing = false;
        completedAt = System.currentTimeMillis();
        VOXY_IO_WORKER.execute(VoxySyncClient::cleanupFailedSyncFiles);
    }

    private static void cleanupLocalState() {
        syncing = false;
        syncId = "";
        dimensionId = "";
        processedRegions = 0;
        totalRegions = 0;
        processedBytes = 0;
        totalBytes = 0;
        status = "";
        deleteStagingOnFailure = false;
        assemblies.clear();
    }

    private static void moveCompletedRegion(Path partPath, Path finalPath) throws IOException {
        try {
            Files.move(partPath, finalPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(partPath, finalPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean isStatusReason(String value) {
        return switch (value) {
            case "server_disabled", "busy", "dimension_changed", "region_dir_missing", "interrupted",
                    "failed", "completed", "client_io_failed", "import_busy", "import_failed",
                    "not_installed", "not_enabled", "no_connection", "unknown", "sending" -> true;
            default -> false;
        };
    }

    private static void cleanupPartialFiles() {
        assemblies.clear();
        if (regionDir == null || !Files.isDirectory(regionDir)) {
            return;
        }
        try (var stream = Files.newDirectoryStream(regionDir, "*.part")) {
            for (Path path : stream) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to clean Voxy partial files", e);
        }
    }

    private static void cleanupFailedSyncFiles() {
        cleanupPartialFiles();
        if (!deleteStagingOnFailure || stagingRoot == null) {
            return;
        }
        try {
            deleteDirectory(stagingRoot);
        } catch (IOException e) {
            LOGGER.warn("Failed to clean failed Voxy staging directory", e);
        }
    }

    private static String safeName(String value) {
        return value.replace(':', '_').replace('/', '_').replace('\\', '_');
    }

    private static String regionFileName(int regionX, int regionZ) {
        return "r." + regionX + "." + regionZ + ".mca";
    }

    private static void deleteDirectory(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            for (Path entry : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }

    private static class RegionAssembly {
        private final int totalParts;
        private final long totalBytes;
        private final Set<Integer> received = ConcurrentHashMap.newKeySet();

        RegionAssembly(int totalParts, long totalBytes) {
            this.totalParts = totalParts;
            this.totalBytes = totalBytes;
        }

        boolean matches(int expectedTotalParts, long expectedTotalBytes) {
            return totalParts == expectedTotalParts && totalBytes == expectedTotalBytes;
        }

        void writePart(Path partPath, int partIndex, long byteOffset, byte[] data) throws IOException {
            if (partIndex < 0 || partIndex >= totalParts || byteOffset < 0 || byteOffset + data.length > totalBytes) {
                throw new IOException("Invalid Voxy region part");
            }
            if (data.length == 0 && totalBytes > 0) {
                throw new IOException("Invalid empty Voxy region part");
            }
            if (!received.add(partIndex)) {
                return;
            }
            Files.createDirectories(partPath.getParent());
            try (FileChannel channel = FileChannel.open(partPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                channel.write(ByteBuffer.wrap(data), byteOffset);
            }
        }

        boolean isComplete() {
            return received.size() == totalParts;
        }
    }
}
