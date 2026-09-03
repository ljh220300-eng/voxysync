package com.nexus.voxysync.client.voxy;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Voxy 桥接加载器：Voxy 缺失时优雅降级（不抛异常，仅返回 null）。
 * 改编自 MapSyncer-rebuild（GPL-3.0）。
 */
public final class VoxyBridgeLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger(VoxyBridgeLoader.class);
    private static volatile boolean attempted;
    private static volatile IVoxyBridge bridge;

    private VoxyBridgeLoader() {
    }

    public static boolean isVoxyInstalled() {
        return FabricLoader.getInstance().isModLoaded("voxy");
    }

    public static IVoxyBridge getBridge() {
        if (!isVoxyInstalled()) {
            return null;
        }
        if (!attempted) {
            synchronized (VoxyBridgeLoader.class) {
                if (!attempted) {
                    attempted = true;
                    try {
                        Class<?> impl = Class.forName("com.nexus.voxysync.client.voxy.VoxyBridgeImpl");
                        bridge = (IVoxyBridge) impl.getDeclaredConstructor().newInstance();
                    } catch (Throwable t) {
                        LOGGER.warn("初始化 Voxy 桥接失败", t);
                        bridge = null;
                    }
                }
            }
        }
        return bridge;
    }

    public static boolean isVoxyReady(Minecraft client) {
        IVoxyBridge loaded = getBridge();
        return loaded != null && loaded.isAvailable(client);
    }
}
