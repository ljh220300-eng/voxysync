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
import java.util.List;
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
    /** 稳定暂存目录（staging/<维度>/region）：跨会话保留已完成的区域文件 */
    private static Path stableRegionDir;
    private static VoxySyncCache cache;
    /** 距上次持久化缓存已完成的区域数 */
    private static int completedSinceSave;
    /** 本次同步开始时客户端已有的区域数（用于按总量显示进度，避免“看起来归零”） */
    private static volatile int alreadyDone;
    /** 导入状态机（0=未导入 1=转换中 2=写入中 3=完成提示已发） */
    private static volatile int importStage;
    private static volatile int importWatchTicks;
    private static volatile int importWatchNotified;
    /** 手动中止标记（区分中断原因，避免误报失败） */
    private static volatile boolean manualStop;
    /** 当次导入的待导入文件清单与是否首次全量 */
    private static volatile java.util.List<String> pendingImportNames = java.util.List.of();
    private static volatile boolean pendingImportFirst;
    private static volatile boolean importBroken;
    private static final Map<String, RegionAssembly> assemblies = new ConcurrentHashMap<>();
    private static final ExecutorService VOXY_IO_WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "voxysync-client-io");
        thread.setDaemon(true);
        return thread;
    });

    /** 已发出能力探测的维度（防止进服每 tick 重复发请求） */
    private static volatile String requestedDimension = "";
    /** 已尝试过自动同步的维度（防止能力回复/切维度反复触发） */
    private static volatile String autoAttemptedDimension = "";
    /** 本维度自动同步剩余重试次数（Voxy 引擎未就绪时每 10 秒重试） */
    private static volatile int autoAttempts;
    private static volatile int autoAttemptTimer;
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
        String version = net.fabricmc.loader.api.FabricLoader.getInstance()
                .getModContainer("voxysync").map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        VoxyPackets.CapabilityRequestPayload payload = new VoxyPackets.CapabilityRequestPayload(version);
        FriendlyByteBuf buf = PacketByteBufs.create();
        payload.encode(buf);
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
            // 收到能力后，若启用且当前维度还没尝试过 → 自动发起（含重试）
            scheduleAutoStart(client);
            // 登录导入：已下载未渲染的立即渲染；已渲染自动跳过
            if (serverEnabled && !syncing) {
                maybeLoginImport(client);
            }
        });
    }

    private static void scheduleAutoStart(Minecraft client) {
        if (!serverEnabled || !VoxySyncConfig.INSTANCE.autoStartOnJoin) {
            return;
        }
        String dim = currentDimensionId(client);
        if (dim == null || dim.equals(autoAttemptedDimension)) {
            return;
        }
        autoAttemptedDimension = dim;
        autoAttempts = 5;
        autoAttemptTimer = 0;
        tryAutoStart(client);
    }

    /** 尝试自动同步；Voxy 引擎未就绪时由 {@link #onClientTick} 定时重试 */
    private static void tryAutoStart(Minecraft client) {
        if (!serverEnabled || autoAttempts <= 0 || syncing) {
            return;
        }
        String dim = currentDimensionId(client);
        if (dim == null || !dim.equals(autoAttemptedDimension)) {
            return;
        }
        if (client.player == null) {
            return;
        }
        if (!VoxyBridgeLoader.isVoxyInstalled()) {
            client.player.displayClientMessage(Component.literal("§7[VoxySync] 未检测到 Voxy 模组，无法同步世界数据（不影响游戏；安装 Voxy 后自动生效）"), false);
            autoAttempts = 0;
            return;
        }
        if (!VoxyBridgeLoader.isVoxyReady(client)) {
            autoAttempts--;
            if (autoAttempts == 0) {
                client.player.displayClientMessage(Component.literal("§c[VoxySync] Voxy 尚未就绪，可稍后重进或让管理员执行 /voxysync sync"), false);
            }
            return;
        }
        autoAttempts = 0;
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
        // 必须在 cleanupLocalState 之后设置（它会把 alreadyDone 清零，0.1.4 的显示 bug 就在这）
        alreadyDone = clientMeta.size();
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
            Path dimRoot = client.gameDirectory.toPath()
                    .resolve("voxysync")
                    .resolve("staging")
                    .resolve(safeName(dimensionId));
            // 跨会话持续同步：把之前会话目录里已完成的区域文件并入稳定目录（0.1.2）
            stableRegionDir = dimRoot.resolve("region");
            Files.createDirectories(stableRegionDir);
            migrateOldSessionFiles(dimRoot, stableRegionDir);
            // 当前会话目录（只装本次新下载的区域，导入也只导这些）
            stagingRoot = dimRoot.resolve(syncId);
            regionDir = stagingRoot.resolve("region");
            Files.createDirectories(regionDir);
            completedSinceSave = 0;
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
                    // 每个区域立即持久化：即使硬关游戏/断电也不丢已完成的区域
                    completedSinceSave++;
                    cache.save();
                    completedSinceSave = 0;
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
                boolean wasManualStop = manualStop;
                manualStop = false;
                if ("interrupted".equals(payload.message()) && wasManualStop) {
                    // 手动中止：不报“失败”，改报已停止并触发渲染
                    syncing = false;
                    if (cache != null) {
                        cache.save();
                    }
                    notifyPlayer(client, "§7[VoxySync] 已手动停止下载");
                    try {
                        mergeSessionToStable();
                    } catch (IOException ignored) {
                    }
                    final Path stableCopy = stableRegionDir;
                    client.execute(() -> {
                        if (stableCopy != null) {
                            finishImportOnClient(client, stableCopy);
                        }
                    });
                    return;
                }
                String reason = reasonText(payload.message());
                notifyPlayer(client, "§c[VoxySync] 同步失败：" + reason);
                if ("server_busy".equals(payload.message()) && retryAttempt == 0) {
                    retryAttempt = 1;
                    retryTicks = 20 * 60;
                    notifyPlayer(client, "§e[VoxySync] 60 秒后自动重试…");
                }
                syncing = false;
                // 保留已下载区域与缓存（0.1.2：断点续传），下次重进自动跳过
                if (cache != null) {
                    cache.save();
                }
                return;
            }

            if (cache != null) {
                cache.save();
            }
            if (payload.transferredRegions() == 0) {
                status = "completed";
                notifyPlayer(client, "§a[VoxySync] 世界数据已是最新，无需同步");
                syncing = false;
                return;
            }
            if (!assemblies.isEmpty()) {
                status = "client_io_failed";
                notifyPlayer(client, "§c[VoxySync] 有区域未收齐，请稍后重试");
                syncing = false;
                if (cache != null) {
                    cache.save();
                }
                return;
            }
            // 合并本次会话文件到稳定目录（0.0.9+）；随后按“待导入清单”触发导入
            try {
                mergeSessionToStable();
            } catch (IOException mergeEx) {
                LOGGER.warn("合并暂存目录失败", mergeEx);
            }
            final Path stableCopy = stableRegionDir;
            client.execute(() -> {
                syncing = false;
                if (stableCopy == null) {
                    return;
                }
                finishImportOnClient(client, stableCopy);
            });
        });
    }

    /** 把当前会话目录的 .mca 移入稳定目录（IO 线程调用） */
    static void mergeSessionToStable() throws IOException {
        if (stableRegionDir == null || regionDir == null) {
            return;
        }
        if (!Files.isDirectory(stableRegionDir) || !Files.isDirectory(regionDir)) {
            return;
        }
        try (var stream = Files.newDirectoryStream(regionDir, "*.mca")) {
            for (Path f : stream) {
                Path target = stableRegionDir.resolve(f.getFileName().toString());
                if (!Files.exists(target)) {
                    Files.move(f, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** 计算”待导入清单“：稳定目录中尚未渲染过的文件（首次全量 / 之后增量） */
    static List<String> planPendingImportNames() throws IOException {
        if (stableRegionDir == null || !Files.isDirectory(stableRegionDir)) {
            return List.of();
        }
        VoxyImportTracker tracker = importTrackerFor(stableRegionDir);
        Set<String> names = new java.util.LinkedHashSet<>();
        try (var stream = Files.newDirectoryStream(stableRegionDir, "*.mca")) {
            for (Path f : stream) {
                names.add(f.getFileName().toString());
            }
        }
        // 旧版 .full-import-done 迁移：标记存在而无 manifest → 视为已渲染
        if (!tracker.hasManifest() && Files.exists(stableRegionDir.resolve(".full-import-done"))) {
            tracker.bootstrapAll(names);
            return List.of();
        }
        List<String> pending = tracker.pending(names);
        LOGGER.info("[VoxySync] 待导入 {} 个（共 {} 个，已渲染 {}）",
                pending.size(), names.size(), tracker.importedCount()
                        + (tracker.hasManifest() ? 0 : names.size() - pending.size()));
        return pending;
    }

    private static VoxyImportTracker importTrackerFor(Path stableDir) {
        VoxyImportTracker tracker = new VoxyImportTracker(stableDir);
        return tracker;
    }

    /** 触发导入入口（主线程）：按清单选择全量/增量目录，交给 Voxy */
    private static void finishImportOnClient(Minecraft client, Path stableDir) {
        try {
            java.util.List<String> pending = planPendingImportNames();
            if (pending.isEmpty()) {
                status = "import_done";
                importStage = 3;
                return;
            }
            IVoxyBridge bridge = VoxyBridgeLoader.getBridge();
            if (bridge == null) {
                notifyPlayer(client, "§c[VoxySync] 未找到 Voxy 桥接，无法导入");
                status = "import_failed";
                return;
            }
            boolean first = !new VoxyImportTracker(stableDir).hasManifest();
            pendingImportNames = pending;
            pendingImportFirst = first;
            Path target = stableDir;
            if (!first) {
                // 增量：把待导入文件复制到独立目录，只导这些（不重复渲染旧数据）
                Path inc = stableDir.getParent().resolve("pending-import");
                deleteDirectory(inc);
                Files.createDirectories(inc);
                for (String name : pending) {
                    Path src = stableDir.resolve(name);
                    if (Files.exists(src)) {
                        Files.copy(src, inc.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                target = inc;
            }
            if (!bridge.startImport(client, target)) {
                notifyPlayer(client, "§e[VoxySync] Voxy 正在忙（已有导入进行中），稍后自动完成");
                status = "import_busy";
                return;
            }
            status = "import_started";
            importStage = 1;
            importWatchTicks = 0;
            importWatchNotified = 0;
            reportVoxySettings(client);
            notifyPlayer(client, "§a[VoxySync] 开始渲染已下载的地图数据（" + pending.size()
                    + " 个区域，约 " + Math.max(1, pending.size() / 300) + " 分钟）…");
        } catch (Exception e) {
            LOGGER.error("启动 Voxy 导入失败", e);
            notifyPlayer(client, "§c[VoxySync] 启动 Voxy 导入失败：" + e.getClass().getSimpleName());
            status = "import_failed";
        }
    }

    /** 打印 Voxy 在客户端实际生效的设置（诊断：渲染是否真的开启） */
    private static void reportVoxySettings(Minecraft client) {
        try {
            Class<?> cfgClass = Class.forName("me.cortex.voxy.client.config.VoxyConfig");
            Object cfg = cfgClass.getField("CONFIG").get(null);
            boolean enabled = cfgClass.getField("enabled").getBoolean(cfg);
            boolean render = cfgClass.getField("enableRendering").getBoolean(cfg);
            boolean ingest = cfgClass.getField("ingestEnabled").getBoolean(cfg);
            int distance = cfgClass.getField("sectionRenderDistance").getInt(cfg);
            String ver = net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getModContainer("voxy").map(c -> c.getMetadata().getVersion().getFriendlyString())
                    .orElse("unknown");
            LOGGER.info("[VoxySync] Voxy {} 实际配置: enabled={} enableRendering={} ingestEnabled={} sectionRenderDistance={}",
                    ver, enabled, render, ingest, distance);
            notifyPlayer(client, "§7[VoxySync] Voxy 配置检查: 渲染=" + (render ? "§a开" : "§c关")
                    + "§r 总开关=" + (enabled ? "§a开" : "§c关") + "§r LoD距离=" + distance + " 区块");
        } catch (Throwable t) {
            LOGGER.warn("[VoxySync] 读取 Voxy 配置失败", t);
        }
    }

    // ---------- 自动同步 / 维度切换（由客户端 mod 每 tick / 进服事件驱动） ----------

    /**
     * 登录导入：每次进服/切维度后，把“已下载但未渲染过”的区域交给 Voxy（已渲染的自动跳过）。
     * 全量标记（旧版 .full-import-done）兼容：存在即视为已渲染，不再重复全量。
     */
    private static void maybeLoginImport(Minecraft client) {
        try {
            if (client.level == null || client.player == null) {
                return;
            }
            if (syncing || importStage == 1 || importStage == 2) {
                return;
            }
            Path dimRoot = client.gameDirectory.toPath().resolve("voxysync").resolve("staging")
                    .resolve(safeName(currentDimensionId(client) != null ? currentDimensionId(client) : "overworld"));
            stableRegionDir = dimRoot.resolve("region");
            if (!Files.isDirectory(stableRegionDir)) {
                return;
            }
            java.util.List<String> pending = planPendingImportNames();
            if (pending.isEmpty()) {
                return;
            }
            finishImportOnClient(client, stableRegionDir);
        } catch (Throwable t) {
            LOGGER.warn("[VoxySync] 登录导入检查失败", t);
        }
    }

    /** 每次进服或维度变化后调用（在客户端线程） */
    public static void onWorldAvailable(Minecraft client) {
        if (client.level == null || client.player == null) {
            return;
        }
        String dim = currentDimensionId(client);
        if (dim == null) {
            return;
        }
        if (!dim.equals(requestedDimension)) {
            // 新维度：重新探测服务器能力；重置自动同步尝试状态（能力回复后再触发）
            requestedDimension = dim;
            autoAttemptedDimension = "";
            autoAttempts = 0;
            capabilityRequested = false;
            serverEnabled = false;
            requestCapability(client);
        }
    }

    /** 每 tick 调用：actionbar 进度 + server_busy 重试 + Voxy 未就绪自动重试 */
    public static void onClientTick(Minecraft client) {
        if (retryTicks > 0 && !syncing && client.level != null && client.player != null) {
            retryTicks--;
            if (retryTicks == 0 && serverEnabled) {
                startSync(client, currentDimensionId(client), false);
            }
        }
        // 导入阶段状态机：阶段1=转换（轮询 activeImporters）→ 阶段2=渲染数据写入（等约2分钟）→ 完成提示
        if ((importStage == 1 || importStage == 2) && client.level != null && client.player != null) {
            importWatchTicks++;
            if (importWatchTicks % 200 == 0) {
                try {
                    if (importStage == 1) {
                        IVoxyBridge bridge = VoxyBridgeLoader.getBridge();
                        boolean busy = bridge != null && bridge.isImportBusy(client);
                        int seconds = importWatchTicks / 20;
                        if (busy && seconds - importWatchNotified >= 60) {
                            importWatchNotified = seconds;
                            client.player.displayClientMessage(
                                    Component.literal("§7[VoxySync] 地图数据转换中（已 " + seconds + " 秒）…"), false);
                        } else if (!busy) {
                            // 转换完成 → 记录已导入清单（后续登录跳过），进入写入阶段
                            try {
                                VoxyImportTracker tracker = new VoxyImportTracker(stableRegionDir);
                                tracker.markImported(pendingImportNames);
                                if (pendingImportFirst) {
                                    java.util.Set<String> all = new java.util.HashSet<>();
                                    try (var stream = Files.newDirectoryStream(stableRegionDir, "*.mca")) {
                                        for (Path f : stream) {
                                            all.add(f.getFileName().toString());
                                        }
                                    }
                                    tracker.markImported(all);
                                }
                            } catch (IOException ignored) {
                            }
                            importStage = 2;
                            status = "import_write";
                            importWatchTicks = 0;
                            client.player.displayClientMessage(
                                    Component.literal("§7[VoxySync] 转换完成，渲染数据正在后台写入（约 1-2 分钟）…"), false);
                        }
                    } else if (importStage == 2 && importWatchTicks >= 240) { // 约 2 分钟宽限
                        importStage = 3;
                        status = "import_done";
                        client.player.displayClientMessage(
                                Component.literal("§a[VoxySync] ✅ 渲染数据就绪，飞到高处即可看到远处地形！"), false);
                    }
                } catch (Throwable t) {
                    LOGGER.warn("[VoxySync] 导入状态查询失败", t);
                }
            }
        }
        if (autoAttempts > 0 && !syncing && serverEnabled && client.level != null) {
            if (++autoAttemptTimer >= 200) { // 每 10 秒重试一次
                autoAttemptTimer = 0;
                tryAutoStart(client);
            }
        }
        if (!syncing || client.player == null) {
            return;
        }
        if (client.level == null || client.level.getGameTime() % PROGRESS_ACTIONBAR_INTERVAL_TICKS != 0) {
            return;
        }
        // 未收到 sync_start（totalRegions==0）时不要显示百分比，否则 已就绪/(已就绪+0) = 100% 闪烁
        if (totalRegions <= 0) {
            client.player.displayClientMessage(
                    Component.literal("§6Voxy 同步: §e正在请求…§r（已就绪 " + alreadyDone + "）"), true);
            return;
        }
        int done = alreadyDone + processedRegions;
        int total = alreadyDone + totalRegions;
        int percent = total > 0 ? done * 100 / total : 0;
        String text = "§6Voxy 同步: §e" + percent + "%§r (" + done + "/" + total
                + " 区域，其中已就绪 " + alreadyDone + ")";
        client.player.displayClientMessage(Component.literal(text), true);
    }

    public static String currentDimensionId(Minecraft client) {
        if (client == null || client.level == null) {
            return null;
        }
        return client.level.dimension().location().toString();
    }

    /** 手动中止下载并立即渲染已下载部分（/voxystop） */
    public static void requestStop(Minecraft client) {
        if (!syncing) {
            if (client.player != null) {
                client.player.displayClientMessage(
                        Component.literal("§7[VoxySync] 当前没有进行中的下载"), false);
            }
            return;
        }
        manualStop = true;
        try {
            if (ClientPlayNetworking.canSend(VoxyPackets.ABORT_SYNC)) {
                FriendlyByteBuf buf = PacketByteBufs.create();
                ClientPlayNetworking.send(VoxyPackets.ABORT_SYNC, buf);
            }
        } catch (IllegalArgumentException | IllegalStateException ignored) {
        }
        syncing = false;
        status = "stopped";
        notifyPlayer(client, "§7[VoxySync] 已停止下载，正在渲染已下载的部分…");
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

    /** 断线/退出时重置自动同步状态（保留 staging 与缓存，实现断点续传） */
    public static void onDisconnect() {
        manualStop = false;
        importStage = 0;
        pendingImportNames = java.util.List.of();
        requestedDimension = "";
        autoAttemptedDimension = "";
        autoAttempts = 0;
        capabilityRequested = false;
        serverEnabled = false;
        syncing = false;
        if (cache != null) {
            cache.save();
        }
        cleanupLocalState();
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
        // 保留 staging（断点续传），只尽力持久化已完成的缓存
        VOXY_IO_WORKER.execute(() -> {
            if (cache != null) {
                cache.save();
            }
        });
    }

    private static void cleanupLocalState() {
        syncing = false;
        retryTicks = 0;
        retryAttempt = 0;
        // 注意：不要在这里重置 alreadyDone —— handleStartOnWorker 也会调用本方法，
        // 0.1.5 的显示 bug 就是它在 sync_start 时把“已就绪”抹成了 0
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

    /**
     * 迁移旧会话目录里已完成的区域文件到稳定目录（0.1.2 断点续传）。
     * 旧目录：staging/&lt;维度&gt;/&lt;syncId&gt;/region/*.mca → staging/&lt;维度&gt;/region/
     */
    private static void migrateOldSessionFiles(Path dimRoot, Path stableRegionDir) throws IOException {
        try (var stream = Files.newDirectoryStream(dimRoot)) {
            for (Path entry : stream) {
                if (!Files.isDirectory(entry) || entry.getFileName().toString().equals("region")) {
                    continue;
                }
                Path oldRegion = entry.resolve("region");
                if (!Files.isDirectory(oldRegion)) {
                    continue;
                }
                try (var files = Files.newDirectoryStream(oldRegion, "*.mca")) {
                    for (Path f : files) {
                        Path target = stableRegionDir.resolve(f.getFileName().toString());
                        if (!Files.exists(target)) {
                            Files.move(f, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
                // 旧目录里的残留（.part 等）不再需要，删除
                deleteDirectory(entry);
            }
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
