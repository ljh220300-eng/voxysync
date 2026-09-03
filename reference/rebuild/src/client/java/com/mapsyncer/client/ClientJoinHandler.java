package com.mapsyncer.client;

import com.mapsyncer.network.PacketHandler;
import com.mapsyncer.util.ChatUtils;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * 客户端玩家加入事件处理器 - 检测未完成的同步并提示断点续传
 */
public class ClientJoinHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientJoinHandler.class);

    public static void onClientJoin(Minecraft client) {
        LOGGER.info("Player joining server, checking sync state...");

        Minecraft mc = client;
        if (mc.player == null) {
            LOGGER.warn("Player is null during join event");
            return;
        }

        new Thread(() -> {
            try {
                Thread.sleep(1000);

                Path serverDir = XaeroMapIntegrator.getCurrentServerDirectory();
                if (serverDir == null || !serverDir.toFile().exists()) {
                    LOGGER.debug("Server directory not found, skipping sync state check");
                    return;
                }

                ClientTimestampCache tsCache = ClientTimestampCache.getInstance(serverDir);
                if (!tsCache.cacheFileExists()) {
                    LOGGER.debug("No timestamp cache found, first time joining this server");
                    return;
                }

                if (tsCache.needsResume()) {
                    LOGGER.info("Detected incomplete sync from previous session");

                    mc.execute(() -> {
                        if (mc.player != null) {
                            MutableComponent message = ChatUtils.prefix()
                                    .append(Component.translatable("mapsyncer.sync.resume_prompt")
                                            .setStyle(Style.EMPTY.withColor(0xFFFF55)));

                            Component resumeButton = Component.literal(" [" +
                                    Component.translatable("mapsyncer.sync.resume_button").getString() + "]")
                                    .setStyle(Style.EMPTY
                                            .withColor(0x55FF55)
                                            .withClickEvent(new ClickEvent.RunCommand(
                                                    tsCache.getSyncCommand()))
                                            .withHoverEvent(new HoverEvent.ShowText(
                                                    Component.translatable("mapsyncer.sync.resume_hover"))));

                            mc.player.sendSystemMessage(message.append(resumeButton));
                        }
                    });
                }
            } catch (InterruptedException e) {
                LOGGER.error("Interrupted while checking sync state", e);
            }
        }, "MapSyncer-SyncStateCheck").start();
    }

    public static void onClientDisconnect(Minecraft client) {
        LOGGER.info("Player disconnecting from server");
        MapPacketReceiver.resetServerStatus();
        ClientTimestampCache.saveCurrent();
    }

    public static void handleServerInstalled(PacketHandler.ServerInstalledPayload payload,
            ClientPlayNetworking.Context context) {
        LOGGER.info("Server has MapSyncer installed, version: {}", payload.version());
    }

    public static void clearSyncState() {
        Minecraft mc = Minecraft.getInstance();
        Path serverDir = XaeroMapIntegrator.getCurrentServerDirectory();
        if (serverDir == null || !serverDir.toFile().exists()) return;

        ClientTimestampCache tsCache = ClientTimestampCache.getInstance(serverDir);
        tsCache.clearSyncState();
        if (mc.player != null) {
            mc.player.sendSystemMessage(ChatUtils.success("mapsyncer.sync.state_cleared"));
        }
    }
}
