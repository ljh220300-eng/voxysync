package com.mapsyncer.client.voxy;

import net.minecraft.client.Minecraft;

import java.nio.file.Path;

public interface IVoxyBridge {
    boolean isAvailable(Minecraft client);

    boolean startImport(Minecraft client, Path regionDirectory) throws Exception;
}
