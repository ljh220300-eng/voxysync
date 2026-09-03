package com.mapsyncer.client;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mapsyncer.network.ClientMeta;
import com.mapsyncer.network.PacketHandler;
import com.mapsyncer.util.ChatUtils;
import com.mapsyncer.util.DimensionPathMapping;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * 地图同步命令处理器。
 * 注册客户端命令 `/mapsyncer`，提供地图同步功能。
 *
 * <p>命令结构：</p>
 * <ul>
 *   <li>/mapsyncer - 显示帮助信息</li>
 *   <li>/mapsyncer help - 显示帮助信息</li>
 *   <li>/mapsyncer sync - 同步当前维度</li>
 *   <li>/mapsyncer sync all - 同步所有维度</li>
 *   <li>/mapsyncer sync &lt;dimension&gt; - 同步指定维度</li>
 * </ul>
 *
 * <p>维度参数支持：</p>
 * <ul>
 *   <li>原版维度：overworld、the_nether、the_end</li>
 *   <li>模组维度：使用完整的维度ID（如 twilightforest:twilight_forest）</li>
 * </ul>
 */
public class MapSyncerCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(MapSyncerCommand.class);

    /**
     * 注册客户端命令。
     * 使用 Brigadier 命令系统注册 /mapsyncer 命令及其子命令。
     *
     * @param dispatcher 命令调度器
     */
    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
                literal("mapsyncer")
                        .executes(MapSyncerCommand::showHelp)
                        .then(literal("help")
                                .executes(MapSyncerCommand::showHelp))
                        .then(literal("sync")
                                .executes(MapSyncerCommand::executeSyncCurrentDim)
                                .then(literal("radius")
                                        .then(argument("blocks", IntegerArgumentType.integer(1))
                                                .executes(MapSyncerCommand::executeSyncRadius)))
                                .then(literal("all")
                                        .executes(MapSyncerCommand::executeSyncAll))
                                .then(argument("dimension", StringArgumentType.greedyString())
                                        .suggests(MapSyncerCommand::suggestDimensions)
                                        .executes(MapSyncerCommand::executeSyncDimension)))
                        .then(literal("gui")
                                .executes(MapSyncerCommand::openGui))
                        .then(literal("clearstate")
                                .requires(source -> false)
                                .executes(MapSyncerCommand::clearSyncState))
        );
        dispatcher.register(
                literal("mapsyncergui")
                        .executes(MapSyncerCommand::openGui)
        );
    }

    private static int openGui(CommandContext<FabricClientCommandSource> context) {
        Minecraft mc = context.getSource().getClient();
        mc.execute(() -> mc.setScreen(new MapSyncerScreen()));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * 显示命令帮助信息。
     *
     * @param context 命令上下文
     * @return 命令执行结果
     */
    private static int showHelp(CommandContext<FabricClientCommandSource> context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;

        // 客户端同步命令
        mc.player.sendSystemMessage(ChatUtils.prefix().append(ChatUtils.header("mapsyncer.command.help_header")));
        mc.player.sendSystemMessage(ChatUtils.desc("mapsyncer.command.help_sync"));
        mc.player.sendSystemMessage(ChatUtils.desc("mapsyncer.command.help_sync_radius"));
        mc.player.sendSystemMessage(ChatUtils.desc("mapsyncer.command.help_sync_dim"));
        mc.player.sendSystemMessage(ChatUtils.desc("mapsyncer.command.help_sync_all"));
        mc.player.sendSystemMessage(ChatUtils.desc("mapsyncer.command.help_gui"));
        mc.player.sendSystemMessage(ChatUtils.header("mapsyncer.command.help_dimension_note"));

        // 如果玩家有OP权限，显示服务端命令
        if (net.minecraft.commands.Commands.LEVEL_OWNERS.check(context.getSource().getPlayer().permissions())) {
            mc.player.sendSystemMessage(ChatUtils.prefix().append(ChatUtils.header("mapsyncer.help.server.header")));
            mc.player.sendSystemMessage(ChatUtils.desc("mapsyncer.help.server.generate"));
            mc.player.sendSystemMessage(ChatUtils.desc("mapsyncer.help.server.generate_dim"));
            mc.player.sendSystemMessage(ChatUtils.desc("mapsyncer.help.server.generate_region"));
            mc.player.sendSystemMessage(ChatUtils.desc("mapsyncer.help.server.generate_force"));
            mc.player.sendSystemMessage(ChatUtils.desc("mapsyncer.help.server.status"));
            mc.player.sendSystemMessage(ChatUtils.desc("mapsyncer.help.server.incremental_off"));
            mc.player.sendSystemMessage(ChatUtils.desc("mapsyncer.help.server.incremental_tick"));
            mc.player.sendSystemMessage(ChatUtils.desc("mapsyncer.help.server.incremental_scheduled"));
        }

        return Command.SINGLE_SUCCESS;
    }

    /**
     * 提供维度名称建议。
     * 包括原版维度、模组维度以及已存在的 Xaero 目录维度。
     *
     * @param context 命令上下文
     * @param builder 建议构建器
     * @return 建议结果的 CompletableFuture
     */
    private static CompletableFuture<Suggestions> suggestDimensions(CommandContext<FabricClientCommandSource> context, SuggestionsBuilder builder) {
        builder.suggest("overworld");
        builder.suggest("the_nether");
        builder.suggest("the_end");
        builder.suggest("all");

        Set<String> added = new HashSet<>();

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level != null) {
            ResourceKey<Level> currentDim = level.dimension();
            Identifier currentLoc = currentDim.identifier();
            if (!"minecraft".equals(currentLoc.getNamespace())) {
                String suggestion = currentLoc.toString();
                builder.suggest(suggestion);
                added.add(suggestion);
            }

            level.registryAccess().lookup(Registries.DIMENSION_TYPE).ifPresent(registry -> {
                for (var key : registry.registryKeySet()) {
                    Identifier loc = key.identifier();
                    String namespace = loc.getNamespace();
                    if ("minecraft".equals(namespace)) continue;

                    String path = loc.getPath();
                    String dimPath = path.endsWith("_type") ? path.substring(0, path.length() - 5) : path;
                    String suggestion = namespace + ":" + dimPath;
                    if (!added.contains(suggestion)) {
                        builder.suggest(suggestion);
                        added.add(suggestion);
                    }
                }
            });

            level.registryAccess().lookup(Registries.LEVEL_STEM).ifPresent(registry -> {
                for (var key : registry.registryKeySet()) {
                    Identifier loc = key.identifier();
                    String namespace = loc.getNamespace();
                    if ("minecraft".equals(namespace)) continue;
                    String suggestion = loc.toString();
                    if (!added.contains(suggestion)) {
                        builder.suggest(suggestion);
                        added.add(suggestion);
                    }
                }
            });
        }

        Path baseDir = XaeroMapIntegrator.getCurrentServerBaseDirectory();
        if (baseDir != null) {
            try (Stream<Path> dirs = Files.list(baseDir)) {
                dirs.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("mw$"))
                    .forEach(p -> {
                        String dirName = p.getFileName().toString();
                        String suggestion = xaeroDirToDimensionId(dirName);
                        if (suggestion != null && !suggestion.isEmpty() && !added.contains(suggestion)) {
                            builder.suggest(suggestion);
                            added.add(suggestion);
                        }
                    });
            } catch (IOException e) {
                LOGGER.debug("Failed to scan Xaero directory", e);
            }
        }

        return builder.buildFuture();
    }

    /**
     * 将 Xaero 目录名转换为维度 ID。
     * 处理原版维度和模组维度的转换。
     *
     * @param dirName Xaero 目录名
     * @return 维度 ID，如果无法转换返回空字符串
     */
    private static String xaeroDirToDimensionId(String dirName) {
        if ("null".equals(dirName)) return "overworld";
        if ("DIM-1".equals(dirName)) return "the_nether";
        if ("DIM1".equals(dirName)) return "the_end";
        if (dirName.contains("$")) return dirName.replace('$', ':');
        if (dirName.startsWith("DIM")) return "";
        return dirName;
    }

    /**
     * 同步指定维度（字符串参数）。
     * 支持维度名称简写和完整 ID。
     *
     * @param context 命令上下文
     * @return 命令执行结果
     */
    private static int executeSyncDimension(CommandContext<FabricClientCommandSource> context) {
        String dimInput = StringArgumentType.getString(context, "dimension");

        if ("all".equalsIgnoreCase(dimInput)) {
            return executeSyncAll(context);
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return 0;

        String dimensionId = resolveDimensionId(dimInput, mc.level);

        sendSyncRequest(mc, dimensionId, false);

        return Command.SINGLE_SUCCESS;
    }

    /**
     * 同步所有维度。
     * 向服务端请求所有维度的地图数据。
     *
     * @param context 命令上下文
     * @return 命令执行结果
     */
    private static int executeSyncAll(CommandContext<FabricClientCommandSource> context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;

        sendSyncRequest(mc, "all", true);

        return Command.SINGLE_SUCCESS;
    }

    /**
     * 同步当前维度。
     * 自动检测玩家当前所在维度并发送同步请求。
     *
     * @param context 命令上下文
     * @return 命令执行结果
     */
    private static int executeSyncCurrentDim(CommandContext<FabricClientCommandSource> context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return 0;

        String dimensionId = currentDimensionId(mc);

        sendSyncRequest(mc, dimensionId, false);

        return Command.SINGLE_SUCCESS;
    }

    /**
     * 清除同步状态标记。
     * 用于忽略上次中断的同步提示。
     *
     * @param context 命令上下文
     * @return 命令执行结果
     */
    private static int clearSyncState(CommandContext<FabricClientCommandSource> context) {
        ClientJoinHandler.clearSyncState();
        return Command.SINGLE_SUCCESS;
    }

    /**
     * 解析用户输入的维度名称为完整维度 ID。
     * 支持简写（如 overworld）和完整 ID（如 minecraft:overworld）。
     *
     * @param input 用户输入的维度名称
     * @param level 客户端世界实例
     * @return 完整的维度 ID
     */
    public static String currentDimensionId(Minecraft mc) {
        if (mc.level == null) {
            return "minecraft:overworld";
        }
        ResourceKey<Level> currentDim = mc.level.dimension();
        return currentDim.identifier().toString();
    }

    private static int executeSyncRadius(CommandContext<FabricClientCommandSource> context) {
        int radiusBlocks = IntegerArgumentType.getInteger(context, "blocks");
        sendRadiusSyncRequest(Minecraft.getInstance(), radiusBlocks);
        return Command.SINGLE_SUCCESS;
    }

    static String resolveDimensionId(String input, ClientLevel level) {
        switch (input.toLowerCase()) {
            case "overworld": return "minecraft:overworld";
            case "nether": case "the_nether": return "minecraft:the_nether";
            case "end": case "the_end": return "minecraft:the_end";
        }

        if (input.contains(":")) return input;

        var optRegistry = level.registryAccess().lookup(Registries.DIMENSION_TYPE);
        if (optRegistry.isPresent()) {
            var registry = optRegistry.get();
            for (var key : registry.registryKeySet()) {
                Identifier loc = key.identifier();
                if ("minecraft".equals(loc.getNamespace())) continue;
                String path = loc.getPath();
                String dimPath = path.endsWith("_type") ? path.substring(0, path.length() - 5) : path;
                if (dimPath.equals(input) || path.equals(input)) {
                    return loc.getNamespace() + ":" + dimPath;
                }
            }
        }

        return "minecraft:" + input;
    }


    /**
     * 发送同步请求到服务端。
     * 计算客户端区域哈希，构建同步请求包并发送。
     *
     * @param mc Minecraft 客户端实例
     * @param dimensionId 维度 ID，如果是同步所有维度使用 "all"
     * @param syncAll 是否同步所有维度
     */
    static void sendSyncRequest(Minecraft mc, String dimensionId, boolean syncAll) {
        Path serverDir = XaeroMapIntegrator.getCurrentServerDirectory();
        DimensionPathMapping dimMapping = DimensionPathMapping.getInstance();
        String xaeroDim = syncAll ? null : dimMapping.toXaeroDimension(dimensionId);

        CompletableFuture.supplyAsync(() -> prepareSyncRequest(serverDir, dimensionId, xaeroDim, syncAll))
                .thenAccept(prepared -> mc.execute(() -> {
                    if (mc.player == null) {
                        return;
                    }

                    LOGGER.info("Sending sync request with {} entries (serverDir={})",
                            prepared.metaMap().size(), serverDir);
                    ClientPlayNetworking.send(new PacketHandler.SyncRequestPayload(prepared.metaMap()));
                    SyncProgressTracker.startTracking();
                }))
                .exceptionally(error -> {
                    LOGGER.error("Failed to prepare sync request", error);
                    return null;
                });
    }

    static void sendRadiusSyncRequest(Minecraft mc, int radiusBlocks) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (!ClientPlayNetworking.canSend(PacketHandler.RadiusSyncRequestPayload.TYPE)) {
            mc.player.sendSystemMessage(ChatUtils.error("mapsyncer.sync.radius_unsupported"));
            return;
        }

        Path serverDir = XaeroMapIntegrator.getCurrentServerDirectory();
        String dimensionId = currentDimensionId(mc);
        String xaeroDim = DimensionPathMapping.getInstance().toXaeroDimension(dimensionId);
        int playerX = mc.player.getBlockX();
        int playerY = mc.player.getBlockY();
        int playerZ = mc.player.getBlockZ();

        CompletableFuture.supplyAsync(() -> prepareSyncRequest(serverDir, dimensionId, xaeroDim, false))
                .thenAccept(prepared -> mc.execute(() -> {
                    if (mc.player == null) {
                        return;
                    }
                    ClientPlayNetworking.send(new PacketHandler.RadiusSyncRequestPayload(
                            prepared.metaMap(), dimensionId, radiusBlocks, playerX, playerY, playerZ));
                    SyncProgressTracker.startTracking();
                }))
                .exceptionally(error -> {
                    LOGGER.error("Failed to prepare radius sync request", error);
                    return null;
                });
    }

    private static PreparedSyncRequest prepareSyncRequest(Path serverDir, String dimensionId,
                                                          String xaeroDim, boolean syncAll) {
        Map<String, ClientMeta> metaMap;

        // 新流程：先发送请求，等服务端确认有数据后再暂停区块更新
        // 不在这里禁用区块更新，改为在收到服务端 status="ok" 后再暂停

        ClientTimestampCache tsCache = serverDir != null && serverDir.toFile().exists()
                ? ClientTimestampCache.getInstance(serverDir) : null;

        if (syncAll) {
            if (serverDir != null && tsCache != null && tsCache.cacheFileExists()) {
                metaMap = ClientHashManager.computeMetaForSync(serverDir);
                LOGGER.info("Sync all: {} cached entries", metaMap.size());
            } else {
                metaMap = new java.util.HashMap<>();
                LOGGER.info("First sync all, sending empty request");
            }
        } else {
            if (tsCache != null && tsCache.cacheFileExists() && tsCache.hasDimensionSynced(xaeroDim)) {
                Path dimDir = serverDir.resolve(xaeroDim);
                Path mwDir = findMwDir(dimDir);
                if (mwDir != null) {
                    metaMap = ClientHashManager.computeMetaForSync(mwDir);
                    LOGGER.info("Dimension {} previously synced, {} entries", dimensionId, metaMap.size());
                } else {
                    metaMap = new java.util.HashMap<>();
                    metaMap.put(xaeroDim + "/_placeholder_", new ClientMeta(0, "00000000"));
                    LOGGER.warn("Dimension {} has cache but no mw$ dir", dimensionId);
                }
            } else {
                metaMap = new java.util.HashMap<>();
                metaMap.put(xaeroDim + "/_placeholder_", new ClientMeta(0, "00000000"));
                LOGGER.info("First sync for {}", dimensionId);
            }
        }

        // 标记同步开始（用于断点续传检测）
        if (tsCache != null) {
            Set<String> dimensions = new HashSet<>();
            if (syncAll) {
                dimensions.add("all");
            } else {
                dimensions.add(xaeroDim);
            }
            String command = syncAll ? "/mapsyncer sync all" : "/mapsyncer sync " + dimensionId;
            tsCache.markSyncStart(dimensions, command);
        }

        return new PreparedSyncRequest(metaMap);
    }

    private record PreparedSyncRequest(Map<String, ClientMeta> metaMap) {
    }

    /**
     * 在维度目录下查找 mw$worldId 目录。
     * Xaero 使用 mw$worldId 格式存储地图数据。
     *
     * @param dimDir 维度目录路径
     * @return mw$ 目录路径，如果未找到返回 null
     */
    private static Path findMwDir(Path dimDir) {
        if (dimDir == null || !dimDir.toFile().exists()) return null;
        Path defaultMwDir = dimDir.resolve(XaeroMapIntegrator.DEFAULT_MW_DIR_NAME);
        if (Files.isDirectory(defaultMwDir)) {
            return defaultMwDir;
        }
        try {
            return Files.list(dimDir)
                    .filter(p -> XaeroMapIntegrator.DEFAULT_MW_DIR_NAME.equals(p.getFileName().toString()))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }
}
