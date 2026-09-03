package com.mapsyncer.server;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mapsyncer.config.ModConfig;
import com.mapsyncer.config.ModConfig.UpdateMode;
import com.mapsyncer.network.PacketHandler;
import com.mapsyncer.server.ConversionOrchestrator.DimensionCacheStats;
import com.mapsyncer.server.ConversionOrchestrator.SingleRegionResult;
import com.mapsyncer.util.ChatUtils;
import com.mapsyncer.util.DimensionPathMapping;
import com.mapsyncer.util.MapSyncerExecutors;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.resources.ResourceKey;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 缓存生成命令 - 注册和处理/mapsyncer命令
 *
 * 提供以下命令：
 * - /mapsyncer help - 显示帮助信息
 * - /mapsyncer generate - 生成所有维度的地图缓存
 * - /mapsyncer generate <dimension> - 生成指定维度的地图缓存
 * - /mapsyncer generate <dimension> <x> <z> - 生成指定区域的地图缓存
 * - /mapsyncer generate <dimension> force - 强制重新生成指定维度
 * - /mapsyncer status - 显示当前生成状态
 * - /mapsyncer incremental off/tick/scheduled/status - 配置增量更新模式
 *
 * 需要管理员权限（permission level 4）才能执行。
 */
public class CacheGenerateCommand {

