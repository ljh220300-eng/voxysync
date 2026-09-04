package com.nexus.voxysync.client;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class VoxyImportTrackerTest {

    private static Set<String> names(String... n) {
        return Set.of(n);
    }

    @Test
    public void firstTimeAllPending() throws Exception {
        Path dir = Files.createTempDirectory("trk1");
        VoxyImportTracker t = new VoxyImportTracker(dir);
        assertFalse(t.hasManifest());
        List<String> pending = t.pending(names("r.0.0.mca", "r.1.0.mca"));
        assertEquals(2, pending.size());
    }

    @Test
    public void markAndSkipOnReload() throws Exception {
        Path dir = Files.createTempDirectory("trk2");
        VoxyImportTracker t = new VoxyImportTracker(dir);
        t.markImported(List.of("r.0.0.mca", "r.1.0.mca"));
        assertTrue(Files.exists(dir.resolve("imported-regions.json")));

        VoxyImportTracker reloaded = new VoxyImportTracker(dir);
        assertTrue(reloaded.hasManifest());
        assertTrue(reloaded.pending(names("r.0.0.mca", "r.1.0.mca", "r.2.0.mca")).equals(List.of("r.2.0.mca")));
    }

    @Test
    public void bootstrapMarksAll() throws Exception {
        Path dir = Files.createTempDirectory("trk3");
        VoxyImportTracker t = new VoxyImportTracker(dir);
        t.bootstrapAll(names("r.0.0.mca", "r.-1.-1.mca"));
        VoxyImportTracker reloaded = new VoxyImportTracker(dir);
        assertTrue(reloaded.pending(names("r.0.0.mca", "r.-1.-1.mca")).isEmpty());
    }

    @Test
    public void corruptedManifestFallsToFullPending() throws Exception {
        Path dir = Files.createTempDirectory("trk4");
        Files.writeString(dir.resolve("imported-regions.json"), "not a json at all");
        VoxyImportTracker t = new VoxyImportTracker(dir);
        assertEquals(2, t.pending(names("r.0.0.mca", "r.1.0.mca")).size());
    }
}
