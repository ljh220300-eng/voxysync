package com.mapsyncer.client;

import com.mapsyncer.network.ChunkMapData;
import com.mapsyncer.network.PacketHandler;
import com.mapsyncer.util.ChatUtils;
import com.mapsyncer.util.DimensionPathMapping;
import com.mapsyncer.util.HashUtils;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 地图数据包接收器。
 * 处理从服务端接收的地图同步数据包，并负责写入到 Xaero 地图目录。
 *
 * <p>主要功能：</p>
 * <ul>
 *   <li>处理同步请求、响应和进度更新</li>
 *   <li>写入同步数据到 Xaero 目录，边接收边加载</li>
 *   <li>重置区域加载状态，触发地图重新加载</li>
 *   <li>检测超时和陈旧的同步请求，防止内存泄漏</li>
 *   <li>同步当前维度时，预先卸载视野范围内的region以便重新加载服务端数据</li>
 * </ul>
 */
public class MapPacketReceiver {

    private static final Logger LOGGER = LoggerFactory.getLogger(MapPacketReceiver.class);

    /** 同步是否正在进行中，用于协调区块更新的禁用 */
    private static volatile boolean syncInProgress = false;

    /**
     * 检查同步是否正在进行中。
     *
     * @return true 表示同步正在进行
     */
    public static boolean isSyncInProgress() {
        return syncInProgress;
    }

    /** 服务端是否已安装 MapSyncer（加入服务器时检测） */
    private static volatile boolean serverInstalled = false;

    /** 服务端版本号 */
    private static volatile String serverVersion = "";

    /** 最后写入的 mw 目录，用于缓存清除 */
    private static volatile Path lastMwDir = null;

    /** 同步开始时间，用于检测陈旧的同步（防止内存泄漏） */
    private static volatile long syncStartTime = 0;

    /** 陈旧同步超时时间（10分钟） */
    private static final long STALE_SYNC_TIMEOUT_MS = 10 * 60 * 1000;

