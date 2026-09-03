package com.nexus.voxysync.server;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.nexus.voxysync.VoxySyncConfig;
import com.nexus.voxysync.VoxySyncMod;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.UUID;

/**
 * /voxysync 管理命令（需要 OP，权限等级 2）。
 *
 * <pre>
 * /voxysync status                 查看当前配置与进行中的同步
 * /voxysync enable | disable       开关服务端同步
 * /voxysync mode radius|all        切换模式（all = 全图，注意安全警告）
 * /voxysync radius &lt;blocks&gt;        设置 radius 模式的半径（方块）
 * /voxysync sync [radius|all]      让<b>自己</b>立即重新同步一次（可临时指定模式）
 * /voxysync devtest [mode] [radius] 只读诊断（/tmp 测试实例无客户端验证用）
 * </pre>
 */
public final class VoxySyncCommand {
    private static final SuggestionProvider<CommandSourceStack> MODE_SUGGESTIONS =
            (context, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                    new String[]{"radius", "all"}, builder);

    private VoxySyncCommand() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("voxysync").requires(source -> source.hasPermission(2))
                        .then(Commands.literal("status").executes(VoxySyncCommand::status))
                        .then(Commands.literal("enable").executes(ctx -> setEnabled(ctx, true)))
                        .then(Commands.literal("disable").executes(ctx -> setEnabled(ctx, false)))
                        .then(Commands.literal("mode")
                                .then(Commands.argument("mode", StringArgumentType.word())
                                        .suggests(MODE_SUGGESTIONS)
                                        .executes(VoxySyncCommand::setMode)))
                        .then(Commands.literal("radius")
                                .then(Commands.argument("blocks", IntegerArgumentType.integer(64, 100000))
                                        .executes(VoxySyncCommand::setRadius)))
                        .then(Commands.literal("sync")
                                .executes(ctx -> syncSelf(ctx, null))
                                .then(Commands.argument("mode", StringArgumentType.word())
                                        .suggests(MODE_SUGGESTIONS)
                                        .executes(ctx -> syncSelf(ctx, StringArgumentType.getString(ctx, "mode")))))
                        .then(Commands.literal("devtest")
                                .executes(ctx -> devtest(ctx, null, -1))
                                .then(Commands.argument("mode", StringArgumentType.word())
                                        .suggests(MODE_SUGGESTIONS)
                                        .executes(ctx -> devtest(ctx, StringArgumentType.getString(ctx, "mode"), -1))
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(64, 100000))
                                                .executes(ctx -> devtest(ctx,
                                                        StringArgumentType.getString(ctx, "mode"),
                                                        IntegerArgumentType.getInteger(ctx, "radius"))))))
                ));
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        VoxySyncConfig.Config cfg = VoxySyncConfig.INSTANCE;
        String modeText = "all".equals(cfg.syncMode)
                ? "§c全图 (all)§r —— 发送整个维度，数据可能暴露箱子/矿脉/地下结构!"
                : "radius（半径 " + cfg.radiusBlocks + " 方块）";
        ctx.getSource().sendSuccess(() -> Component.literal("§6[VoxySync]§r 状态：")
                .append("\n  · 同步开关: " + (cfg.enableVoxySync ? "§a开启§r" : "§c关闭§r"))
                .append("\n  · 模式: " + modeText)
                .append("\n  · 限速: " + cfg.speedLimitKBps + " KB/s（≤0 不限速）")
                .append("\n  · 分片大小: " + cfg.maxPacketSize + " 字节")
                .append("\n  · 进行中的同步: " + VoxySyncHandler.getActiveSyncCount() + " 个")
                .append("\n  · 配置: config/voxysync.json"), true);
        return 1;
    }

    private static int setEnabled(CommandContext<CommandSourceStack> ctx, boolean enabled) {
        VoxySyncConfig.INSTANCE.enableVoxySync = enabled;
        VoxySyncConfig.save();
        VoxySyncHandler.logSecurityWarningIfEnabled();
        ctx.getSource().sendSuccess(() -> Component.literal("§6[VoxySync]§r 同步已"
                + (enabled ? "§a开启§r（模式: " + VoxySyncConfig.INSTANCE.syncMode + "）" : "§c关闭§r")), true);
        return 1;
    }

    private static int setMode(CommandContext<CommandSourceStack> ctx) {
        String mode = StringArgumentType.getString(ctx, "mode");
        if (!"radius".equals(mode) && !"all".equals(mode)) {
            ctx.getSource().sendFailure(Component.literal("§c模式只能是 radius 或 all"));
            return 0;
        }
        VoxySyncConfig.INSTANCE.syncMode = mode;
        VoxySyncConfig.save();
        if ("all".equals(mode)) {
            VoxySyncMod.LOGGER.warn("[VoxySync] 已切换为全图模式(all)：发送完整 MCA 区域文件，"
                    + "可暴露箱子内容/实体/矿脉/隐藏结构，仅建议在信任的服务器使用！");
            ctx.getSource().sendSuccess(() -> Component.literal("§6[VoxySync]§r 已切换为 §c全图模式(all)§r。"
                    + "警告：MCA 数据可暴露箱子内容、实体、矿脉与隐藏结构，仅建议在信任的服务器使用！"), true);
        } else {
            ctx.getSource().sendSuccess(() -> Component.literal("§6[VoxySync]§r 已切换为 radius 模式（半径 "
                    + VoxySyncConfig.INSTANCE.radiusBlocks + " 方块）"), true);
        }
        return 1;
    }

    private static int setRadius(CommandContext<CommandSourceStack> ctx) {
        int blocks = IntegerArgumentType.getInteger(ctx, "blocks");
        VoxySyncConfig.INSTANCE.radiusBlocks = blocks;
        VoxySyncConfig.save();
        ctx.getSource().sendSuccess(() -> Component.literal("§6[VoxySync]§r radius 半径已设为 "
                + blocks + " 方块"), true);
        return 1;
    }

    private static int syncSelf(CommandContext<CommandSourceStack> ctx, String mode) {
        ServerPlayer player;
        try {
            player = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("§c该命令只能由在线玩家执行"));
            return 0;
        }
        if (mode != null && !"radius".equals(mode) && !"all".equals(mode)) {
            ctx.getSource().sendFailure(Component.literal("§c模式只能是 radius 或 all"));
            return 0;
        }
        UUID playerId = player.getUUID();
        if (mode != null) {
            VoxySyncHandler.setPendingMode(playerId, mode);
        }
        VoxySyncHandler.requestClientSync(player, mode, "管理员请求重新同步");
        ctx.getSource().sendSuccess(() -> Component.literal("§6[VoxySync]§r 已通知客户端重新同步"
                + (mode != null ? "（模式: " + mode + "）" : "（按配置: " + VoxySyncConfig.INSTANCE.syncMode + "）")), true);
        return 1;
    }

    /** 诊断（只读）：无真实客户端时验证区域收集/增量逻辑与编解码 */
    private static int devtest(CommandContext<CommandSourceStack> ctx, String mode, int radius) {
        ServerLevel world = ctx.getSource().getLevel();
        int cx = world.getSharedSpawnPos().getX();
        int cz = world.getSharedSpawnPos().getZ();
        if (mode == null) {
            mode = VoxySyncConfig.INSTANCE.syncMode;
        }
        String out = VoxySyncHandler.diagCollect(world, cx, cz, mode, radius);
        ctx.getSource().sendSuccess(() -> Component.literal(out), true);
        ctx.getSource().sendSuccess(() -> Component.literal("编解码测试: " + VoxySyncHandler.diagCodec()), true);
        Path regionDir = VoxySyncHandler.resolveRegionDir(world);
        if (regionDir != null) {
            ctx.getSource().sendSuccess(() -> Component.literal("文件校验: " + VoxySyncHandler.diagFileRead(regionDir)), true);
        }
        return 1;
    }
}
