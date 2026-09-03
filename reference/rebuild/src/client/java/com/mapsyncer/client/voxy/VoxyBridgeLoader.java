package com.mapsyncer.client.voxy;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
                        Class<?> impl = Class.forName("com.mapsyncer.client.voxy.VoxyBridgeImpl");
                        bridge = (IVoxyBridge) impl.getDeclaredConstructor().newInstance();
                    } catch (Throwable t) {
                        LOGGER.warn("Failed to initialize Voxy bridge", t);
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
