package com.mapsyncer.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public final class AutoSyncManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(AutoSyncManager.class);
    private static volatile boolean scheduled = false;

    private AutoSyncManager() {
    }

    public static void onServerInstalled() {
        if (!ClientConfig.VALUES.autoSyncOnJoin || scheduled || MapPacketReceiver.isSyncInProgress()) {
            return;
        }

        scheduled = true;
        int delaySeconds = Math.max(1, ClientConfig.VALUES.autoSyncDelaySeconds);
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(delaySeconds * 1000L);
                Minecraft mc = Minecraft.getInstance();
                if (mc.player == null || mc.level == null || !MapPacketReceiver.isServerInstalled()) {
                    return;
                }

                Path serverDir = XaeroMapIntegrator.getCurrentServerDirectory();
                if (serverDir != null && serverDir.toFile().exists()) {
                    ClientTimestampCache tsCache = ClientTimestampCache.getInstance(serverDir);
                    if (tsCache.needsResume()) {
                        runSavedCommand(mc, tsCache.getSyncCommand());
                        return;
                    }
                }

                Level level = mc.level;
                String dimensionId = level.dimension().identifier().toString();
                mc.execute(() -> MapSyncerCommand.sendSyncRequest(mc, dimensionId, false));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                scheduled = false;
            }
        }, "MapSyncer-AutoSync");
        thread.setDaemon(true);
        thread.start();
    }

    public static void reset() {
        scheduled = false;
    }

    private static void runSavedCommand(Minecraft mc, String command) {
        if (command == null || command.isBlank()) {
            return;
        }

        mc.execute(() -> {
            if (mc.player == null) {
                return;
            }
            String normalized = command.startsWith("/") ? command.substring(1) : command;
            LOGGER.info("Auto-resuming sync command: /{}", normalized);
            mc.player.connection.sendCommand(normalized);
        });
    }
}
