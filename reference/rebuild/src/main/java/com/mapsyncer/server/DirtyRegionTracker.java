package com.mapsyncer.server;

import com.mapsyncer.config.ModConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class DirtyRegionTracker {
    private static final ConcurrentHashMap<DirtyRegionKey, DirtyRegion> DIRTY_REGIONS = new ConcurrentHashMap<>();

    private DirtyRegionTracker() {
    }

    public static void markDirty(ServerLevel level, BlockPos pos) {
        if (!ModConfig.SERVER.enableDirtyRegionTracking || level == null || pos == null) {
            return;
        }

        ChunkPos chunkPos = ChunkPos.containing(pos);
        ResourceKey<Level> dimension = level.dimension();
        int regionX = chunkPos.getRegionX();
        int regionZ = chunkPos.getRegionZ();
        long now = System.currentTimeMillis();
        DirtyRegionKey key = new DirtyRegionKey(dimension, regionX, regionZ);
        DIRTY_REGIONS.compute(key, (ignored, existing) ->
                new DirtyRegion(dimension, regionX, regionZ, now));
    }

    public static List<DirtyRegion> takeSnapshot(int maxCount) {
        if (maxCount <= 0 || DIRTY_REGIONS.isEmpty()) {
            return List.of();
        }

        List<DirtyRegion> snapshot = new ArrayList<>(Math.min(maxCount, DIRTY_REGIONS.size()));
        for (DirtyRegion region : DIRTY_REGIONS.values()) {
            snapshot.add(region);
            if (snapshot.size() >= maxCount) {
                break;
            }
        }
        return snapshot;
    }

    public static void markProcessed(DirtyRegion region) {
        if (region == null) {
            return;
        }
        DIRTY_REGIONS.remove(region.key(), region);
    }

    public static void clear() {
        DIRTY_REGIONS.clear();
    }

    public static boolean hasDirtyRegions() {
        return !DIRTY_REGIONS.isEmpty();
    }

    public static int dirtyCount() {
        return DIRTY_REGIONS.size();
    }

    private record DirtyRegionKey(ResourceKey<Level> dimension, int regionX, int regionZ) {
    }

    public record DirtyRegion(ResourceKey<Level> dimension, int regionX, int regionZ, long latestDirtyAtMillis) {
        public boolean matches(ResourceKey<Level> dimension) {
            return Objects.equals(this.dimension, dimension);
        }

        public RegionScanner.RegionCoords toRegionCoords() {
            return new RegionScanner.RegionCoords(regionX, regionZ);
        }

        private DirtyRegionKey key() {
            return new DirtyRegionKey(dimension, regionX, regionZ);
        }
    }
}
