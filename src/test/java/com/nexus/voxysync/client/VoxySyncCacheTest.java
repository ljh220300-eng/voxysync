package com.nexus.voxysync.client;

import com.nexus.voxysync.network.VoxyPackets;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * 客户端缓存单测：保存/加载往返、维度前缀裁剪、损坏文件优雅降级。
 */
public class VoxySyncCacheTest {

    @Test
    public void saveLoadRoundTripAndSnapshotStripPrefix() throws Exception {
        Path dir = Files.createTempDirectory("vcache");
        VoxySyncCache cache = new VoxySyncCache(dir);

        cache.update("minecraft:overworld", "r.0.0.mca", 1725000000L, 12345L);
        cache.update("minecraft:overworld", "r.1.0.mca", 1725000001L, 67890L);
        cache.update("minecraft:the_nether", "r.-1.-1.mca", 1725000002L, 5L);
        cache.save();

        VoxySyncCache reloaded = new VoxySyncCache(dir);
        Map<String, VoxyPackets.RegionMeta> over = reloaded.snapshotForDimension("minecraft:overworld");
        assertEquals(2, over.size());
        assertTrue(over.containsKey("r.0.0.mca"));
        assertFalse(over.containsKey("minecraft:overworld/r.0.0.mca"));
        assertEquals(1725000000L, over.get("r.0.0.mca").timestampSeconds());
        assertEquals(12345L, over.get("r.0.0.mca").sizeBytes());

        Map<String, VoxyPackets.RegionMeta> nether = reloaded.snapshotForDimension("minecraft:the_nether");
        assertEquals(1, nether.size());
        assertTrue(nether.containsKey("r.-1.-1.mca"));

        assertTrue(reloaded.snapshotForDimension("minecraft:the_end").isEmpty());
    }

    @Test
    public void corruptCacheFileIsIgnored() throws Exception {
        Path dir = Files.createTempDirectory("vcache2");
        Files.writeString(dir.resolve("voxy-sync-cache.json"), "{ not json !!!");
        VoxySyncCache cache = new VoxySyncCache(dir); // 不应抛异常
        cache.update("minecraft:overworld", "r.0.0.mca", 1L, 2L);
        cache.save();
        VoxySyncCache again = new VoxySyncCache(dir);
        assertEquals(1, again.snapshotForDimension("minecraft:overworld").size());
    }

    @Test
    public void noCacheFileIsFine() throws Exception {
        Path dir = Files.createTempDirectory("vcache3");
        VoxySyncCache cache = new VoxySyncCache(dir);
        assertTrue(cache.snapshotForDimension("minecraft:overworld").isEmpty());
    }
}