    /** 客户端同步写盘线程，避免网络包处理和文件 IO 阻塞客户端主线程。 */
    private static final ExecutorService SYNC_WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "mapsyncer-client-sync-worker");
        thread.setDaemon(true);
        return thread;
    });

    /** 每 tick 最多刷新多少个 Xaero region，避免同步时集中反射刷新导致掉帧。 */
    private static final int REGION_RELOAD_BATCH_SIZE = 10;
    private static final long REGION_RELOAD_BATCH_DELAY_MS = 2_000;

    /** 等待在客户端主线程触发 Xaero 刷新的 region 队列。 */
    private static final ConcurrentLinkedQueue<PendingRegionLoad> pendingRegionLoads = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<PendingRegionLoad> externalRegionLoads = new ConcurrentLinkedQueue<>();

    /** 用于丢弃断线、取消或新同步开始后遗留的旧同步任务。 */
    private static final AtomicLong syncGeneration = new AtomicLong();
    private static final Map<String, IncomingRegionParts> incomingRegionParts = new ConcurrentHashMap<>();
    private static final Map<Path, List<Path>> cacheDirectoriesByMwDir = new ConcurrentHashMap<>();

    private static volatile boolean completionPending = false;
    private static volatile int completionRegionCount = 0;
    private static volatile long lastRegionReloadFlushMillis = 0;

    /** 同步期间更新的区域坐标集合（仅存储坐标，不存储数据，节省内存） */
    private static final Set<XaeroMapIntegrator.RegionCoord> updatedRegionCoords = ConcurrentHashMap.newKeySet();

    /** 已加载的区域集合（避免重复加载） */
    private static final Set<XaeroMapIntegrator.RegionCoord> loadedRegions = ConcurrentHashMap.newKeySet();

    /** 反射 API 缓存（避免重复反射调用开销） */
    private static volatile Object cachedMapProcessor = null;
    private static volatile Object cachedMapSaveLoad = null;
    private static volatile Method cachedGetLeafMapRegion = null;
    private static volatile Method cachedRequestLoad = null;
    private static volatile Field cachedLoadStateField = null;
    private static volatile Field cachedShouldCacheField = null;
    private static volatile Method cachedSetHasHadTerrain = null;
    private static volatile Method cachedCancelRefresh = null;

    /** 反射 API 是否已初始化 */
    private static volatile boolean reflectionInitialized = false;

    /**
     * 检查当前同步是否陈旧（运行时间过长）。
     * 陈旧的同步可能表示连接中断，需要清除数据。
     *
     * @return 如果同步陈旧返回 true；否则返回 false
     */
    public static boolean isSyncStale() {
        if (!syncInProgress || syncStartTime == 0) {
            return false;
        }
        return System.currentTimeMillis() - syncStartTime > STALE_SYNC_TIMEOUT_MS;
    }

    /**
     * 清除所有累积的同步数据，防止内存泄漏。
     * 在同步中断或变得陈旧时调用。
     */
    public static void clearSyncData() {
        syncGeneration.incrementAndGet();
        syncInProgress = false;
        lastMwDir = null;
        syncStartTime = 0;
        completionPending = false;
        completionRegionCount = 0;
        pendingRegionLoads.clear();
        externalRegionLoads.clear();
        loadedRegions.clear();
        lastRegionReloadFlushMillis = 0;
        Path serverDir = XaeroMapIntegrator.getCurrentServerDirectory();
        cleanupPartFiles();
        cleanupStalePartFiles(serverDir);
        cacheDirectoriesByMwDir.clear();
        clearReceivedChunks();
        LOGGER.info("Cleared sync data to prevent memory leak");
    }

    /**
     * 清除累积的区域坐标集合，释放内存。
     * 在同步完成、中断或服务器停止时调用。
     */
    public static void clearReceivedChunks() {
        updatedRegionCoords.clear();
    }

    /**
     * 检查服务端是否已安装 MapSyncer
     *
     * @return true 表示服务端已安装
     */
    public static boolean isServerInstalled() {
        return serverInstalled;
    }

    public static String getServerVersion() {
        return serverVersion;
    }

    /**
     * 重置服务端安装状态（离开服务器时调用）
     */
    public static void resetServerStatus() {
        serverInstalled = false;
        serverVersion = "";
        AutoSyncManager.reset();
        clearSyncData();
        clearReflectionCache();
    }

    public static void handleServerInstalled(PacketHandler.ServerInstalledPayload payload,
            ClientPlayNetworking.Context context) {
        serverInstalled = true;
        serverVersion = payload.version();
        LOGGER.info("Server has MapSyncer installed, version: {}", serverVersion);
        AutoSyncManager.onServerInstalled();
    }

    /**
     * 处理服务端返回的同步响应数据包。
     * 实现边接收边加载优化，每个 region 写入后立即触发加载。
     *
     * <p>状态处理：</p>
     * <ul>
     *   <li>"ok" - 有数据同步，流式处理每个 region</li>
     *   <li>"uptodate" - 地图已是最新，直接返回</li>
     *   <li>"no_cache" - 服务端无缓存，直接返回</li>
     *   <li>"dim_not_available" - 维度不存在，直接返回</li>
     * </ul>
     *
     * @param payload 同步响应数据包
     * @param context 数据包上下文
     */
    public static void handleSyncResponse(PacketHandler.SyncResponsePayload payload, ClientPlayNetworking.Context context) {
        context.client().execute(() -> {
            String status = payload.status();
            List<ChunkMapData> chunks = payload.chunks();
            int serverWorldId = payload.worldId();

            LOGGER.debug("Received sync response: status={}, chunks={}, isComplete={}", status, chunks.size(), payload.isComplete());

            Path serverDir = XaeroMapIntegrator.getCurrentServerDirectory();
            DefaultMapMigration.schedule(serverDir);

            // 根据状态决定处理方式
            if ("no_cache".equals(status) || "dim_not_available".equals(status)) {
                LOGGER.info("Server returned error status: {}, no sync needed", status);
                SyncProgressTracker.cancelTracking();
                clearSyncData();
                clearReflectionCache();
                clearSyncStateOnWorker(serverDir);
                return;
            }

            if ("uptodate".equals(status)) {
                LOGGER.info("Map is up-to-date, no sync needed");
                SyncProgressTracker.completeWithCount(0);
                clearSyncData();
                clearReflectionCache();
                markSyncCompleteOnWorker(serverDir);
                return;
            }

            // status == "ok"，有数据需要同步
            if (isSyncStale()) {
                SyncProgressTracker.cancelTracking();
                clearSyncData();
                clearReflectionCache();
                LOGGER.warn("Sync was stale, cleared accumulated data");
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.sendSystemMessage(ChatUtils.error("mapsyncer.sync.timeout"));
                }
                return;
            }

            // 首次收到数据时初始化反射缓存
            if (!syncInProgress) {
                syncInProgress = true;
                syncStartTime = System.currentTimeMillis();
                long generation = syncGeneration.incrementAndGet();
                updatedRegionCoords.clear();
                loadedRegions.clear();
                pendingRegionLoads.clear();
                cleanupPartFiles();
                cleanupStalePartFiles(serverDir);
                cacheDirectoriesByMwDir.clear();
                completionPending = false;
                completionRegionCount = 0;
                lastRegionReloadFlushMillis = System.currentTimeMillis();
                LOGGER.info("Starting sync (background streaming mode, generation={})", generation);
                initializeReflectionCache();
            }

            // 获取当前视距范围（用于判断视距内/外）
            // 注意：视距判断需要使用 chunk 的 caveLayer，而不是默认的地表层
            Minecraft mc = Minecraft.getInstance();
            boolean isCaveDimension = mc.level != null && mc.level.dimension() == Level.NETHER;
            String currentXaeroDim = mc.level != null
                    ? DimensionPathMapping.getInstance().toXaeroDimension(mc.level.dimension().identifier().toString())
                    : null;
            Map<Integer, Set<XaeroMapIntegrator.RegionCoord>> viewRegionsByLayer = new HashMap<>();
            for (ChunkMapData chunk : chunks) {
                boolean shouldProcess = isCaveDimension
                    ? (chunk.caveLayer != Integer.MAX_VALUE)
                    : (chunk.caveLayer == Integer.MAX_VALUE);
                if (shouldProcess && currentXaeroDim != null && currentXaeroDim.equals(chunk.dimension)) {
                    viewRegionsByLayer.computeIfAbsent(chunk.caveLayer, XaeroMapIntegrator::getViewDistanceRegions);
                }
            }

            long generation = syncGeneration.get();
            List<ChunkMapData> chunkSnapshot = List.copyOf(chunks);
            SYNC_WORKER.execute(() -> processSyncResponseOnWorker(
                    context.client(),
                    chunkSnapshot,
                    payload.isComplete(),
                    serverWorldId,
                    serverDir,
                    isCaveDimension,
                    currentXaeroDim,
                    viewRegionsByLayer,
                    generation));
        });
    }

    /**
     * 处理服务端发送的进度更新数据包。
     * 更新同步进度追踪器的状态。
     *
     * @param payload 进度更新数据包
     * @param context 数据包上下文
     */
    public static void handleSyncProgress(PacketHandler.SyncProgressPayload payload, ClientPlayNetworking.Context context) {
        context.client().execute(() ->
                SyncProgressTracker.update(payload.processed(), payload.total(), payload.status()));
    }

    public static void handleSyncRegionPart(PacketHandler.SyncRegionPartPayload payload,
            ClientPlayNetworking.Context context) {
        Path serverDir = XaeroMapIntegrator.getCurrentServerDirectory();
        DefaultMapMigration.schedule(serverDir);
        boolean isCaveDimension = context.client().level != null && context.client().level.dimension() == Level.NETHER;
        String currentXaeroDim = context.client().level != null
                ? DimensionPathMapping.getInstance().toXaeroDimension(context.client().level.dimension().identifier().toString())
                : null;

        if (!syncInProgress) {
            syncInProgress = true;
            syncStartTime = System.currentTimeMillis();
            syncGeneration.incrementAndGet();
            lastRegionReloadFlushMillis = System.currentTimeMillis();
            initializeReflectionCache();
        }

        long generation = syncGeneration.get();
        SYNC_WORKER.execute(() -> processRegionPartOnWorker(
                payload, serverDir, isCaveDimension, currentXaeroDim, generation));
    }

    public static void handleSyncRegionComplete(PacketHandler.SyncRegionCompletePayload payload,
            ClientPlayNetworking.Context context) {
        Path serverDir = XaeroMapIntegrator.getCurrentServerDirectory();
        boolean isCaveDimension = context.client().level != null && context.client().level.dimension() == Level.NETHER;
        String currentXaeroDim = context.client().level != null
                ? DimensionPathMapping.getInstance().toXaeroDimension(context.client().level.dimension().identifier().toString())
                : null;
        long generation = syncGeneration.get();
        SYNC_WORKER.execute(() -> processRegionCompleteOnWorker(
                context.client(), payload, serverDir, isCaveDimension, currentXaeroDim, generation));
    }

    /**
     * 同步完成后恢复区块更新状态。
     * 不再使用全局暂停机制。
     */
    private static void resumeChunkUpdates() {
        syncInProgress = false;
        ClientTimestampCache.saveCurrent();
        LOGGER.info("Sync complete");
    }

    private static void processSyncResponseOnWorker(Minecraft client,
            List<ChunkMapData> chunks,
            boolean isComplete,
            int serverWorldId,
            Path serverDir,
            boolean isCaveDimension,
            String currentXaeroDim,
            Map<Integer, Set<XaeroMapIntegrator.RegionCoord>> viewRegionsByLayer,
            long generation) {
        if (generation != syncGeneration.get()) {
            return;
        }

        try {
            ClientTimestampCache tsCache = getTimestampCacheOnWorker(serverDir);

            for (ChunkMapData chunk : chunks) {
                if (generation != syncGeneration.get()) {
                    return;
                }

                XaeroMapIntegrator.RegionCoord coord = new XaeroMapIntegrator.RegionCoord(
                        chunk.regionX, chunk.regionZ, chunk.caveLayer);
                updatedRegionCoords.add(coord);

                Path mwDir = null;
                if (serverDir != null) {
                    mwDir = XaeroMapIntegrator.writeChunkDataAndGetMwDir(chunk, serverDir, serverWorldId);
                    if (mwDir != null) {
                        lastMwDir = mwDir;
                        cacheDirectoriesByMwDir.remove(mwDir);
                    }
                } else {
                    LOGGER.warn("Skipping map write because server directory is unavailable");
                }

                if (tsCache != null) {
                    String relativePath = buildRelativePathForCache(chunk);
                    String hash = HashUtils.computeHash(chunk.data);
                    tsCache.update(relativePath, chunk.timestampSeconds, hash);
                    tsCache.saveDeferred();
                }

                boolean shouldProcess = isCaveDimension
                    ? (chunk.caveLayer != Integer.MAX_VALUE)
                    : (chunk.caveLayer == Integer.MAX_VALUE);
                if (mwDir != null) {
                    clearSingleRegionCache(coord, mwDir);
                }
                if (shouldProcess && mwDir != null
                        && currentXaeroDim != null && currentXaeroDim.equals(chunk.dimension)) {
                    Set<XaeroMapIntegrator.RegionCoord> viewRegionsForLayer = viewRegionsByLayer.get(chunk.caveLayer);
                    boolean inViewDistance = viewRegionsForLayer != null && viewRegionsForLayer.contains(coord);
                    if (inViewDistance) {
                        pendingRegionLoads.offer(new PendingRegionLoad(coord, chunk.caveLayer, true, generation));
                        LOGGER.debug("Queued region ({}, {}) layer={} for throttled Xaero reload",
                                coord.x(), coord.z(), chunk.caveLayer);
                    } else {
                        LOGGER.debug("Region ({}, {}) layer={} written; Xaero reload deferred until needed",
                                coord.x(), coord.z(), chunk.caveLayer);
                    }
                }
            }

            if (isComplete) {
                int totalReceived = updatedRegionCoords.size();
                if (tsCache != null) {
                    tsCache.markSyncComplete();
                }
                cleanupPartFiles();
                cleanupStalePartFiles(serverDir);

                client.execute(() -> {
                    if (generation != syncGeneration.get()) {
                        return;
                    }
                    completionRegionCount = totalReceived;
                    completionPending = true;
                    tryCompleteSyncOnClient(generation);
                });
            }
        } catch (Exception e) {
            LOGGER.error("Failed to process sync response on background worker", e);
            client.execute(() -> {
                if (generation != syncGeneration.get()) {
                    return;
                }
                SyncProgressTracker.cancelTracking();
                clearSyncData();
                clearReflectionCache();
            });
        }
    }

    private static ClientTimestampCache getTimestampCacheOnWorker(Path serverDir) {
        return serverDir != null && serverDir.toFile().exists()
                ? ClientTimestampCache.getInstance(serverDir)
                : null;
    }

    private static void processRegionPartOnWorker(PacketHandler.SyncRegionPartPayload payload,
            Path serverDir, boolean isCaveDimension, String currentXaeroDim, long generation) {
        if (generation != syncGeneration.get() || serverDir == null) {
            return;
        }

        ChunkMapData chunk = new ChunkMapData(payload.regionX(), payload.regionZ(), payload.dimension(),
                new byte[0], payload.timestampSeconds(), payload.caveLayer());
        XaeroMapIntegrator.RegionFileTarget target =
                XaeroMapIntegrator.resolveRegionFileTarget(chunk, serverDir, payload.worldId());
        if (target == null) {
            return;
        }
        if (payload.totalParts() <= 0 || payload.totalBytes() < 0
                || payload.byteOffset() < 0 || payload.byteOffset() + payload.data().length > payload.totalBytes()) {
            return;
        }

        try {
            Files.createDirectories(target.targetDir());
            String key = payload.syncId() + "|" + payload.regionKey();
            IncomingRegionParts parts = incomingRegionParts.computeIfAbsent(key, ignored ->
                    new IncomingRegionParts(target.partFile(), target.outputFile(), payload.totalParts(),
                            payload.totalBytes(), payload.timestampSeconds(), payload.hash(), chunk));
            if (!parts.matches(payload, target)) {
                cleanupIncomingPart(key, parts);
                return;
            }
            if (payload.partIndex() < 0 || payload.partIndex() >= parts.totalParts) {
                cleanupIncomingPart(key, parts);
                return;
            }
            if (parts.received.get(payload.partIndex())) {
                return;
            }
            if (parts.receivedBytes + payload.data().length > parts.totalBytes) {
                cleanupIncomingPart(key, parts);
                return;
            }

            try (SeekableByteChannel channel = Files.newByteChannel(parts.partFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                channel.position(payload.byteOffset());
                channel.write(ByteBuffer.wrap(payload.data()));
            }
            parts.received.set(payload.partIndex());
            parts.receivedBytes += payload.data().length;
        } catch (Exception e) {
            LOGGER.error("Failed to write region part {}", payload.regionKey(), e);
        }
    }

    private static void processRegionCompleteOnWorker(Minecraft client,
            PacketHandler.SyncRegionCompletePayload payload, Path serverDir,
            boolean isCaveDimension, String currentXaeroDim, long generation) {
        if (generation != syncGeneration.get() || serverDir == null) {
            return;
        }

        String key = payload.syncId() + "|" + payload.regionKey();
        IncomingRegionParts parts = incomingRegionParts.remove(key);
        if (parts == null) {
            LOGGER.warn("Missing part state for completed region {}", payload.regionKey());
            return;
        }

        try {
            if (parts.received.cardinality() != payload.totalParts()
                    || Files.size(parts.partFile) != payload.totalBytes()) {
                cleanupIncomingPart(key, parts);
                LOGGER.warn("Incomplete region parts for {}", payload.regionKey());
                return;
            }

            String hash = HashUtils.computeFileHash(parts.partFile);
            if (!hash.equals(payload.hash())) {
                cleanupIncomingPart(key, parts);
                LOGGER.warn("Hash mismatch for region {}: {} != {}", payload.regionKey(), hash, payload.hash());
                return;
            }

            Files.createDirectories(parts.outputFile.getParent());
            Path mwDir = parts.chunk.caveLayer == Integer.MAX_VALUE
                    ? parts.outputFile.getParent()
                    : parts.outputFile.getParent().getParent().getParent();
            try {
                Files.move(parts.partFile, parts.outputFile,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(parts.partFile, parts.outputFile, StandardCopyOption.REPLACE_EXISTING);
            }

            lastMwDir = mwDir;
            cacheDirectoriesByMwDir.remove(mwDir);

            ClientTimestampCache tsCache = getTimestampCacheOnWorker(serverDir);
            if (tsCache != null) {
                tsCache.update(payload.regionKey(), payload.timestampSeconds(), payload.hash());
                tsCache.saveDeferred();
            }

            XaeroMapIntegrator.RegionCoord coord = new XaeroMapIntegrator.RegionCoord(
                    payload.regionX(), payload.regionZ(), payload.caveLayer());
            updatedRegionCoords.add(coord);
            clearSingleRegionCache(coord, lastMwDir);
            boolean shouldProcess = isCaveDimension
                    ? (payload.caveLayer() != Integer.MAX_VALUE)
                    : (payload.caveLayer() == Integer.MAX_VALUE);
            if (shouldProcess && currentXaeroDim != null && currentXaeroDim.equals(payload.dimension())) {
                Set<XaeroMapIntegrator.RegionCoord> viewRegions =
                        XaeroMapIntegrator.getViewDistanceRegions(payload.caveLayer());
                if (viewRegions.contains(coord)) {
                    pendingRegionLoads.offer(new PendingRegionLoad(coord, payload.caveLayer(), true, generation));
                }
            }
        } catch (Exception e) {
            cleanupIncomingPart(key, parts);
            LOGGER.error("Failed to complete region parts {}", payload.regionKey(), e);
        }
    }

    private static void cleanupIncomingPart(String key, IncomingRegionParts parts) {
        incomingRegionParts.remove(key);
        try {
            Files.deleteIfExists(parts.partFile);
        } catch (Exception e) {
            LOGGER.debug("Failed to delete part file {}", parts.partFile, e);
        }
    }

    private static void cleanupPartFiles() {
        for (Map.Entry<String, IncomingRegionParts> entry : incomingRegionParts.entrySet()) {
            cleanupIncomingPart(entry.getKey(), entry.getValue());
        }
        incomingRegionParts.clear();
    }

    private static void cleanupStalePartFiles(Path serverDir) {
        if (serverDir == null || !Files.exists(serverDir)) {
            return;
        }

        try (var stream = Files.walk(serverDir)) {
            stream.filter(path -> path.getFileName() != null
                            && path.getFileName().toString().endsWith(".zip.part"))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception e) {
                            LOGGER.debug("Failed to delete stale part file {}", path, e);
                        }
                    });
        } catch (Exception e) {
            LOGGER.debug("Failed to scan stale part files under {}", serverDir, e);
        }
    }

    private static void markSyncCompleteOnWorker(Path serverDir) {
        SYNC_WORKER.execute(() -> {
            ClientTimestampCache tsCache = getTimestampCacheOnWorker(serverDir);
            if (tsCache != null) {
                tsCache.markSyncComplete();
            }
        });
    }

    private static void clearSyncStateOnWorker(Path serverDir) {
        SYNC_WORKER.execute(() -> {
            ClientTimestampCache tsCache = getTimestampCacheOnWorker(serverDir);
            if (tsCache != null) {
                tsCache.clearSyncState();
            }
        });
    }

    public static void queueExternalRegionReloads(Set<XaeroMapIntegrator.RegionCoord> regions) {
        if (regions == null || regions.isEmpty()) {
            return;
        }
        long generation = syncGeneration.get();
        for (XaeroMapIntegrator.RegionCoord coord : regions) {
            if (coord != null) {
                externalRegionLoads.offer(new PendingRegionLoad(coord, coord.caveLayer(), true, generation));
            }
        }
        lastRegionReloadFlushMillis = 0;
    }

    private static void flushExternalRegionLoads() {
        long generation = syncGeneration.get();
        if (!externalRegionLoads.isEmpty() && !reflectionInitialized) {
            initializeReflectionCache();
        }
        if (externalRegionLoads.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (externalRegionLoads.size() < REGION_RELOAD_BATCH_SIZE
                && now - lastRegionReloadFlushMillis < REGION_RELOAD_BATCH_DELAY_MS) {
            return;
        }
        int processed = 0;
        while (processed < REGION_RELOAD_BATCH_SIZE) {
            PendingRegionLoad pending = externalRegionLoads.poll();
            if (pending == null) {
                break;
            }
            if (pending.generation() != generation) {
                continue;
            }
            triggerSingleRegionLoad(pending.coord(), pending.caveLayer(), pending.inViewDistance());
            processed++;
        }
        if (processed > 0) {
            lastRegionReloadFlushMillis = System.currentTimeMillis();
        }
    }

    public static void onClientTick(Minecraft client) {
        long generation = syncGeneration.get();
        if (pendingRegionLoads.isEmpty()) {
            flushExternalRegionLoads();
            tryCompleteSyncOnClient(generation);
            return;
        }

        long now = System.currentTimeMillis();
        boolean shouldFlush = completionPending
                || pendingRegionLoads.size() >= REGION_RELOAD_BATCH_SIZE
                || now - lastRegionReloadFlushMillis >= REGION_RELOAD_BATCH_DELAY_MS;
        if (!shouldFlush) {
            tryCompleteSyncOnClient(generation);
            return;
        }

        int processed = 0;
        while (processed < REGION_RELOAD_BATCH_SIZE) {
            PendingRegionLoad pending = pendingRegionLoads.poll();
            if (pending == null) {
                break;
            }
            if (pending.generation() != generation) {
                continue;
            }

            triggerSingleRegionLoad(pending.coord(), pending.caveLayer(), pending.inViewDistance());
            processed++;
        }
        if (processed > 0) {
            lastRegionReloadFlushMillis = now;
        }

        flushExternalRegionLoads();
        tryCompleteSyncOnClient(generation);
    }

    private static void tryCompleteSyncOnClient(long generation) {
        if (!completionPending || generation != syncGeneration.get() || !pendingRegionLoads.isEmpty()) {
            return;
        }

        int totalReceived = completionRegionCount;
        LOGGER.info("同步完成: 总计 {} 个区域已处理", totalReceived);

        if (!updatedRegionCoords.isEmpty()) {
            XaeroMapIntegrator.recordUpdatedRegionCoords(new HashSet<>(updatedRegionCoords));
        } else {
            LOGGER.info("Sync complete with no data received");
        }

        SyncProgressTracker.completeWithCount(totalReceived);
        resumeChunkUpdates();
        clearSyncState();
        clearReflectionCache();
    }

    /**
     * 清理同步状态（非反射缓存）。
     */
    private static void clearSyncState() {
        updatedRegionCoords.clear();
        loadedRegions.clear();
        pendingRegionLoads.clear();
        ClientTimestampCache.saveCurrent();
        cleanupPartFiles();
        cleanupStalePartFiles(XaeroMapIntegrator.getCurrentServerDirectory());
        cacheDirectoriesByMwDir.clear();
        completionPending = false;
        completionRegionCount = 0;
        lastMwDir = null;
        syncStartTime = 0;
    }

    /**
     * 清理反射 API 缓存。
     */
    private static void clearReflectionCache() {
        reflectionInitialized = false;
        cachedMapProcessor = null;
        cachedMapSaveLoad = null;
        cachedGetLeafMapRegion = null;
        cachedRequestLoad = null;
        cachedLoadStateField = null;
        cachedShouldCacheField = null;
        cachedSetHasHadTerrain = null;
        cachedCancelRefresh = null;
    }

    // ========== 边接收边加载优化方法 ==========

    /**
     * 初始化反射 API 缓存（一次性，避免重复反射开销）。
     * 在首次收到同步数据时调用。
     */
    private static void initializeReflectionCache() {
        if (reflectionInitialized) return;

        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;

            // 获取 WorldMapSession
            Class<?> worldMapSessionClass = Class.forName("xaero.map.WorldMapSession");
            Object session = worldMapSessionClass.getMethod("getCurrentSession").invoke(null);
            if (session == null) {
                LOGGER.warn("无法初始化反射缓存: WorldMapSession 为空");
                return;
            }

            // 获取 MapProcessor
            Class<?> mapProcessorClass = Class.forName("xaero.map.MapProcessor");
            cachedMapProcessor = worldMapSessionClass.getMethod("getMapProcessor").invoke(session);
            if (cachedMapProcessor == null) {
                LOGGER.warn("无法初始化反射缓存: MapProcessor 为空");
                return;
            }

            // 获取 MapSaveLoad
            Class<?> mapSaveLoadClass = Class.forName("xaero.map.file.MapSaveLoad");
            cachedMapSaveLoad = mapProcessorClass.getMethod("getMapSaveLoad").invoke(cachedMapProcessor);
            if (cachedMapSaveLoad == null) {
                LOGGER.warn("无法初始化反射缓存: MapSaveLoad 为空");
                return;
            }

            // 缓存常用反射方法和字段
            cachedGetLeafMapRegion = mapProcessorClass.getMethod("getLeafMapRegion", int.class, int.class, int.class, boolean.class);
            cachedRequestLoad = mapSaveLoadClass.getMethod("requestLoad", Class.forName("xaero.map.region.MapRegion"), String.class, boolean.class);

            Class<?> mapRegionClass = Class.forName("xaero.map.region.MapRegion");
            cachedLoadStateField = mapRegionClass.getDeclaredField("loadState");
            cachedLoadStateField.setAccessible(true);
            cachedCancelRefresh = mapRegionClass.getMethod("cancelRefresh", mapProcessorClass);
            cachedSetHasHadTerrain = mapRegionClass.getMethod("setHasHadTerrain");

            Class<?> leveledRegionClass = Class.forName("xaero.map.region.LeveledRegion");
            cachedShouldCacheField = leveledRegionClass.getDeclaredField("shouldCache");
            cachedShouldCacheField.setAccessible(true);

            reflectionInitialized = true;

            // 关键：设置 regionDetectionComplete = true，否则 getLeafMapRegion 会返回 null
            Method setRegionDetectionComplete = mapSaveLoadClass.getMethod("setRegionDetectionComplete", boolean.class);
            setRegionDetectionComplete.invoke(cachedMapSaveLoad, true);

            LOGGER.info("反射 API 缓存已初始化，regionDetectionComplete=true");

        } catch (Exception e) {
            LOGGER.error("初始化反射缓存失败", e);
        }
    }

    /**
     * 立即加载单个区域。
     * 使用缓存的反射 API，设置 shouldCache=true 确保加载后生成缓存。
     *
     * 视距内 region：使用 requestLoad(prioritize=true) 插入队头，优先加载
     * 视距外 region：直接添加到 toLoad 队列队尾，绕过 loadingFiles 检查
     *
     * @param coord 区域坐标
     * @param caveLayer 洞穴层编号，地表层使用 Integer.MAX_VALUE
     * @param inViewDistance 是否在视距内（用于优先级判断）
     */
    private static void triggerSingleRegionLoad(XaeroMapIntegrator.RegionCoord coord, int caveLayer, boolean inViewDistance) {
        if (!reflectionInitialized || cachedMapProcessor == null) {
            LOGGER.warn("反射缓存未初始化，无法加载区域 ({}, {}) layer={}", coord.x(), coord.z(), caveLayer);
            return;
        }

        // 避免重复加载
        if (loadedRegions.contains(coord)) {
            LOGGER.debug("区域 ({}, {}) layer={} 已加载，跳过", coord.x(), coord.z(), caveLayer);
            return;
        }

        try {
            // 获取或创建 MapRegion - 使用正确的 caveLayer
            Object mapRegion = cachedGetLeafMapRegion.invoke(cachedMapProcessor,
                caveLayer, coord.x(), coord.z(), true);
            if (mapRegion == null) {
                LOGGER.warn("无法创建 MapRegion ({}, {}) layer={}", coord.x(), coord.z(), caveLayer);
                return;
            }

            // 清除 refresh 状态
            cachedCancelRefresh.invoke(mapRegion, cachedMapProcessor);

            // 设置 shouldCache=true，确保完整加载条件满足
            cachedShouldCacheField.setBoolean(mapRegion, true);

            // 关键：设置 hasHadTerrain=true，否则 loadCacheTextures 会直接返回元数据
            // 如果 hasHadTerrain=false，加载时会跳过完整数据加载
            cachedSetHasHadTerrain.invoke(mapRegion);

            cachedLoadStateField.setByte(mapRegion, (byte) 4);
            cachedRequestLoad.invoke(cachedMapSaveLoad, mapRegion,
                    inViewDistance ? "sync view" : "sync deferred", inViewDistance);
            LOGGER.debug("Queued Xaero reload for region ({}, {}) layer={} inView={}",
                    coord.x(), coord.z(), caveLayer, inViewDistance);

            loadedRegions.add(coord);

        } catch (Exception e) {
            LOGGER.warn("立即加载区域 ({}, {}) layer={} 失败: {}", coord.x(), coord.z(), caveLayer, e.getMessage());
        }
    }

    /**
     * 清除单个区域的缓存文件。
     * 在区域立即加载前调用，确保加载最新数据。
     *
     * @param coord 区域坐标
     */
    private static void clearSingleRegionCache(XaeroMapIntegrator.RegionCoord coord) {
        clearSingleRegionCache(coord, lastMwDir);
    }

    private static void clearSingleRegionCache(XaeroMapIntegrator.RegionCoord coord, Path mwDir) {
        if (mwDir == null) return;

        String cacheFileName = coord.x() + "_" + coord.z() + ".xwmc";
        List<Path> cacheDirs = cacheDirectoriesByMwDir.computeIfAbsent(mwDir, MapPacketReceiver::findCacheDirectories);

        for (Path cacheDir : cacheDirs) {
            Path cacheFile = cacheDir.resolve(cacheFileName);
            if (cacheFile.toFile().exists()) {
                try {
                    java.nio.file.Files.deleteIfExists(cacheFile);
                    LOGGER.debug("已清除缓存: {}", cacheFile);
                } catch (Exception e) {
                    LOGGER.warn("清除缓存失败: {}", cacheFile);
                }
                return;
            }
        }
    }

    private record PendingRegionLoad(XaeroMapIntegrator.RegionCoord coord,
                                     int caveLayer,
                                     boolean inViewDistance,
                                     long generation) {
    }

    private static class IncomingRegionParts {
        final Path partFile;
        final Path outputFile;
        final int totalParts;
        final long totalBytes;
        final long timestampSeconds;
        final String hash;
        final ChunkMapData chunk;
        final BitSet received;
        long receivedBytes;

        IncomingRegionParts(Path partFile, Path outputFile, int totalParts, long totalBytes,
                long timestampSeconds, String hash, ChunkMapData chunk) {
            this.partFile = partFile;
            this.outputFile = outputFile;
            this.totalParts = totalParts;
            this.totalBytes = totalBytes;
            this.timestampSeconds = timestampSeconds;
            this.hash = hash;
            this.chunk = chunk;
            this.received = new BitSet(totalParts);
        }

        boolean matches(PacketHandler.SyncRegionPartPayload payload, XaeroMapIntegrator.RegionFileTarget target) {
            return totalParts == payload.totalParts()
                    && totalBytes == payload.totalBytes()
                    && timestampSeconds == payload.timestampSeconds()
                    && hash.equals(payload.hash())
                    && outputFile.equals(target.outputFile());
        }
    }

    /**
     * 构建时间戳缓存的服务器格式相对路径。
     *
     * @param chunk 区块数据
     * @return 相对路径字符串
     */
    private static String buildRelativePathForCache(ChunkMapData chunk) {
        String xaeroDim = chunk.dimension;
        if (chunk.caveLayer == Integer.MAX_VALUE) {
            return xaeroDim + "/" + chunk.regionX + "_" + chunk.regionZ;
        } else {
            return xaeroDim + "/caves/" + chunk.caveLayer + "/" + chunk.regionX + "_" + chunk.regionZ;
        }
    }

    /**
     * 在 mw 目录下查找所有缓存目录。
     * 缓存目录命名格式：cache、cache_1、cache_<version>。
     *
     * @param mwDir mw 目录路径
     * @return 缓存目录列表
     */
    private static java.util.List<Path> findCacheDirectories(Path mwDir) {
        java.util.List<Path> cacheDirs = new java.util.ArrayList<>();

        try {
            // Standard cache directories
            Path cache = mwDir.resolve("cache");
            Path cache1 = mwDir.resolve("cache_1");

            if (cache.toFile().exists() && cache.toFile().isDirectory()) {
                cacheDirs.add(cache);
            }
            if (cache1.toFile().exists() && cache1.toFile().isDirectory()) {
                cacheDirs.add(cache1);
            }

            // Also check for versioned cache directories (cache_<number>)
            try (java.nio.file.DirectoryStream<Path> stream = java.nio.file.Files.newDirectoryStream(mwDir, "cache_*")) {
                for (Path dir : stream) {
                    if (dir.toFile().isDirectory() && !cacheDirs.contains(dir)) {
                        cacheDirs.add(dir);
                    }
                }
            }

            LOGGER.debug("Found {} cache directories", cacheDirs.size());
        } catch (Exception e) {
            LOGGER.warn("Failed to find cache directories: {}", e.getMessage());
        }

        return cacheDirs;
    }

    /**
     * 卸载视野范围内的region以便同步当前维度时重新加载服务端数据。
     * 在同步当前维度时调用。
     *
     * @param targetDimension 目标维度（Xaero格式）
     */
    public static void prepareSyncForDimension(String targetDimension) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }

        // 检查是否同步当前维度
        String currentXaeroDim = DimensionPathMapping.getInstance().toXaeroDimension(
                mc.level.dimension().identifier().toString());

        if (targetDimension.equals(currentXaeroDim)) {
            // 同步当前维度，卸载视野范围内的region
            LOGGER.info("Syncing current dimension {}, unloading view distance regions", targetDimension);
            int unloaded = XaeroMapIntegrator.unloadViewDistanceRegions();
            if (unloaded > 0 && mc.player != null) {
                mc.player.sendSystemMessage(
                        ChatUtils.desc("mapsyncer.sync.unloading_regions", unloaded));
            }
        }
    }
}
