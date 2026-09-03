package com.nexus.voxysync.client;

import com.nexus.voxysync.network.VoxyPackets;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.*;

/**
 * 客户端纯逻辑单测：区域分片拼装（乱序/重复/越界/大小校验）与元数据分块。
 * 无 GPU、无 MC 客户端运行时依赖（仅 JDK + 本 mod 逻辑类）。
 */
public class VoxySyncClientLogicTest {

    private static Map<String, VoxyPackets.RegionMeta> meta(int n) {
        Map<String, VoxyPackets.RegionMeta> m = new HashMap<>();
        for (int i = 0; i < n; i++) {
            m.put("r." + i + ".0.mca", new VoxyPackets.RegionMeta(1725000000L + i, 1000L * i));
        }
        return m;
    }

    @Test
    public void chunkSmallMetaIsSingleChunk() {
        List<Map<String, VoxyPackets.RegionMeta>> chunks = VoxySyncClient.chunkMetaForRequest(meta(1));
        assertEquals(1, chunks.size());
        assertEquals(1, chunks.get(0).size());
    }

    @Test
    public void chunkEmptyMetaIsSingleEmptyChunk() {
        List<Map<String, VoxyPackets.RegionMeta>> chunks = VoxySyncClient.chunkMetaForRequest(new HashMap<>());
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).isEmpty());
    }

    @Test
    public void chunkSplitsAtMaxEntries() {
        List<Map<String, VoxyPackets.RegionMeta>> chunks =
                VoxySyncClient.chunkMetaForRequest(meta(VoxyPackets.MAX_ENTRIES_PER_CHUNK + 7));
        assertEquals(2, chunks.size());
        assertEquals(VoxyPackets.MAX_ENTRIES_PER_CHUNK, chunks.get(0).size());
        assertEquals(7, chunks.get(1).size());
        // 无丢无重
        int total = chunks.stream().mapToInt(Map::size).sum();
        assertEquals(VoxyPackets.MAX_ENTRIES_PER_CHUNK + 7, total);
    }

    @Test
    public void assemblyReassemblesOutOfOrderBytes() throws Exception {
        byte[] file = new byte[100000];
        new Random(7).nextBytes(file);
        int parts = 10;
        int piece = file.length / parts;
        VoxySyncClient.RegionAssembly a = new VoxySyncClient.RegionAssembly(parts, file.length);
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("va");
        java.nio.file.Path partPath = dir.resolve("r.1.1.mca.part");
        // 倒序写入
        for (int i = parts - 1; i >= 0; i--) {
            byte[] p = new byte[piece];
            System.arraycopy(file, i * piece, p, 0, piece);
            a.writePart(partPath, i, (long) i * piece, p);
        }
        assertTrue(a.isComplete());
        java.nio.file.Path out = dir.resolve("r.1.1.mca");
        java.nio.file.Files.move(partPath, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertArrayEquals(file, java.nio.file.Files.readAllBytes(out));
    }

    @Test
    public void assemblyIgnoresDuplicatePart() throws Exception {
        byte[] file = new byte[100];
        new Random(8).nextBytes(file);
        VoxySyncClient.RegionAssembly a = new VoxySyncClient.RegionAssembly(2, file.length);
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("va2");
        java.nio.file.Path partPath = dir.resolve("r.0.0.mca.part");
        a.writePart(partPath, 0, 0, java.util.Arrays.copyOfRange(file, 0, 50));
        a.writePart(partPath, 0, 0, new byte[]{1, 2, 3}); // 重复块应被忽略
        a.writePart(partPath, 1, 50, java.util.Arrays.copyOfRange(file, 50, 100));
        assertTrue(a.isComplete());
        java.nio.file.Files.move(partPath, dir.resolve("r.0.0.mca"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertArrayEquals(file, java.nio.file.Files.readAllBytes(dir.resolve("r.0.0.mca")));
    }

    @Test(expected = java.io.IOException.class)
    public void assemblyRejectsOutOfBounds() throws Exception {
        VoxySyncClient.RegionAssembly a = new VoxySyncClient.RegionAssembly(2, 100);
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("va3");
        a.writePart(dir.resolve("r.0.0.mca.part"), 3, 0, new byte[10]); // partIndex 越界
    }

    @Test(expected = java.io.IOException.class)
    public void assemblyRejectsMetaChange() throws Exception {
        VoxySyncClient.RegionAssembly a = new VoxySyncClient.RegionAssembly(2, 100);
        assertFalse(a.matches(3, 100));
        assertFalse(a.matches(2, 101));
        assertTrue(a.matches(2, 100));
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("va4");
        a.writePart(dir.resolve("r.0.0.mca.part"), 0, 0, new byte[80]);
        a.writePart(dir.resolve("r.0.0.mca.part"), 1, 80, new byte[30]); // 超 totalBytes
    }

    @Test(expected = java.io.IOException.class)
    public void assemblyRejectsEmptyPartWhenExpectedBytes() throws Exception {
        VoxySyncClient.RegionAssembly a = new VoxySyncClient.RegionAssembly(1, 100);
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("va5");
        a.writePart(dir.resolve("r.0.0.mca.part"), 0, 0, new byte[0]);
    }
}
