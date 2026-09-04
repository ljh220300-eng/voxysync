package com.nexus.voxysync.client;

import com.nexus.voxysync.network.VoxyPackets;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * 客户端入口：注册接收器 + 进服/切维度自动同步 + actionbar 进度。
 */
public class VoxySyncClientMod implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(VoxyPackets.CAPABILITY,
                (client, handler, buf, responseSender) ->
                        VoxySyncClient.handleCapability(VoxyPackets.CapabilityPayload.decode(buf), client));
        ClientPlayNetworking.registerGlobalReceiver(VoxyPackets.SYNC_START,
                (client, handler, buf, responseSender) ->
                        VoxySyncClient.handleStart(VoxyPackets.SyncStartPayload.decode(buf), client));
        ClientPlayNetworking.registerGlobalReceiver(VoxyPackets.REGION_PART,
                (client, handler, buf, responseSender) ->
                        VoxySyncClient.handlePart(VoxyPackets.RegionPartPayload.decode(buf), client));
        ClientPlayNetworking.registerGlobalReceiver(VoxyPackets.SYNC_PROGRESS,
                (client, handler, buf, responseSender) ->
                        VoxySyncClient.handleProgress(VoxyPackets.SyncProgressPayload.decode(buf), client));
        ClientPlayNetworking.registerGlobalReceiver(VoxyPackets.SYNC_COMPLETE,
                (client, handler, buf, responseSender) ->
                        VoxySyncClient.handleComplete(VoxyPackets.SyncCompletePayload.decode(buf), client));
        ClientPlayNetworking.registerGlobalReceiver(VoxyPackets.REQUEST_SYNC,
                (client, handler, buf, responseSender) ->
                        VoxySyncClient.handleRequestSync(VoxyPackets.RequestSyncPayload.decode(buf), client));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                VoxySyncClient.onWorldAvailable(client));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, sender) ->
                VoxySyncClient.onDisconnect());
        ClientTickEvents.END_CLIENT_TICK.register(VoxySyncClient::onClientTick);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client != null && client.level != null && client.player != null) {
                VoxySyncClient.onWorldAvailable(client);
            }
        });

        // /voxystop —— 手动中止下载并立即渲染已下载部分
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(literal("voxystop")
                        .executes(ctx -> {
                            VoxySyncClient.requestStop(ctx.getSource().getClient());
                            return 1;
                        })));
    }

}
