package com.mapsyncer.client;

import com.mapsyncer.MapSyncer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class SyncHudOverlay {
    private static final Identifier ID = Identifier.fromNamespaceAndPath(MapSyncer.MOD_ID, "sync_progress");
    private static final long COMPLETE_VISIBLE_MS = 2000;

    private SyncHudOverlay() {
    }

    public static void register() {
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, ID, SyncHudOverlay::render);
    }

    private static void render(GuiGraphicsExtractor graphics, net.minecraft.client.DeltaTracker deltaTracker) {
        if (!ClientConfig.VALUES.showSyncHud) {
            return;
        }

        boolean tracking = SyncProgressTracker.isTracking();
        long completedAt = SyncProgressTracker.getCompletedAt();
        long now = System.currentTimeMillis();
        if (!tracking && (completedAt <= 0 || now - completedAt > COMPLETE_VISIBLE_MS)) {
            return;
        }

        float alpha = 1.0F;
        if (!tracking) {
            alpha = Math.max(0.0F, 1.0F - ((now - completedAt) / (float) COMPLETE_VISIBLE_MS));
        }

        Minecraft mc = Minecraft.getInstance();
        int width = 160;
        int height = 24;
        int x = graphics.guiWidth() - width - 12;
        int y = 12;
        int barX = x + 8;
        int barY = y + 15;
        int barWidth = width - 16;
        int percent = tracking ? SyncProgressTracker.getPercent() : 100;
        int filled = Math.max(0, Math.min(barWidth, (barWidth * percent) / 100));

        graphics.fill(x, y, x + width, y + height, withAlpha(0xCC101418, alpha));
        graphics.outline(x, y, width, height, withAlpha(0xFF5BC0EB, alpha));
        graphics.fill(barX, barY, barX + barWidth, barY + 5, withAlpha(0xFF2E3740, alpha));
        graphics.fill(barX, barY, barX + filled, barY + 5, withAlpha(0xFF5BC0EB, alpha));

        String title = Component.translatable("mapsyncer.hud.syncing").getString();
        if (!tracking) {
            title = Component.translatable("mapsyncer.hud.completed").getString();
        }
        String label = title + " " + percent + "%";
        graphics.text(mc.font, label, x + 8, y + 5, withAlpha(0xFFFFFFFF, alpha), false);
    }

    private static int withAlpha(int argb, float alpha) {
        int sourceAlpha = (argb >>> 24) & 0xFF;
        int scaledAlpha = Math.max(0, Math.min(255, Math.round(sourceAlpha * alpha)));
        return (argb & 0x00FFFFFF) | (scaledAlpha << 24);
    }
}
