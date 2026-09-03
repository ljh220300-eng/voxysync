package com.nexus.voxysync.client;

import com.nexus.voxysync.VoxySyncConfig;
import com.nexus.voxysync.client.voxy.IVoxyBridge;
import com.nexus.voxysync.client.voxy.VoxyBridgeLoader;
import com.nexus.voxysync.network.VoxyPackets;
import com.nexus.voxysync.network.VoxyPackets.CapabilityPayload;
import com.nexus.voxysync.network.VoxyPackets.RegionPartPayload;
import com.nexus.voxysync.network.VoxyPackets.SyncCompletePayload;
import com.nexus.voxysync.network.VoxyPackets.SyncProgressPayload;
import com.nexus.voxysync.network.VoxyPackets.SyncRequestPayload;
import com.nexus.voxysync.network.VoxyPackets.SyncStartPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Voxy 同步 —— 客户端实现（改编自 MapSyncer-rebuild 的 VoxySyncClient，GPL-3.0）。
 *
 * <p>流程：进服探测服务器能力 → 请求当前维度同步（带本地缓存做增量）→
 * 收齐区域分片写入 {@code .minecraft/voxysync/staging/&lt;维度&gt;/&lt;syncId&gt;/region/}
 * → 反射调用 Voxy 导入管线 → 完成/失败提示（actionbar 进度 + 聊天消息）。</p>
 *
 * <p>无 Voxy 时优雅降级：只提示一次"未安装 Voxy"。</p>
 */
