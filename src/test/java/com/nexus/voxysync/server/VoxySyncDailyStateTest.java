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

        assertFalse(st.isDoneToday(id, today));
        st.markDone(id, today);
        assertTrue(st.isDoneToday(id, today));
        assertFalse(st.isDoneToday(id, today.plusDays(1)));

        // 重载持久化
        VoxySyncDailyState reloaded = new VoxySyncDailyState(dir.resolve("daily.json"));
        assertTrue(reloaded.isDoneToday(id, today));
        assertFalse(reloaded.isDoneToday(id, today.minusDays(1)));
    }

    @Test
    public void differentPlayersIndependent() throws Exception {
        Path dir = Files.createTempDirectory("daily2");
        VoxySyncDailyState st = new VoxySyncDailyState(dir.resolve("daily.json"));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        st.markDone(a, LocalDate.of(2026, 9, 4));
        assertTrue(st.isDoneToday(a, LocalDate.of(2026, 9, 4)));
        assertFalse(st.isDoneToday(b, LocalDate.of(2026, 9, 4)));
    }

    @Test
    public void corruptFileIgnored() throws Exception {
        Path dir = Files.createTempDirectory("daily3");
        Files.writeString(dir.resolve("daily.json"), "not-json");
        VoxySyncDailyState st = new VoxySyncDailyState(dir.resolve("daily.json"));
        assertFalse(st.isDoneToday(UUID.randomUUID(), LocalDate.of(2026, 9, 4)));
    }
}
