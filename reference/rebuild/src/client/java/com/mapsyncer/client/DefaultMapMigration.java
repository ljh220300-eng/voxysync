package com.mapsyncer.client;

import com.mapsyncer.util.ChatUtils;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

public final class DefaultMapMigration {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultMapMigration.class);
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "mapsyncer-default-map-migration");
        thread.setDaemon(true);
        return thread;
    });
    private static final Set<Path> SCHEDULED_SERVER_DIRS = ConcurrentHashMap.newKeySet();

    private DefaultMapMigration() {
    }

    public static void schedule(Path serverDir) {
        if (serverDir == null) {
            return;
        }

        Path normalized = serverDir.toAbsolutePath().normalize();
        if (!SCHEDULED_SERVER_DIRS.add(normalized)) {
            return;
        }

        WORKER.execute(() -> migrate(normalized));
    }

    private static void migrate(Path serverDir) {
        try {
            List<Path> oldMwDirs = findOldMwDirs(serverDir);
            if (oldMwDirs.isEmpty()) {
                LOGGER.debug("No legacy Xaero mw$ map directories found under {}", serverDir);
                notifyMessage("mapsyncer.migration.skipped");
                return;
            }

            notifyMessage("mapsyncer.migration.start");
            MigrationResult result = copyLegacyRegions(oldMwDirs);
            LOGGER.info("Default map migration complete for {}: copied={}, skipped={}, failed={}",
                    serverDir, result.copied(), result.skipped(), result.failed());

            Minecraft.getInstance().execute(() -> {
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.sendSystemMessage(ChatUtils.success(
                            "mapsyncer.migration.complete",
                            result.copied(), result.skipped(), result.failed()));
                    if (!result.migratedRegions().isEmpty()) {
                        Minecraft.getInstance().player.sendSystemMessage(ChatUtils.message(
                                "mapsyncer.migration.reload", result.migratedRegions().size()));
                    }
                }
                if (!result.migratedRegions().isEmpty()) {
                    MapPacketReceiver.queueExternalRegionReloads(result.migratedRegions());
                }
            });
        } catch (Exception e) {
            LOGGER.error("Default map migration failed for {}", serverDir, e);
            notifyError("mapsyncer.migration.failed", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static List<Path> findOldMwDirs(Path serverDir) throws IOException {
        List<Path> oldMwDirs = new ArrayList<>();
        if (!Files.isDirectory(serverDir)) {
            return oldMwDirs;
        }

        try (Stream<Path> dimensions = Files.list(serverDir)) {
            for (Path dimDir : dimensions.filter(Files::isDirectory).toList()) {
                try (Stream<Path> mwDirs = Files.list(dimDir)) {
                    mwDirs.filter(Files::isDirectory)
                            .filter(DefaultMapMigration::isLegacyMwDir)
                            .forEach(oldMwDirs::add);
                } catch (IOException e) {
                    LOGGER.debug("Failed to scan dimension directory {}", dimDir, e);
                }
            }
        }
        return oldMwDirs;
    }

    private static boolean isLegacyMwDir(Path dir) {
        String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
        return name.startsWith("mw$") && !XaeroMapIntegrator.DEFAULT_MW_DIR_NAME.equals(name);
    }

    private static MigrationResult copyLegacyRegions(List<Path> oldMwDirs) {
        int copied = 0;
        int skipped = 0;
        int failed = 0;
        Set<XaeroMapIntegrator.RegionCoord> migratedRegions = new HashSet<>();

        for (Path oldMwDir : oldMwDirs) {
            Path dimDir = oldMwDir.getParent();
            if (dimDir == null) {
                continue;
            }
            Path defaultMwDir = dimDir.resolve(XaeroMapIntegrator.DEFAULT_MW_DIR_NAME);

            try (Stream<Path> files = Files.walk(oldMwDir)) {
                for (Path source : files.filter(Files::isRegularFile).toList()) {
                    String fileName = source.getFileName() == null ? "" : source.getFileName().toString();
                    if (!fileName.endsWith(".zip")) {
                        continue;
                    }

                    Path relative = oldMwDir.relativize(source);
                    XaeroMapIntegrator.RegionCoord coord = parseRegionCoord(relative);
                    if (coord == null) {
                        continue;
                    }

                    Path target = defaultMwDir.resolve(relative);
                    if (Files.exists(target)) {
                        skipped++;
                        continue;
                    }

                    try {
                        Files.createDirectories(target.getParent());
                        Files.copy(source, target);
                        copied++;
                        migratedRegions.add(coord);
                    } catch (FileAlreadyExistsException e) {
                        skipped++;
                    } catch (Exception e) {
                        failed++;
                        LOGGER.warn("Failed to copy legacy map region {} to {}", source, target, e);
                    }
                }
            } catch (IOException e) {
                failed++;
                LOGGER.warn("Failed to walk legacy map directory {}", oldMwDir, e);
            }
        }

        return new MigrationResult(copied, skipped, failed, migratedRegions);
    }

    private static XaeroMapIntegrator.RegionCoord parseRegionCoord(Path relative) {
        if (relative.getNameCount() == 1) {
            return parseRegionFileName(relative.getFileName().toString(), Integer.MAX_VALUE);
        }
        if (relative.getNameCount() == 3 && "caves".equals(relative.getName(0).toString())) {
            try {
                int caveLayer = Integer.parseInt(relative.getName(1).toString());
                return parseRegionFileName(relative.getFileName().toString(), caveLayer);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static XaeroMapIntegrator.RegionCoord parseRegionFileName(String fileName, int caveLayer) {
        if (!fileName.endsWith(".zip")) {
            return null;
        }
        String stem = fileName.substring(0, fileName.length() - 4);
        String[] parts = stem.split("_", -1);
        if (parts.length != 2) {
            return null;
        }
        try {
            return new XaeroMapIntegrator.RegionCoord(
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    caveLayer);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void notifyMessage(String key, Object... args) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.sendSystemMessage(ChatUtils.message(key, args));
            }
        });
    }

    private static void notifyError(String key, Object... args) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.sendSystemMessage(ChatUtils.error(key, args));
            }
        });
    }

    private record MigrationResult(int copied,
                                   int skipped,
                                   int failed,
                                   Set<XaeroMapIntegrator.RegionCoord> migratedRegions) {
    }
}