public final class VoxySyncClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(VoxySyncClient.class);

    private static final int PROGRESS_ACTIONBAR_INTERVAL_TICKS = 10;

    private static volatile boolean serverEnabled;
    private static volatile boolean capabilityRequested;
    private static volatile boolean syncing;
    private static volatile String syncId = "";
    private static volatile String dimensionId = "";
    private static volatile int processedRegions;
    private static volatile int totalRegions;
    private static volatile long processedBytes;
    private static volatile long totalBytes;
    private static volatile String status = "";

    private static Path stagingRoot;
    private static Path regionDir;
    private static VoxySyncCache cache;
    private static final Map<String, RegionAssembly> assemblies = new ConcurrentHashMap<>();
    private static final ExecutorService VOXY_IO_WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "voxysync-client-io");
        thread.setDaemon(true);
        return thread;
    });

    /** 用于自动同步的一次性标记 */
    private static volatile String lastAutoDimension = "";
    /** server_busy 自动重试倒计时（tick；1200 = 60 秒），仅重试一次 */
    private static volatile int retryTicks;
    private static volatile int retryAttempt;

    private VoxySyncClient() {
    }

    // ---------- 能力探测 ----------

    public static void requestCapability(Minecraft client) {
        if (client.getConnection() == null) {
            return;
        }
        if (capabilityRequested) {
            return;
        }
        if (!ClientPlayNetworking.canSend(VoxyPackets.CAPABILITY_REQUEST)) {
            // 服务器没有装本 mod（或不在线）
            serverEnabled = false;
            return;
        }
        capabilityRequested = true;
        FriendlyByteBuf buf = PacketByteBufs.create();
        ClientPlayNetworking.send(VoxyPackets.CAPABILITY_REQUEST, buf);
    }

    public static void handleCapability(CapabilityPayload payload, Minecraft client) {
        client.execute(() -> {
            serverEnabled = payload.enabled();
            if (serverEnabled && payload.reason() != null && payload.reason().startsWith("enabled:")) {
                LOGGER.info("服务器 Voxy 同步已启用，模式: {}", payload.reason().substring("enabled:".length()));
            } else {
                LOGGER.info("服务器未启用 Voxy 同步（{}）", payload.reason());
            }
            // 收到能力后，若启用且当前维度还没同步过 → 自动发起
            maybeAutoStart(client);
        });
    }

    private static void maybeAutoStart(Minecraft client) {
        if (!serverEnabled || client.level == null || client.player == null) {
            return;
        }
        if (!VoxySyncConfig.INSTANCE.autoStartOnJoin) {
            return;
        }
        String dim = currentDimensionId(client);
        if (dim == null || dim.equals(lastAutoDimension)) {
            return;
        }
        startSync(client, dim, false);
    }

    public static void handleRequestSync(VoxyPackets.RequestSyncPayload payload, Minecraft client) {
        client.execute(() -> {
            if (syncing) {
                client.player.displayClientMessage(
                        Component.literal("§7[VoxySync] 正在同步中，稍后再试"), false);
                return;
            }
            LOGGER.info("服务器请求重新同步: {}", payload.note());
            startSync(client, currentDimensionId(client), false);
        });
    }

    // ---------- 发起同步 ----------

    public static void startSync(Minecraft client, String dim, boolean manual) {
        if (client == null || client.level == null || client.player == null || syncing) {
            return;
        }
        if (!ClientPlayNetworking.canSend(VoxyPackets.SYNC_REQUEST)) {
            notifyPlayer(client, "§c[VoxySync] 服务器未安装 VoxySync 或未连接");
            return;
        }
        if (!VoxyBridgeLoader.isVoxyInstalled()) {
            notifyPlayer(client, "§7[VoxySync] 未检测到 Voxy 模组，无法同步世界数据"
                    + "（不影响游戏；安装 Voxy 后自动生效）");
            return;
        }
        if (!VoxyBridgeLoader.isVoxyReady(client)) {
            notifyPlayer(client, "§c[VoxySync] Voxy 尚未就绪（请确认已安装并进入世界后重试）");
            return;
        }
        if (dim == null) {
            return;
        }
        cache = VoxySyncCache.create(client);
        Map<String, VoxyPackets.RegionMeta> clientMeta = cache.snapshotForDimension(dim);
        cleanupLocalState();
        syncing = true;
        syncId = "";
        dimensionId = dim;
        status = "requesting";
        LOGGER.info("请求 Voxy 同步，维度 {}，本地缓存 {} 个区域", dim, clientMeta.size());
        try {
            // C2S 自定义载荷上限 32767 字节 → 分块发送元数据
            String requestId = UUID.randomUUID().toString();
            java.util.List<Map<String, VoxyPackets.RegionMeta>> chunks = chunkMetaForRequest(clientMeta);
            int totalChunks = chunks.size();
            for (int i = 0; i < totalChunks; i++) {
                sendSyncRequestChunk(dim, requestId, i, totalChunks, chunks.get(i));
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            LOGGER.warn("发送同步请求失败", e);
            fail("no_connection");
        }
    }

    /** 将客户端元数据按 {@link VoxyPackets#MAX_ENTRIES_PER_CHUNK} 条/块切分（可单测） */
    static java.util.List<Map<String, VoxyPackets.RegionMeta>> chunkMetaForRequest(
            Map<String, VoxyPackets.RegionMeta> clientMeta) {
        int total = Math.max(1, (clientMeta.size() + VoxyPackets.MAX_ENTRIES_PER_CHUNK - 1)
                / VoxyPackets.MAX_ENTRIES_PER_CHUNK);
        java.util.List<Map<String, VoxyPackets.RegionMeta>> result = new java.util.ArrayList<>(total);
        Map<String, VoxyPackets.RegionMeta> chunk = new HashMap<>();
        for (Map.Entry<String, VoxyPackets.RegionMeta> entry : clientMeta.entrySet()) {
            chunk.put(entry.getKey(), entry.getValue());
            if (chunk.size() >= VoxyPackets.MAX_ENTRIES_PER_CHUNK) {
                result.add(chunk);
                chunk = new HashMap<>();
            }
        }
        if (!chunk.isEmpty() || result.isEmpty()) {
            result.add(chunk);
        }
        return result;
    }

    private static void sendSyncRequestChunk(String dim, String requestId, int chunkIndex,
                                             int totalChunks, Map<String, VoxyPackets.RegionMeta> chunk) {
        SyncRequestPayload payload = new SyncRequestPayload(dim, requestId, chunkIndex, totalChunks, chunk);
        FriendlyByteBuf buf = PacketByteBufs.create();
        payload.encode(buf);
        ClientPlayNetworking.send(VoxyPackets.SYNC_REQUEST, buf);
    }

    // ---------- 接收端 ----------

    public static void handleStart(SyncStartPayload payload, Minecraft client) {
        VOXY_IO_WORKER.execute(() -> handleStartOnWorker(payload, client));
    }

    private static void handleStartOnWorker(SyncStartPayload payload, Minecraft client) {
        cleanupLocalState();
        syncing = true;
        syncId = payload.syncId();
        dimensionId = payload.dimensionId();
        processedRegions = 0;
        totalRegions = payload.totalRegions();
        processedBytes = 0;
        totalBytes = payload.totalBytes();
        status = "downloading";
        try {
            stagingRoot = client.gameDirectory.toPath()
                    .resolve("voxysync")
                    .resolve("staging")
                    .resolve(safeName(dimensionId))
                    .resolve(syncId);
            regionDir = stagingRoot.resolve("region");
            deleteDirectory(stagingRoot);
            Files.createDirectories(regionDir);
        } catch (IOException e) {
            LOGGER.error("准备 Voxy staging 目录失败", e);
            fail("client_io_failed");
        }
    }

    public static void handlePart(RegionPartPayload payload, Minecraft client) {
        VOXY_IO_WORKER.execute(() -> handlePartOnWorker(payload));
    }

    private static void handlePartOnWorker(RegionPartPayload payload) {
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
                throw new IOException("传输中区域元数据变化: " + fileName);
            }
            assembly.writePart(regionDir.resolve(fileName + ".part"),
                    payload.partIndex(), payload.byteOffset(), payload.data());

            if (assembly.isComplete()) {
                Path partPath = regionDir.resolve(fileName + ".part");
                Path finalPath = regionDir.resolve(fileName);
                if (Files.size(partPath) != payload.totalBytes()) {
                    throw new IOException("区域大小不匹配: " + fileName);
                }
                moveCompletedRegion(partPath, finalPath);
                if (cache != null) {
                    cache.update(payload.dimensionId(), fileName, payload.timestampSeconds(), payload.totalBytes());
                }
                assemblies.remove(fileName);
            }
        } catch (Exception e) {
            LOGGER.error("写入 Voxy 区域分片失败", e);
            fail("client_io_failed");
        }
    }

    public static void handleProgress(SyncProgressPayload payload, Minecraft client) {
        client.execute(() -> {
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

    public static void handleComplete(SyncCompletePayload payload, Minecraft client) {
        VOXY_IO_WORKER.execute(() -> {
            if (!payload.syncId().isBlank() && !syncId.isBlank() && !payload.syncId().equals(syncId)) {
                return;
            }
            if (!payload.success()) {
                status = payload.message();
                String reason = reasonText(payload.message());
                notifyPlayer(client, "§c[VoxySync] 同步失败：" + reason);
                if ("server_busy".equals(payload.message()) && retryAttempt == 0) {
                    retryAttempt = 1;
                    retryTicks = 20 * 60;
                    notifyPlayer(client, "§e[VoxySync] 60 秒后自动重试…");
                }
                syncing = false;
                cleanupFailedSyncFiles();
                return;
            }

            if (cache != null) {
                cache.save();
            }
            if (payload.transferredRegions() == 0) {
                status = "completed";
                notifyPlayer(client, "§a[VoxySync] 世界数据已是最新，无需同步");
                syncing = false;
                cleanupPartialFiles();
                return;
            }
            if (!assemblies.isEmpty()) {
                status = "client_io_failed";
                notifyPlayer(client, "§c[VoxySync] 有区域未收齐，请稍后重试");
                syncing = false;
                cleanupFailedSyncFiles();
                return;
            }
            Path importRegionDir = regionDir;
            client.execute(() -> finishImportOnClient(client, importRegionDir));
        });
    }

    private static void finishImportOnClient(Minecraft client, Path importRegionDir) {
        status = "importing";
        try {
            IVoxyBridge bridge = VoxyBridgeLoader.getBridge();
            if (bridge == null) {
                notifyPlayer(client, "§c[VoxySync] 未找到 Voxy 桥接，无法导入");
                cleanupFailedSyncFiles();
            } else if (importRegionDir == null || !Files.isDirectory(importRegionDir)) {
                notifyPlayer(client, "§a[VoxySync] 数据已下载");
                status = "completed";
            } else if (!bridge.startImport(client, importRegionDir)) {
                notifyPlayer(client, "§e[VoxySync] 数据已下载，但 Voxy 导入器忙碌中，稍后会自动使用");
                status = "import_busy";
                cleanupFailedSyncFiles();
            } else {
                notifyPlayer(client, "§a[VoxySync] 世界数据同步完成，Voxy 开始导入超远渲染 LoD！");
                status = "import_started";
            }
        } catch (Exception e) {
            LOGGER.error("启动 Voxy 导入失败", e);
            notifyPlayer(client, "§c[VoxySync] 启动 Voxy 导入失败：" + e.getClass().getSimpleName());
            status = "import_failed";
            cleanupFailedSyncFiles();
        } finally {
            syncing = false;
        }
    }

    // ---------- 自动同步 / 维度切换（由客户端 mod 每 tick / 进服事件驱动） ----------

    /** 每次进服或维度变化后调用（在客户端线程） */
    public static void onWorldAvailable(Minecraft client) {
        if (client.level == null || client.player == null) {
            return;
        }
        String dim = currentDimensionId(client);
        if (dim == null) {
            return;
        }
        if (!dim.equals(lastAutoDimension)) {
            lastAutoDimension = dim;
            capabilityRequested = false;
            serverEnabled = false;
            requestCapability(client);
        }
    }

    /** 每 tick 调用：显示 actionbar 进度 + server_busy 自动重试 */
    public static void onClientTick(Minecraft client) {
        if (retryTicks > 0 && !syncing && client.level != null && client.player != null) {
            retryTicks--;
            if (retryTicks == 0 && serverEnabled) {
                startSync(client, currentDimensionId(client), false);
            }
        }
        if (!syncing || client.player == null) {
            return;
        }
        if (client.level == null || client.level.getGameTime() % PROGRESS_ACTIONBAR_INTERVAL_TICKS != 0) {
            return;
        }
        int percent = getPercent();
        String text = "§6Voxy 同步: §e" + percent + "%§r (" + processedRegions + "/" + totalRegions + " 区域)";
        client.player.displayClientMessage(Component.literal(text), true);
    }

    public static String currentDimensionId(Minecraft client) {
        if (client == null || client.level == null) {
            return null;
        }
        return client.level.dimension().location().toString();
    }

    // ---------- 状态查询 ----------

    public static boolean isSyncing() {
        return syncing;
    }

    public static int getPercent() {
        return totalRegions > 0 ? Math.min(100, processedRegions * 100 / totalRegions) : 0;
    }

    public static String getStatus() {
        return status;
    }

    /** 断线/退出时重置自动同步状态 */
    public static void onDisconnect() {
        lastAutoDimension = "";
        capabilityRequested = false;
        serverEnabled = false;
        syncing = false;
        cleanupLocalState();
        VOXY_IO_WORKER.execute(VoxySyncClient::cleanupFailedSyncFiles);
    }

    // ---------- 内部 ----------

    private static void notifyPlayer(Minecraft client, String message) {
        if (client.player == null) {
            return;
        }
        client.player.displayClientMessage(Component.literal(message), false);
    }

    private static String reasonText(String code) {
        return switch (code == null ? "" : code) {
            case "server_disabled" -> "服务器未启用 Voxy 同步";
            case "busy" -> "你已有同步进行中";
            case "server_busy" -> "服务器当前同步人数已满，请稍后重试";
            case "dimension_changed" -> "维度已变化";
            case "region_dir_missing" -> "服务器找不到区域文件目录";
            case "interrupted" -> "同步被中断（可能已离线或切换维度）";
            case "failed" -> "服务器同步出错";
            case "no_world" -> "未处于可同步的世界";
            case "client_io_failed" -> "本地写入失败";
            case "import_busy" -> "Voxy 导入器忙碌";
            case "import_failed" -> "Voxy 导入失败";
            default -> code;
        };
    }

    private static void fail(String reason) {
        status = reason;
        syncing = false;
        VOXY_IO_WORKER.execute(VoxySyncClient::cleanupFailedSyncFiles);
    }

    private static void cleanupLocalState() {
        syncing = false;
        retryTicks = 0;
        retryAttempt = 0;
        syncId = "";
        dimensionId = "";
        processedRegions = 0;
        totalRegions = 0;
        processedBytes = 0;
        totalBytes = 0;
        status = "";
        assemblies.clear();
    }

    private static void moveCompletedRegion(Path partPath, Path finalPath) throws IOException {
        try {
            Files.move(partPath, finalPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(partPath, finalPath, StandardCopyOption.REPLACE_EXISTING);
        }
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
            LOGGER.warn("清理 Voxy 分片文件失败", e);
        }
    }

    private static void cleanupFailedSyncFiles() {
        cleanupPartialFiles();
        if (stagingRoot == null) {
            return;
        }
        try {
            deleteDirectory(stagingRoot);
        } catch (IOException e) {
            LOGGER.warn("清理失败的 Voxy staging 目录失败", e);
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

    /** 区域分片拼装（纯 java.nio，可单测） */
    static class RegionAssembly {
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
                throw new IOException("无效的 Voxy 区域分片");
            }
            if (data.length == 0 && totalBytes > 0) {
                throw new IOException("空的 Voxy 区域分片");
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
