package com.nexus.voxysync.server;

import com.nexus.voxysync.VoxySyncConfig;
import com.nexus.voxysync.network.VoxyPackets;
import com.nexus.voxysync.network.VoxyPackets.CapabilityPayload;
import com.nexus.voxysync.network.VoxyPackets.RegionPartPayload;
import com.nexus.voxysync.network.VoxyPackets.SyncCompletePayload;
import com.nexus.voxysync.network.VoxyPackets.SyncProgressPayload;
import com.nexus.voxysync.network.VoxyPackets.SyncRequestPayload;
import com.nexus.voxysync.network.VoxyPackets.SyncStartPayload;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Voxy 区域同步 —— 服务端实现（改编自 MapSyncer-rebuild 的 VoxySyncHandler，GPL-3.0）。
 *
 * <p>行为要点：</p>
 * <ul>
 *   <li>仅同步玩家<b>当前所在维度</b>（不匹配直接拒绝，客户端也不可能导入别的维度）。</li>
 *   <li>模式 radius：以玩家所在区域为中心，圈选 Chebyshev 距离不超过
 *       {@code ceil(radiusBlocks/512)} 个区域的方形范围，并按距离<b>由近及远</b>发送。</li>
 *   <li>模式 all：发送该维度全部非空区域文件（安全警告见 {@link #logSecurityWarningIfEnabled()}，
 *       需在 config/voxysync.json 显式设置 syncMode=all）。</li>
 *   <li>增量：客户端上报的 (时间戳, 大小) 与服务端一致的文件跳过，不重复发送。</li>
 *   <li>限速：每个玩家独立按 syncSpeedLimitKBps 节流；每个分片发送前检查玩家仍在本维度。</li>
 *   <li>并发：单玩家同时一个同步；全局最多 {@link #MAX_CONCURRENT_SYNCS} 个同步线程。</li>
 * </ul>
 */
public final class VoxySyncHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(VoxySyncHandler.class);
    private static final Pattern REGION_FILE_PATTERN = Pattern.compile("^r\\.(-?[0-9]+)\\.(-?[0-9]+)\\.mca$");

    /** 单个分片数据上限（低于 1.20.1 S2C 自定义载荷上限 1048576） */
    private static final int MAX_PACKET_SIZE_LIMIT = 1_000_000;
    /** 全局同时进行的同步线程数上限 */
    private static final int MAX_CONCURRENT_SYNCS = 2;
    /** 每个区域 512×512 方块 */
    private static final int BLOCKS_PER_REGION = 512;

    private static final Set<UUID> syncingPlayers = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Thread> syncThreads = new ConcurrentHashMap<>();
    private static final Map<UUID, String> playerSyncDimensions = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> speedLimitBytesSent = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> speedLimitCycleStart = new ConcurrentHashMap<>();
    /** /voxysync sync [mode] 的本次覆盖模式（request_sync 提示客户端发起后消费） */
    private static final Map<UUID, String> pendingMode = new ConcurrentHashMap<>();
    /** 客户端分块上报的元数据聚合器（请求 id 不同则重置） */
    private static final Map<UUID, MetaAggregator> pendingMeta = new ConcurrentHashMap<>();
    private static final AtomicInteger activeSyncCount = new AtomicInteger();

    private VoxySyncHandler() {
    }

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(VoxyPackets.CAPABILITY_REQUEST,
                (server, player, handler, buf, responseSender) -> sendCapability(player));
        ServerPlayNetworking.registerGlobalReceiver(VoxyPackets.SYNC_REQUEST,
                (server, player, handler, buf, responseSender) ->
                        handleSyncRequest(VoxyPackets.SyncRequestPayload.decode(buf), player));
    }

    public static void logSecurityWarningIfEnabled() {
        if (!VoxySyncConfig.INSTANCE.enableVoxySync) {
            return;
        }
        LOGGER.warn("============================================================");
        LOGGER.warn("[VoxySync] Voxy sync is ENABLED. This sends MCA region files to clients.");
        LOGGER.warn("[VoxySync] MCA data can expose chest contents, block entities, entities, ores and hidden structures.");
        LOGGER.warn("[VoxySync] Current mode: " + VoxySyncConfig.INSTANCE.syncMode
                + ("all".equals(VoxySyncConfig.INSTANCE.syncMode) ? " (FULL-MAP mode: sending all regions of this dimension, HIGHEST RISK!)" : ""));
        LOGGER.warn("[VoxySync] Only enable this on trusted technical/build servers.");
        LOGGER.warn("============================================================");
    }

    // ---------- 接收端 ----------

    private static void sendCapability(ServerPlayer player) {
        MinecraftServer server = player.server;
        if (server == null) {
            return;
        }
        server.execute(() -> {
            if (player.connection == null || !ServerPlayNetworking.canSend(player, VoxyPackets.CAPABILITY)) {
                return;
            }
            boolean enabled = VoxySyncConfig.INSTANCE.enableVoxySync;
            String reason = enabled ? "enabled:" + VoxySyncConfig.INSTANCE.syncMode : "server_disabled";
            CapabilityPayload payload = new CapabilityPayload(enabled, reason);
            FriendlyByteBuf buf = PacketByteBufs.create();
            payload.encode(buf);
            ServerPlayNetworking.send(player, VoxyPackets.CAPABILITY, buf);
        });
    }

    private static void handleSyncRequest(SyncRequestPayload payload, ServerPlayer player) {
        MinecraftServer server = player.server;
        if (server == null) {
            return;
        }
        server.execute(() -> {
            if (!VoxySyncConfig.INSTANCE.enableVoxySync) {
                sendComplete(player, "", false, "server_disabled", 0, 0);
                return;
            }
            // 聚合分块元数据（1.20.1 C2S 自定义载荷上限 32767 字节，故客户端分块发送）
            UUID playerId = player.getUUID();
            int totalChunks = Math.min(Math.max(payload.totalChunks(), 1), VoxyPackets.MAX_CHUNKS);
            MetaAggregator aggregator = pendingMeta.compute(playerId, (id, prev) -> {
                if (prev == null || !prev.requestId().equals(payload.requestId())
                        || !prev.dimensionId().equals(payload.dimensionId())) {
                    // 新请求：停掉旧同步线程，重置聚合器
                    Thread old = syncThreads.remove(id);
                    if (old != null && old.isAlive()) {
                        old.interrupt();
                    }
                    cleanupSyncStateNoCount(id);
                    return new MetaAggregator(payload.dimensionId(), payload.requestId(),
                            totalChunks, new ConcurrentHashMap<>());
                }
                return prev;
            });
            aggregator.chunks().put(payload.chunkIndex(), payload.entries());
            if (aggregator.chunks().size() < aggregator.totalChunks()) {
                return; // 等待其余分块
            }
            pendingMeta.remove(playerId);

            Map<String, VoxyPackets.RegionMeta> clientMeta = new HashMap<>();
            int totalEntries = 0;
            for (int i = 0; i < aggregator.totalChunks(); i++) {
                Map<String, VoxyPackets.RegionMeta> chunk = aggregator.chunks().get(i);
                if (chunk == null) {
                    sendComplete(player, "", false, "meta_incomplete", 0, 0);
                    return;
                }
                totalEntries += chunk.size();
                if (totalEntries > VoxyPackets.MAX_META_ENTRIES) {
                    sendComplete(player, "", false, "meta_too_large", 0, 0);
                    return;
                }
                for (Map.Entry<String, VoxyPackets.RegionMeta> entry : chunk.entrySet()) {
                    clientMeta.put(aggregator.dimensionId() + "/" + entry.getKey(), entry.getValue());
                }
            }
            startSyncForPlayer(player, aggregator.dimensionId(), clientMeta);
        });
    }

    /** 校验玩家状态并启动同步线程（在服务器线程调用） */
    private static void startSyncForPlayer(ServerPlayer player, String requestedDimension,
                                           Map<String, VoxyPackets.RegionMeta> clientMeta) {
        UUID playerId = player.getUUID();
        if (syncingPlayers.contains(playerId)) {
            sendComplete(player, "", false, "busy", 0, 0);
            return;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            sendComplete(player, "", false, "no_world", 0, 0);
            return;
        }
        String currentDimension = level.dimension().location().toString();
        if (!currentDimension.equals(requestedDimension)) {
            sendComplete(player, "", false, "dimension_changed", 0, 0);
            return;
        }
        Path regionDir = resolveRegionDir(level);
        if (regionDir == null || !Files.isDirectory(regionDir)) {
            sendComplete(player, "", false, "region_dir_missing", 0, 0);
            return;
        }
        if (activeSyncCount.get() >= MAX_CONCURRENT_SYNCS) {
            sendComplete(player, "", false, "server_busy", 0, 0);
            return;
        }

        String mode = pendingMode.remove(playerId);
        if (mode == null) {
            mode = VoxySyncConfig.INSTANCE.syncMode;
        }
        if (!"radius".equals(mode) && !"all".equals(mode)) {
            mode = "radius";
        }
        // 玩家中心区域（在服务器线程读取，避免后台线程读世界状态）
        int centerRegionX = Math.floorDiv(player.blockPosition().getX(), BLOCKS_PER_REGION);
        int centerRegionZ = Math.floorDiv(player.blockPosition().getZ(), BLOCKS_PER_REGION);

        String syncId = UUID.randomUUID().toString();
        final String syncMode = mode;
        syncingPlayers.add(playerId);
        playerSyncDimensions.put(playerId, currentDimension);
        activeSyncCount.incrementAndGet();

        Thread thread = new Thread(
                () -> runSync(syncId, player, currentDimension, regionDir, clientMeta,
                        syncMode, centerRegionX, centerRegionZ),
                "voxysync-sync-" + playerId);
        thread.setDaemon(true);
        syncThreads.put(playerId, thread);
        thread.start();
    }

    // ---------- 同步主流程（后台线程） ----------

    private static void runSync(String syncId, ServerPlayer player, String dimensionId, Path regionDir,
                                Map<String, VoxyPackets.RegionMeta> clientMeta, String mode,
                                int centerRegionX, int centerRegionZ) {
        UUID playerId = player.getUUID();
        int transferredRegions = 0;
        long transferredBytes = 0;
        try {
            List<RegionFileInfo> regions = collectRegions(dimensionId, regionDir, clientMeta, mode,
                    centerRegionX, centerRegionZ, VoxySyncConfig.INSTANCE.radiusBlocks);
            long totalBytes = regions.stream().mapToLong(RegionFileInfo::sizeBytes).sum();
            sendStart(player, new SyncStartPayload(syncId, dimensionId, regions.size(), totalBytes));

            if (regions.isEmpty()) {
                sendComplete(player, syncId, true, "completed", 0, 0);
                return;
            }

            for (RegionFileInfo region : regions) {
                if (!isPlayerStillValid(player, dimensionId)) {
                    sendComplete(player, syncId, false, "interrupted", transferredRegions, transferredBytes);
                    return;
                }
                sendRegion(syncId, player, dimensionId, region);
                transferredRegions++;
                transferredBytes += region.sizeBytes();
                sendProgress(player, new SyncProgressPayload(syncId, transferredRegions, regions.size(),
                        transferredBytes, totalBytes, "sending"));
            }
            sendComplete(player, syncId, true, "completed", transferredRegions, transferredBytes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendComplete(player, syncId, false, "interrupted", transferredRegions, transferredBytes);
        } catch (Exception e) {
            LOGGER.error("Voxy 同步失败: {}", player.getGameProfile().getName(), e);
            sendComplete(player, syncId, false, "failed", transferredRegions, transferredBytes);
        } finally {
            // 若已有更新的同步线程接管了该玩家，则不要清理它的状态
            if (syncThreads.get(playerId) == Thread.currentThread()) {
                syncThreads.remove(playerId);
                cleanupSyncStateNoCount(playerId);
            }
            activeSyncCount.decrementAndGet();
        }
    }

    /**
     * 收集需要发送的区域文件。
     * 增量跳过：客户端缓存 (时间戳, 大小) 完全一致 → 跳过。
     */
    private static List<RegionFileInfo> collectRegions(String dimensionId, Path regionDir,
                                                       Map<String, VoxyPackets.RegionMeta> clientMeta,
                                                       String mode, int centerRegionX, int centerRegionZ,
                                                       int radiusBlocks) throws IOException {
        boolean radius = "radius".equals(mode);
        int regionRadius = 0;
        if (radius) {
            regionRadius = (int) Math.ceil(radiusBlocks / (double) BLOCKS_PER_REGION);
        }
        List<RegionFileInfo> regions = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(regionDir, "r.*.*.mca")) {
            for (Path path : stream) {
                String fileName = path.getFileName().toString();
                Matcher matcher = REGION_FILE_PATTERN.matcher(fileName);
                if (!matcher.matches()) {
                    continue;
                }
                long sizeBytes = Files.size(path);
                if (sizeBytes <= 0) {
                    continue;
                }
                int regionX = Integer.parseInt(matcher.group(1));
                int regionZ = Integer.parseInt(matcher.group(2));
                int dist = 0;
                if (radius) {
                    int dx = Math.abs(regionX - centerRegionX);
                    int dz = Math.abs(regionZ - centerRegionZ);
                    if (dx > regionRadius || dz > regionRadius) {
                        continue;
                    }
                    dist = Math.max(dx, dz);
                }
                long timestampSeconds = Files.getLastModifiedTime(path).toMillis() / 1000;
                String key = dimensionId + "/" + fileName;
                VoxyPackets.RegionMeta clientEntry = clientMeta.get(key);
                if (clientEntry != null
                        && clientEntry.timestampSeconds() == timestampSeconds
                        && clientEntry.sizeBytes() == sizeBytes) {
                    continue;
                }
                regions.add(new RegionFileInfo(path, regionX, regionZ, timestampSeconds, sizeBytes, dist));
            }
        }
        if (radius) {
            // 由近及远，方便玩家先拿到附近的 LoD
            regions.sort(Comparator.comparingInt(RegionFileInfo::dist)
                    .thenComparingInt(RegionFileInfo::regionX)
                    .thenComparingInt(RegionFileInfo::regionZ));
        } else {
            regions.sort(Comparator.comparingInt(RegionFileInfo::regionX)
                    .thenComparingInt(RegionFileInfo::regionZ));
        }
        return regions;
    }

    private static void sendRegion(String syncId, ServerPlayer player, String dimensionId,
                                   RegionFileInfo region) throws IOException, InterruptedException {
        int chunkSize = getPayloadDataSize();
        int totalParts = (int) Math.max(1, (region.sizeBytes() + chunkSize - 1) / chunkSize);
        ByteBuffer buffer = ByteBuffer.allocate(chunkSize);
        try (FileChannel channel = FileChannel.open(region.path(), StandardOpenOption.READ)) {
            for (int partIndex = 0; partIndex < totalParts; partIndex++) {
                long offset = (long) partIndex * chunkSize;
                buffer.clear();
                int read = channel.read(buffer, offset);
                if (read < 0) {
                    read = 0;
                }
                byte[] data = new byte[read];
                buffer.flip();
                buffer.get(data);
                if (data.length == 0 && region.sizeBytes() > 0) {
                    throw new IOException("空读异常: " + region.path());
                }
                if (!applySpeedLimit(data.length, player)) {
                    throw new InterruptedException("限速期间玩家失联");
                }
                RegionPartPayload payload = new RegionPartPayload(syncId, dimensionId,
                        region.regionX(), region.regionZ(), partIndex, totalParts,
                        offset, region.sizeBytes(), region.timestampSeconds(), data);
                sendPart(player, payload);
            }
        }
    }

    private static int getPayloadDataSize() {
        int maxPacketSize = Math.min(VoxySyncConfig.INSTANCE.maxPacketSize, MAX_PACKET_SIZE_LIMIT);
        return Math.max(16 * 1024, maxPacketSize - 2048);
    }

    private static boolean applySpeedLimit(int bytesSent, ServerPlayer player) throws InterruptedException {
        int limitKBps = VoxySyncConfig.INSTANCE.speedLimitKBps;
        if (limitKBps <= 0 || bytesSent <= 0) {
            return isPlayerStillValid(player);
        }
        UUID playerId = player.getUUID();
        long cycleStart = speedLimitCycleStart.getOrDefault(playerId, System.currentTimeMillis());
        long totalBytes = speedLimitBytesSent.getOrDefault(playerId, 0L) + bytesSent;
        speedLimitCycleStart.put(playerId, cycleStart);
        speedLimitBytesSent.put(playerId, totalBytes);

        long actualTimeMs = System.currentTimeMillis() - cycleStart;
        long expectedTimeMs = (totalBytes * 1000L) / (limitKBps * 1024L);
        if (actualTimeMs >= expectedTimeMs || actualTimeMs > 1000) {
            speedLimitCycleStart.put(playerId, System.currentTimeMillis());
            speedLimitBytesSent.put(playerId, 0L);
            return isPlayerStillValid(player);
        }
        long waitMs = expectedTimeMs - actualTimeMs;
        long waitStart = System.currentTimeMillis();
        while (System.currentTimeMillis() - waitStart < waitMs) {
            if (!isPlayerStillValid(player)) {
                return false;
            }
            Thread.sleep(Math.min(100, waitMs - (System.currentTimeMillis() - waitStart)));
        }
        return isPlayerStillValid(player);
    }

    private static boolean isPlayerStillValid(ServerPlayer player) {
        String dim = playerSyncDimensions.get(player.getUUID());
        return dim != null && isPlayerStillValid(player, dim);
    }

    private static boolean isPlayerStillValid(ServerPlayer player, String dimensionId) {
        return syncingPlayers.contains(player.getUUID())
                && player.connection != null
                && player.level().dimension().location().toString().equals(dimensionId);
    }

    // ---------- 发送（切回服务器线程） ----------

    private static void sendStart(ServerPlayer player, SyncStartPayload payload) {
        dispatchSend(player, VoxyPackets.SYNC_START, buf -> payload.encode(buf));
    }

    private static void sendProgress(ServerPlayer player, SyncProgressPayload payload) {
        dispatchSend(player, VoxyPackets.SYNC_PROGRESS, buf -> payload.encode(buf));
    }

    private static void sendPart(ServerPlayer player, RegionPartPayload payload) {
        dispatchSend(player, VoxyPackets.REGION_PART, buf -> payload.encode(buf));
    }

    private static void sendComplete(ServerPlayer player, String syncId, boolean success,
                                     String message, int transferredRegions, long transferredBytes) {
        SyncCompletePayload payload = new SyncCompletePayload(syncId, success, message,
                transferredRegions, transferredBytes);
        dispatchSend(player, VoxyPackets.SYNC_COMPLETE, buf -> payload.encode(buf));
    }

    private static void dispatchSend(ServerPlayer player, ResourceLocation channel, Consumer<FriendlyByteBuf> encoder) {
        MinecraftServer server = player.server;
        if (server == null) {
            return;
        }
        server.execute(() -> {
            if (player.connection != null && ServerPlayNetworking.canSend(player, channel)) {
                FriendlyByteBuf buf = PacketByteBufs.create();
                encoder.accept(buf);
                ServerPlayNetworking.send(player, channel, buf);
            }
        });
    }

    // ---------- 资源目录解析 ----------

    /**
     * 解析某个服务端维度的 region 目录（1.20.1 传统目录布局）：
     * 主世界 region/；地狱 DIM-1/region/；末地 DIM1/region/；
     * 自定义维度（数据包/Mod）dimensions/&lt;namespace&gt;/&lt;path&gt;/region/。
     */
    static Path resolveRegionDir(ServerLevel level) {
        try {
            Path worldRoot = level.getServer().getWorldPath(LevelResource.ROOT);
            if (!Files.exists(worldRoot)) {
                return null;
            }
            worldRoot = worldRoot.toRealPath();
            String dimPath = level.dimension().location().toString();
            String relative = switch (dimPath) {
                case "minecraft:overworld" -> "region";
                case "minecraft:the_nether" -> "DIM-1/region";
                case "minecraft:the_end" -> "DIM1/region";
                default -> "dimensions/" + dimPath.replace(':', '/') + "/region";
            };
            Path regionDir = worldRoot.resolve(relative);
            if (Files.isDirectory(regionDir)) {
                return regionDir.toRealPath();
            }
            LOGGER.warn("维度 {} 的 region 目录未找到: {}", dimPath, regionDir);
            return null;
        } catch (IOException e) {
            LOGGER.error("解析维度 {} 的 region 目录失败", level.dimension().location(), e);
            return null;
        }
    }

    // ---------- 诊断（/voxysync devtest，用于 /tmp 测试实例验证，只读） ----------

    /** 无客户端时验证收集逻辑：返回摘要文本（按指定模式/半径/中心收集，再模拟全量缓存验证增量跳过） */
    public static String diagCollect(ServerLevel level, int centerBlockX, int centerBlockZ,
                                     String mode, int radiusBlocksOverride) {
        try {
            Path regionDir = resolveRegionDir(level);
            if (regionDir == null) {
                return "region 目录未找到";
            }
            String dim = level.dimension().location().toString();
            int cx = Math.floorDiv(centerBlockX, BLOCKS_PER_REGION);
            int cz = Math.floorDiv(centerBlockZ, BLOCKS_PER_REGION);
            int radius = radiusBlocksOverride;
            if (radius <= 0) {
                radius = VoxySyncConfig.INSTANCE.radiusBlocks;
            }
            List<RegionFileInfo> all = collectRegions(dim, regionDir, Map.of(), mode, cx, cz, radius);
            long totalBytes = all.stream().mapToLong(RegionFileInfo::sizeBytes).sum();
            Map<String, VoxyPackets.RegionMeta> fullMeta = new HashMap<>();
            for (RegionFileInfo r : all) {
                // 与服务端聚合后的键一致：dim/file
                fullMeta.put(dim + "/" + r.path().getFileName().toString(),
                        new VoxyPackets.RegionMeta(r.timestampSeconds(), r.sizeBytes()));
            }
            List<RegionFileInfo> none = collectRegions(dim, regionDir, fullMeta, mode, cx, cz, radius);
            StringBuilder sb = new StringBuilder();
            sb.append("维度=").append(dim).append(" 模式=").append(mode)
                    .append(" 中心=(").append(cx * BLOCKS_PER_REGION).append(",").append(cz * BLOCKS_PER_REGION)
                    .append(")块 半径=").append(radius).append("块\n");
            sb.append("区域数(全目录): ").append(countAllFiles(regionDir))
                    .append("  待发送: ").append(all.size())
                    .append("  总计 ").append(totalBytes / 1024 / 1024).append(" MB\n");
            sb.append("增量跳过验证: 模拟全部已缓存 → 待发送 ").append(none.size()).append(" (应为 0)\n");
            if (!all.isEmpty()) {
                sb.append("前 5 个: ");
                all.stream().limit(5).forEach(r -> sb.append(r.path().getFileName())
                        .append(" ").append(r.sizeBytes()).append("B dist=").append(r.dist()).append("; "));
                sb.append("\n");
            }
            return sb.toString();
        } catch (IOException e) {
            return "diag 异常: " + e;
        }
    }

    private static int countAllFiles(Path regionDir) throws IOException {
        int n = 0;
        try (var stream = Files.newDirectoryStream(regionDir, "r.*.*.mca")) {
            for (var p : stream) {
                n++;
            }
        }
        return n;
    }

    /** 载荷编解码往返验证（RegionPartPayload 通道，用假数据） */
    public static String diagCodec() {
        try {
            byte[] data = new byte[4096];
            new java.util.Random(42).nextBytes(data);
            RegionPartPayload original = new RegionPartPayload("sync-1", "minecraft:overworld", 12, -3,
                    2, 5, 8192, data.length, 1725000000L, data);
            FriendlyByteBuf buf = PacketByteBufs.create();
            original.encode(buf);
            int writtenBytes = buf.readableBytes();
            RegionPartPayload decoded = RegionPartPayload.decode(buf);
            boolean ok = decoded.syncId().equals(original.syncId())
                    && decoded.dimensionId().equals(original.dimensionId())
                    && decoded.regionX() == original.regionX() && decoded.regionZ() == original.regionZ()
                    && decoded.partIndex() == original.partIndex() && decoded.totalParts() == original.totalParts()
                    && decoded.byteOffset() == original.byteOffset() && decoded.totalBytes() == original.totalBytes()
                    && decoded.timestampSeconds() == original.timestampSeconds()
                    && java.util.Arrays.equals(decoded.data(), original.data());
            return ok ? "编解码往返 OK (载荷 " + writtenBytes + " 字节)" : "编解码往返 失败!";
        } catch (Exception e) {
            return "编解码异常: " + e;
        }
    }

    /** 文件分片读校验：按实际分片大小读取某个 region 文件并重组，比对 SHA-256 */
    public static String diagFileRead(Path regionDir) {
        try (var stream = Files.newDirectoryStream(regionDir, "r.*.*.mca")) {
            Path p = stream.iterator().next();
            long size = Files.size(p);
            byte[] whole = Files.readAllBytes(p);
            java.security.MessageDigest wholeDigest = java.security.MessageDigest.getInstance("SHA-256");
            wholeDigest.update(whole);
            String shaWhole = hex(wholeDigest.digest());

            int chunk = getPayloadDataSize();
            byte[] buf = new byte[chunk];
            java.security.MessageDigest partsDigest = java.security.MessageDigest.getInstance("SHA-256");
            int parts = 0;
            long offset = 0;
            try (FileChannel channel = FileChannel.open(p, StandardOpenOption.READ)) {
                while (offset < size) {
                    ByteBuffer bb = ByteBuffer.wrap(buf);
                    int read = channel.read(bb, offset);
                    if (read < 0) {
                        break;
                    }
                    partsDigest.update(buf, 0, read);
                    offset += read;
                    parts++;
                }
            }
            String shaParts = hex(partsDigest.digest());
            boolean ok = shaWhole.equals(shaParts);
            return "分片读取完整性: " + (ok ? "OK" : "FAIL") + "  (文件 " + p.getFileName()
                    + " / " + size + " B / " + parts + " 片 × " + chunk + " B, sha256="
                    + shaParts.substring(0, 16) + "...)";
        } catch (Exception e) {
            return "分片读取校验异常: " + e;
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // ---------- 生命周期 ----------

    public static void onPlayerDisconnect(UUID playerId) {
        Thread thread = syncThreads.remove(playerId);
        if (thread != null && thread.isAlive()) {
            thread.interrupt();
        }
        // 若该玩家的同步线程仍在跑，由线程 finally 负责递减计数；未在跑则这里补一次
        if (thread == null || !thread.isAlive()) {
            cleanupSyncStateNoCount(playerId);
        }
    }

    public static void cleanup() {
        for (UUID playerId : new HashSet<>(syncingPlayers)) {
            onPlayerDisconnect(playerId);
        }
        syncingPlayers.clear();
        syncThreads.clear();
        playerSyncDimensions.clear();
        speedLimitBytesSent.clear();
        speedLimitCycleStart.clear();
        pendingMode.clear();
        pendingMeta.clear();
        activeSyncCount.set(0);
    }

    private static void cleanupSyncStateNoCount(UUID playerId) {
        syncingPlayers.remove(playerId);
        playerSyncDimensions.remove(playerId);
        speedLimitBytesSent.remove(playerId);
        speedLimitCycleStart.remove(playerId);
        pendingMeta.remove(playerId);
    }

    /** 供命令类调用：给某个玩家预设本次同步模式（request_sync 后由客户端发起请求时消费） */
    public static void setPendingMode(UUID playerId, String mode) {
        if (mode != null) {
            pendingMode.put(playerId, mode);
        }
    }

    /** 供命令类调用：向客户端发送"请发起同步"提示 */
    public static void requestClientSync(ServerPlayer player, String modeHint, String note) {
        MinecraftServer server = player.server;
        if (server == null) {
            return;
        }
        server.execute(() -> {
            if (player.connection != null && ServerPlayNetworking.canSend(player, VoxyPackets.REQUEST_SYNC)) {
                VoxyPackets.RequestSyncPayload payload = new VoxyPackets.RequestSyncPayload(modeHint, note);
                FriendlyByteBuf buf = PacketByteBufs.create();
                payload.encode(buf);
                ServerPlayNetworking.send(player, VoxyPackets.REQUEST_SYNC, buf);
            }
        });
    }

    public static Set<UUID> getSyncingPlayers() {
        return syncingPlayers;
    }

    public static int getActiveSyncCount() {
        return activeSyncCount.get();
    }

    private record RegionFileInfo(Path path, int regionX, int regionZ, long timestampSeconds,
                                  long sizeBytes, int dist) {
    }

    private record MetaAggregator(String dimensionId, String requestId, int totalChunks,
                                  Map<Integer, Map<String, VoxyPackets.RegionMeta>> chunks) {
    }
}
