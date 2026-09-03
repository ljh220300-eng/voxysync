package com.nexus.voxysync.client.voxy;

import net.minecraft.client.Minecraft;

import java.nio.file.Path;

/**
 * Voxy 桥接接口（保留可替换接口：将来 Voxy 官方支持 1.20.1 或改版时只需换实现）。
 * 改编自 MapSyncer-rebuild（GPL-3.0）。
 */
public interface IVoxyBridge {
    boolean isAvailable(Minecraft client);

    boolean startImport(Minecraft client, Path regionDirectory) throws Exception;
}