    /**
     * 注册命令到命令分发器
     *
     * @param dispatcher Brigadier命令分发器
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mapsyncer")
                .requires(Commands.hasPermission(Commands.LEVEL_OWNERS))
                .executes(CacheGenerateCommand::showHelp)
                .then(Commands.literal("help")
                        .executes(CacheGenerateCommand::showHelp))
                .then(Commands.literal("gui")
                        .executes(CacheGenerateCommand::openGui))
                .then(Commands.literal("generate")
                        .executes(CacheGenerateCommand::generateAll)
                        .then(Commands.argument("dimension", DimensionArgument.dimension())
                                .executes(CacheGenerateCommand::generateDimension)
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .executes(CacheGenerateCommand::generateSingleRegion)))
                                .then(Commands.literal("force")
                                        .executes(CacheGenerateCommand::generateDimensionForce))))
                .then(Commands.literal("status")
                        .executes(CacheGenerateCommand::showStatus))
                .then(Commands.literal("incremental")
                        .then(Commands.literal("run")
                                .executes(CacheGenerateCommand::runIncrementalNow))
                        .then(Commands.literal("off")
                                .executes(CacheGenerateCommand::setIncrementalOff))
                        .then(Commands.literal("tick")
                                .executes(CacheGenerateCommand::setIncrementalTick)
                                .then(Commands.argument("interval", IntegerArgumentType.integer(20, 72000))
                                        .executes(CacheGenerateCommand::setIncrementalTickInterval)))
                        .then(Commands.literal("scheduled")
                                .executes(CacheGenerateCommand::setIncrementalScheduled)
                                .then(Commands.argument("hour", IntegerArgumentType.integer(0, 23))
                                        .executes(CacheGenerateCommand::setScheduledTimeDefaultMinute)
                                        .then(Commands.argument("minute", IntegerArgumentType.integer(0, 59))
                                                .executes(CacheGenerateCommand::setScheduledTime))))));
    }

    private static int showHelp(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> ChatUtils.prefix().append(ChatUtils.header("mapsyncer.help.server.header")), false);
        ctx.getSource().sendSuccess(() -> ChatUtils.desc("mapsyncer.help.server.gui"), false);
        ctx.getSource().sendSuccess(() -> ChatUtils.desc("mapsyncer.help.server.generate"), false);
        ctx.getSource().sendSuccess(() -> ChatUtils.desc("mapsyncer.help.server.generate_dim"), false);
        ctx.getSource().sendSuccess(() -> ChatUtils.desc("mapsyncer.help.server.generate_region"), false);
        ctx.getSource().sendSuccess(() -> ChatUtils.desc("mapsyncer.help.server.generate_force"), false);
        ctx.getSource().sendSuccess(() -> ChatUtils.desc("mapsyncer.help.server.status"), false);
        ctx.getSource().sendSuccess(() -> ChatUtils.desc("mapsyncer.help.server.incremental_off"), false);
        ctx.getSource().sendSuccess(() -> ChatUtils.desc("mapsyncer.help.server.incremental_tick"), false);
        ctx.getSource().sendSuccess(() -> ChatUtils.desc("mapsyncer.help.server.incremental_scheduled"), false);
        ctx.getSource().sendSuccess(() -> ChatUtils.desc("mapsyncer.help.server.incremental_run"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int openGui(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!ServerPlayNetworking.canSend(player, PacketHandler.OpenGuiPayload.TYPE)) {
            ctx.getSource().sendFailure(ChatUtils.error("mapsyncer.command.gui_client_missing"));
            return 0;
        }
        ServerPlayNetworking.send(player, new PacketHandler.OpenGuiPayload());
        return Command.SINGLE_SUCCESS;
    }

    /**
     * 生成所有维度的地图缓存
     *
     * @param ctx 命令上下文
     * @return 命令执行结果
     */
    private static int generateAll(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        ctx.getSource().sendSuccess(() -> ChatUtils.message("mapsyncer.generate.start_full"), false);

        MapSyncerExecutors.submitConversion(() -> {
            ConversionOrchestrator.generateAll(server);
            String dimList = String.join(", ", ConversionOrchestrator.getCompletedDimensions());
            server.execute(() -> ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.generate.full_complete",
                            ConversionOrchestrator.getProcessedCount(),
                            ConversionOrchestrator.getTotalCount(),
                            ConversionOrchestrator.getCompletedDimensions().size(),
                            dimList), false));
        });

        return Command.SINGLE_SUCCESS;
    }

    /**
     * 生成指定维度的地图缓存
     *
     * @param ctx 命令上下文
     * @return 命令执行结果
     * @throws CommandSyntaxException 如果维度参数解析失败
     */
    private static int generateDimension(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerLevel level = DimensionArgument.getDimension(ctx, "dimension");
        ResourceKey<Level> dimension = level.dimension();
        MinecraftServer server = ctx.getSource().getServer();
        String dimensionId = dimension.identifier().toString();
        String friendlyName = DimensionPathMapping.getInstance().getFriendlyName(dimension);
        ctx.getSource().sendSuccess(() -> ChatUtils.message("mapsyncer.generate.start_dim", friendlyName), false);

        MapSyncerExecutors.submitConversion(() -> {
            ConversionOrchestrator.generateDimension(server, dimensionId);
            server.execute(() -> ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.generate.dim_complete",
                            ConversionOrchestrator.getProcessedCount(),
                            ConversionOrchestrator.getTotalCount(),
                            ConversionOrchestrator.getUpdatedCount()), false));
        });

        return Command.SINGLE_SUCCESS;
    }

    /**
     * 强制重新生成指定维度的地图缓存
     *
     * 清除维度缓存目录后重新生成所有区域。
     *
     * @param ctx 命令上下文
     * @return 命令执行结果
     * @throws CommandSyntaxException 如果维度参数解析失败
     */
    private static int generateDimensionForce(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerLevel level = DimensionArgument.getDimension(ctx, "dimension");
        ResourceKey<Level> dimension = level.dimension();
        MinecraftServer server = ctx.getSource().getServer();
        String dimensionId = dimension.identifier().toString();
        String friendlyName = DimensionPathMapping.getInstance().getFriendlyName(dimension);
        ctx.getSource().sendSuccess(() -> ChatUtils.message("mapsyncer.generate.start_force", friendlyName), false);

        MapSyncerExecutors.submitConversion(() -> {
            ConversionOrchestrator.generateDimensionForce(server, dimensionId);
            server.execute(() -> ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.generate.force_complete",
                            ConversionOrchestrator.getProcessedCount(),
                            ConversionOrchestrator.getTotalCount(),
                            ConversionOrchestrator.getUpdatedCount()), false));
        });

        return Command.SINGLE_SUCCESS;
    }

    /**
     * 生成单个区域的地图缓存
     *
     * @param ctx 命令上下文
     * @return 命令执行结果
     * @throws CommandSyntaxException 如果参数解析失败
     */
    private static int generateSingleRegion(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerLevel level = DimensionArgument.getDimension(ctx, "dimension");
        ResourceKey<Level> dimension = level.dimension();
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int z = IntegerArgumentType.getInteger(ctx, "z");
        MinecraftServer server = ctx.getSource().getServer();

        if (ConversionOrchestrator.checkMcaFileExists(server, dimension, x, z) == null) {
            String friendlyName = DimensionPathMapping.getInstance().getFriendlyName(dimension);
            ctx.getSource().sendFailure(ChatUtils.error("mapsyncer.command.region_not_found", x, z, friendlyName));
            return 0;
        }

        String friendlyName = DimensionPathMapping.getInstance().getFriendlyName(dimension);
        ctx.getSource().sendSuccess(() -> ChatUtils.message("mapsyncer.command.generating_region", x, z, friendlyName), false);

        MapSyncerExecutors.submitConversion(() -> {
            SingleRegionResult result = ConversionOrchestrator.generateSingleRegion(server, dimension, x, z);
            server.execute(() -> {
                if (result == SingleRegionResult.SUCCESS) {
                    ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.command.region_converted"), false);
                } else if (result == SingleRegionResult.CONVERSION_FAILED) {
                    ctx.getSource().sendFailure(ChatUtils.error("mapsyncer.command.region_conversion_failed", x, z));
                }
            });
        });

        return Command.SINGLE_SUCCESS;
    }

    /**
     * 显示当前生成状态、增量更新状态和缓存统计（合并为一行）
     *
     * @param ctx 命令上下文
     * @return 命令执行结果
     */
    private static int showStatus(CommandContext<CommandSourceStack> ctx) {
        IncrementalUpdateHandler handler = IncrementalUpdateHandler.getInstance();
        UpdateMode mode = ModConfig.SERVER.incrementalUpdateMode;

        // 构建完整状态消息
        String genStatus;
        String incStatus;

        if (ConversionOrchestrator.isRunning()) {
            genStatus = String.format("转换进行中：%d/%d 个区域 - %s",
                    ConversionOrchestrator.getProcessedCount(),
                    ConversionOrchestrator.getTotalCount(),
                    ConversionOrchestrator.getStatus());
        } else {
            genStatus = "无转换任务";
        }

        if (mode == UpdateMode.DISABLED || !handler.isRunning()) {
            incStatus = "增量更新未启用";
        } else if (mode == UpdateMode.TICK) {
            int interval = ModConfig.SERVER.incrementalUpdateIntervalTicks;
            int remainingTicks = interval - handler.getTickCounter();
            int remainingSeconds = remainingTicks / 20;
            int minutes = remainingSeconds / 60;
            int seconds = remainingSeconds % 60;
            incStatus = String.format("增量更新TICK模式，下次 %d分%d秒后", minutes, seconds);
        } else if (mode == UpdateMode.SCHEDULED) {
            int hour = ModConfig.SERVER.scheduledUpdateHour;
            int minute = ModConfig.SERVER.scheduledUpdateMinute;
            incStatus = String.format("增量更新定时模式，每日 %02d:%02d", hour, minute);
        } else {
            incStatus = "增量更新未启用";
        }

        // 合并为一行显示
        ctx.getSource().sendSuccess(() -> ChatUtils.message("mapsyncer.status.combined", genStatus, incStatus), false);

        // 显示缓存统计（总计一行，每个维度单独一行）
        List<DimensionCacheStats> cacheStats = ConversionOrchestrator.getCacheStats();
        if (!cacheStats.isEmpty()) {
            int totalDims = cacheStats.size();
            int totalRegions = cacheStats.stream().mapToInt(DimensionCacheStats::regionCount).sum();
            long totalSize = cacheStats.stream().mapToLong(DimensionCacheStats::sizeBytes).sum();
            double totalSizeMB = totalSize / (1024.0 * 1024.0);

            // 总计一行
            ctx.getSource().sendSuccess(() -> ChatUtils.message("mapsyncer.status.cache_total",
                    totalDims, totalRegions, totalSizeMB), false);

            // 每个维度单独一行
            for (DimensionCacheStats stat : cacheStats) {
                ctx.getSource().sendSuccess(() -> ChatUtils.message("mapsyncer.status.cache_dim",
                        stat.dimension(), stat.regionCount(), stat.sizeMB()), false);
            }
        }

        return Command.SINGLE_SUCCESS;
    }

    private static int runIncrementalNow(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        ctx.getSource().sendSuccess(() -> ChatUtils.message("mapsyncer.command.incremental_run_start"), false);

        MapSyncerExecutors.submitConversion(() -> {
            ConversionOrchestrator.performIncrementalScan(server);
            server.execute(() -> ctx.getSource().sendSuccess(() ->
                    ChatUtils.success("mapsyncer.command.incremental_run_complete"), false));
        });

        return Command.SINGLE_SUCCESS;
    }

    /**
     * 禁用增量更新
     *
     * @param ctx 命令上下文
     * @return 命令执行结果
     */
    private static int setIncrementalOff(CommandContext<CommandSourceStack> ctx) {
        ModConfig.SERVER.incrementalUpdateMode = UpdateMode.DISABLED;
        saveConfig();
        IncrementalUpdateHandler.getInstance().stop();
        ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.command.incremental_disabled"), false);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * 设置TICK模式增量更新
     *
     * 使用配置中默认的tick间隔。
     *
     * @param ctx 命令上下文
     * @return 命令执行结果
     */
    private static int setIncrementalTick(CommandContext<CommandSourceStack> ctx) {
        ModConfig.SERVER.incrementalUpdateMode = UpdateMode.TICK;
        saveConfig();
        IncrementalUpdateHandler.getInstance().start(ctx.getSource().getServer());
        int interval = ModConfig.SERVER.incrementalUpdateIntervalTicks;
        ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.command.incremental_tick_set", interval, interval / 20.0f), false);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * 设置TICK模式增量更新并指定间隔
     *
     * @param ctx 命令上下文
     * @return 命令执行结果
     */
    private static int setIncrementalTickInterval(CommandContext<CommandSourceStack> ctx) {
        int interval = IntegerArgumentType.getInteger(ctx, "interval");
        ModConfig.SERVER.incrementalUpdateIntervalTicks = interval;
        ModConfig.SERVER.incrementalUpdateMode = UpdateMode.TICK;
        saveConfig();
        IncrementalUpdateHandler.getInstance().start(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.command.incremental_tick_interval", interval, interval / 20.0f), false);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * 设置SCHEDULED模式增量更新
     *
     * 使用配置中默认的计划时间。
     *
     * @param ctx 命令上下文
     * @return 命令执行结果
     */
    private static int setIncrementalScheduled(CommandContext<CommandSourceStack> ctx) {
        ModConfig.SERVER.incrementalUpdateMode = UpdateMode.SCHEDULED;
        saveConfig();
        IncrementalUpdateHandler.getInstance().start(ctx.getSource().getServer());
        int hour = ModConfig.SERVER.scheduledUpdateHour;
        int minute = ModConfig.SERVER.scheduledUpdateMinute;
        ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.command.incremental_scheduled_set", hour, minute), false);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * 设置SCHEDULED模式并指定小时（使用默认分钟）
     *
     * @param ctx 命令上下文
     * @return 命令执行结果
     */
    private static int setScheduledTimeDefaultMinute(CommandContext<CommandSourceStack> ctx) {
        int hour = IntegerArgumentType.getInteger(ctx, "hour");
        ModConfig.SERVER.scheduledUpdateHour = hour;
        ModConfig.SERVER.incrementalUpdateMode = UpdateMode.SCHEDULED;
        saveConfig();
        IncrementalUpdateHandler.getInstance().start(ctx.getSource().getServer());
        int minute = ModConfig.SERVER.scheduledUpdateMinute;
        ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.command.incremental_scheduled_set", hour, minute), false);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * 设置SCHEDULED模式并指定完整时间
     *
     * @param ctx 命令上下文
     * @return 命令执行结果
     */
    private static int setScheduledTime(CommandContext<CommandSourceStack> ctx) {
        int hour = IntegerArgumentType.getInteger(ctx, "hour");
        int minute = IntegerArgumentType.getInteger(ctx, "minute");
        ModConfig.SERVER.scheduledUpdateHour = hour;
        ModConfig.SERVER.scheduledUpdateMinute = minute;
        ModConfig.SERVER.incrementalUpdateMode = UpdateMode.SCHEDULED;
        saveConfig();
        IncrementalUpdateHandler.getInstance().start(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.command.incremental_scheduled_set", hour, minute), false);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * 保存配置文件
     */
    private static void saveConfig() {
        ModConfig.save();
    }
}
