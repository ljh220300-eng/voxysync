package com.mapsyncer.client;

import com.mapsyncer.network.PacketHandler;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

final class AdminStatusClientState {
    private static volatile PacketHandler.AdminStatusPayload lastStatus;
    private static volatile String lastError = "";
    private static volatile Boolean draftRadiusEnabled;
    private static volatile Integer draftMaxRadius;
    private static volatile String draftRadiusMode;

    private AdminStatusClientState() {
    }

    static PacketHandler.AdminStatusPayload getLastStatus() {
        return lastStatus;
    }

    static String getLastError() {
        return lastError;
    }

    static boolean requestNow() {
        try {
            if (!ClientPlayNetworking.canSend(PacketHandler.AdminStatusRequestPayload.TYPE)) {
                lastError = "unsupported";
                return false;
            }
            ClientPlayNetworking.send(new PacketHandler.AdminStatusRequestPayload());
            lastError = "";
            return true;
        } catch (IllegalArgumentException | IllegalStateException e) {
            lastError = "unavailable";
            return false;
        }
    }

    static void handle(PacketHandler.AdminStatusPayload payload, ClientPlayNetworking.Context context) {
        context.client().execute(() -> {
            lastStatus = payload;
            lastError = "";
            draftRadiusEnabled = null;
            draftMaxRadius = null;
            draftRadiusMode = null;
        });
    }

    static boolean hasDraft() {
        return draftRadiusEnabled != null || draftMaxRadius != null || draftRadiusMode != null;
    }

    static void setDraftRadiusEnabled(boolean enabled) {
        draftRadiusEnabled = enabled;
    }

    static boolean getDraftRadiusEnabled(boolean fallback) {
        return draftRadiusEnabled != null ? draftRadiusEnabled : fallback;
    }

    static void setDraftMaxRadius(int value) {
        draftMaxRadius = value;
    }

    static int getDraftMaxRadius(int fallback) {
        return draftMaxRadius != null ? draftMaxRadius : fallback;
    }

    static void setDraftRadiusMode(String value) {
        draftRadiusMode = value;
    }

    static String getDraftRadiusMode(String fallback) {
        return draftRadiusMode != null ? draftRadiusMode : fallback;
    }

    static void sendSettingsUpdate() {
        PacketHandler.AdminStatusPayload status = lastStatus;
        if (status == null) {
            return;
        }
        ClientPlayNetworking.send(new PacketHandler.AdminSettingsUpdatePayload(
                getDraftRadiusEnabled(status.radiusSyncEnabled()),
                getDraftMaxRadius(status.maxRadiusSyncBlocks()),
                getDraftRadiusMode(status.radiusSyncCenterMode()),
                status.radiusSyncFixedDimension(),
                status.radiusSyncFixedX(),
                status.radiusSyncFixedY(),
                status.radiusSyncFixedZ()
        ));
    }

    static void reset() {
        lastStatus = null;
        lastError = "";
        draftRadiusEnabled = null;
        draftMaxRadius = null;
        draftRadiusMode = null;
    }
}
