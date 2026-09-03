package com.nexus.voxysync;

import com.nexus.voxysync.server.VoxySyncCommand;
import com.nexus.voxysync.server.VoxySyncHandler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * VoxySync 入口（双端共用；客户端另有 {@code VoxySyncClientMod} 入口）。
 *
 * <p>本 mod 改编自 GPL-3.0 的 Jiemoy/MapSyncer-rebuild 的 Voxy 同步实现，
 * 适配 Minecraft 1.20.1（Fabric，Yarn 映射 / Identifier + PacketByteBuf 网络风格），
 * 并新增「全图 / 半径」双模式：radius（默认，仅发送玩家周围区域）与
 * all（全图，需在 config/voxysync.json 显式启用，发送完整 MCA 区域文件，
 * 可能泄露箱子/矿脉/地下结构等，仅建议在信任的服务器开启）。</p>
 */
public class VoxySyncMod implements ModInitializer {
    public static final String MOD_ID = "voxysync";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        VoxySyncConfig.load();
        VoxySyncHandler.register();
        VoxySyncCommand.register();

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                VoxySyncHandler.onPlayerDisconnect(handler.player.getUUID()));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> VoxySyncHandler.cleanup());

        VoxySyncHandler.logSecurityWarningIfEnabled();
        LOGGER.info("VoxySync initialized for Fabric (mode={}, radius={} blocks)",
                VoxySyncConfig.INSTANCE.syncMode, VoxySyncConfig.INSTANCE.radiusBlocks);
    }
}
