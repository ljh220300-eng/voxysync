package com.nexus.voxysync.server;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.Assert.*;

public class VoxySyncDailyStateTest {

    @Test
    public void markDoneBlocksSameDayAndUnlocksNextDay() throws Exception {
        Path dir = Files.createTempDirectory("daily1");
        VoxySyncDailyState st = new VoxySyncDailyState(dir.resolve("daily.json"));
        UUID id = UUID.randomUUID();
        LocalDate today = LocalDate.of(2026, 9, 4);
        String dim = "minecraft:overworld";

        assertFalse(st.isDoneToday(id, dim, today));
        st.markDone(id, dim, today);
        assertTrue(st.isDoneToday(id, dim, today));
        assertFalse(st.isDoneToday(id, dim, today.plusDays(1)));

        VoxySyncDailyState reloaded = new VoxySyncDailyState(dir.resolve("daily.json"));
        assertTrue(reloaded.isDoneToday(id, dim, today));
    }

    @Test
    public void dimensionsIndependent() throws Exception {
        Path dir = Files.createTempDirectory("daily2");
        VoxySyncDailyState st = new VoxySyncDailyState(dir.resolve("daily.json"));
        UUID id = UUID.randomUUID();
        LocalDate today = LocalDate.of(2026, 9, 4);
        st.markDone(id, "minecraft:overworld", today);
        assertTrue(st.isDoneToday(id, "minecraft:overworld", today));
        // 换维度不互锁
        assertFalse(st.isDoneToday(id, "minecraft:the_nether", today));
        st.markDone(id, "minecraft:the_nether", today);
        assertTrue(st.isDoneToday(id, "minecraft:the_nether", today));
    }

    @Test
    public void differentPlayersIndependent() throws Exception {
        Path dir = Files.createTempDirectory("daily3");
        VoxySyncDailyState st = new VoxySyncDailyState(dir.resolve("daily.json"));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        st.markDone(a, "minecraft:overworld", LocalDate.of(2026, 9, 4));
        assertTrue(st.isDoneToday(a, "minecraft:overworld", LocalDate.of(2026, 9, 4)));
        assertFalse(st.isDoneToday(b, "minecraft:overworld", LocalDate.of(2026, 9, 4)));
    }

    @Test
    public void oldFormatEntriesIgnored() throws Exception {
        Path dir = Files.createTempDirectory("daily4");
        Files.writeString(dir.resolve("daily.json"),
                "{\"" + UUID.randomUUID() + "\": \"2026-09-04\"}");
        VoxySyncDailyState st = new VoxySyncDailyState(dir.resolve("daily.json"));
        assertFalse(st.isDoneToday(UUID.randomUUID(), "minecraft:overworld", LocalDate.of(2026, 9, 4)));
    }

    @Test
    public void corruptFileIgnored() throws Exception {
        Path dir = Files.createTempDirectory("daily5");
        Files.writeString(dir.resolve("daily.json"), "not-json");
        VoxySyncDailyState st = new VoxySyncDailyState(dir.resolve("daily.json"));
        assertFalse(st.isDoneToday(UUID.randomUUID(), "minecraft:overworld", LocalDate.of(2026, 9, 4)));
    }
}
