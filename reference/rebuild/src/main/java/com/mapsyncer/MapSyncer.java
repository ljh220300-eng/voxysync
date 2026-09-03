package com.mapsyncer;

import com.mapsyncer.config.ModConfig;
import com.mapsyncer.config.ModConfig.UpdateMode;
import com.mapsyncer.server.CacheGenerateCommand;
import com.mapsyncer.server.DimensionRegistry;
import com.mapsyncer.server.DirtyRegionTracker;
import com.mapsyncer.server.IncrementalUpdateHandler;
import com.mapsyncer.server.PlayerJoinHandler;
import com.mapsyncer.server.PublicWaypointConfig;
import com.mapsyncer.server.ServerSyncHandler;
import com.mapsyncer.server.VoxySyncHandler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MapSyncer implements ModInitializer {
    public static final String MOD_ID = "mapsyncer";
    public static String VERSION = "unknown";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        VERSION = FabricLoader.getInstance()
                .getModContainer(MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");

        ModConfig.load();
        PublicWaypointConfig.load();
        ServerSyncHandler.register();
        VoxySyncHandler.register();
        VoxySyncHandler.logSecurityWarningIfEnabled();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                CacheGenerateCommand.register(dispatcher));

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            DimensionRegistry.registerAllDimensions(server);

            UpdateMode mode = ModConfig.SERVER.incrementalUpdateMode;
            if (mode != UpdateMode.DISABLED) {
                IncrementalUpdateHandler.getInstance().start(server);
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            IncrementalUpdateHandler.getInstance().stop();
            VoxySyncHandler.cleanup();
            DirtyRegionTracker.clear();
        });

        ServerLifecycleEvents.SERVER_STOPPED.register(PlayerJoinHandler::onServerStopped);

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            IncrementalUpdateHandler.onServerTick(server);
            PlayerJoinHandler.onServerTick(server);
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                PlayerJoinHandler.onPlayerJoin(handler.player));

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                PlayerJoinHandler.onPlayerLeave(handler.player));

        LOGGER.info("MapSyncer initialized for Fabric");
    }
}
