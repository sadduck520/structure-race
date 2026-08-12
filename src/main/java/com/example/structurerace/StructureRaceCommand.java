package com.example.structurerace;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.List;

/**
 * 结构竞速 (Structure Race) - 管理员指令注册类
 *
 * <p>命令列表（需要 OP 权限 level 2）：
 * <ul>
 *   <li><b>{@code /race start}</b> —— 开始新一局（清空分数与队伍进度）</li>
 *   <li><b>{@code /race resume}</b> —— 恢复已暂停的比赛（不清空分数）</li>
 *   <li><b>{@code /race stop}</b> —— 停止比赛（暂停计分）</li>
 *   <li><b>{@code /race reset}</b> —— 重置比赛与所有玩家数据</li>
 *   <li><b>{@code /race mode &lt;score|timer&gt;}</b> —— 切换获胜模式</li>
 *   <li><b>{@code /race config winscore &lt;分&gt;}</b> —— 热修改积分制获胜分数</li>
 *   <li><b>{@code /race config duration &lt;秒&gt;}</b> —— 热修改限时制时长</li>
 *   <li><b>{@code /race status}</b> —— 查看当前模式、状态与剩余时间</li>
 *   <li><b>{@code /race time}</b> —— 查看限时制剩余时间</li>
 *   <li><b>{@code /race top}</b> —— 显示当前积分排行榜</li>
 *   <li><b>{@code /race team create &lt;名称&gt;}</b> —— 创建队伍</li>
 *   <li><b>{@code /race team disband &lt;名称&gt;}</b> —— 解散队伍</li>
 *   <li><b>{@code /race team add &lt;玩家&gt; &lt;队伍&gt;}</b> —— 将玩家移入队伍（自动离开原队伍）</li>
 *   <li><b>{@code /race team remove &lt;玩家&gt;}</b> —— 将玩家移出队伍</li>
 *   <li><b>{@code /race team list}</b> —— 查看所有队伍与成员</li>
 *   <li><b>{@code /race team info &lt;队伍&gt;}</b> —— 查看队伍的已发现结构/群系（中文名）</li>
 *   <li><b>{@code /race team check &lt;队伍&gt; &lt;结构&gt;}</b> —— 查询某结构是否已被该队伍发现</li>
 * </ul>
 */
public final class StructureRaceCommand {

    private StructureRaceCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("race")
                    .requires(src -> src.hasPermissionLevel(2)) // 管理员权限
                    .then(CommandManager.literal("start").executes(ctx -> start(ctx)))
                    .then(CommandManager.literal("resume").executes(ctx -> resume(ctx)))
                    .then(CommandManager.literal("stop").executes(ctx -> stop(ctx)))
                    .then(CommandManager.literal("reset").executes(ctx -> reset(ctx)))
                    .then(CommandManager.literal("mode")
                            .then(CommandManager.argument("mode", StringArgumentType.word())
                                    .executes(ctx -> setMode(ctx))))
                    .then(CommandManager.literal("config")
                            .then(CommandManager.literal("winscore")
                                    .then(CommandManager.argument("score", IntegerArgumentType.integer(1))
                                            .executes(ctx -> setWinScore(ctx))))
                            .then(CommandManager.literal("duration")
                                    .then(CommandManager.argument("seconds", IntegerArgumentType.integer(1))
                                            .executes(ctx -> setDuration(ctx)))))
                    .then(CommandManager.literal("status").executes(ctx -> status(ctx)))
                    .then(CommandManager.literal("time").executes(ctx -> time(ctx)))
                    .then(CommandManager.literal("top").executes(ctx -> top(ctx)))
                    .then(CommandManager.literal("team")
                            .then(CommandManager.literal("create")
                                    .then(CommandManager.argument("name", StringArgumentType.word())
                                            .executes(ctx -> teamCreate(ctx))))
                            .then(CommandManager.literal("disband")
                                    .then(CommandManager.argument("name", StringArgumentType.word())
                                            .executes(ctx -> teamDisband(ctx))))
                            .then(CommandManager.literal("add")
                                    .then(CommandManager.argument("player", StringArgumentType.word())
                                            .then(CommandManager.argument("team", StringArgumentType.word())
                                                    .executes(ctx -> teamAdd(ctx)))))
                            .then(CommandManager.literal("remove")
                                    .then(CommandManager.argument("player", StringArgumentType.word())
                                            .executes(ctx -> teamRemove(ctx))))
                            .then(CommandManager.literal("list").executes(ctx -> teamList(ctx)))
                            .then(CommandManager.literal("info")
                                    .then(CommandManager.argument("team", StringArgumentType.word())
                                            .executes(ctx -> teamInfo(ctx))))
                            .then(CommandManager.literal("check")
                                    .then(CommandManager.argument("team", StringArgumentType.word())
                                            .then(CommandManager.argument("structure", StringArgumentType.greedyString())
                                                    .executes(ctx -> teamCheck(ctx)))))));
        });
    }

    // ==================== 比赛控制 ====================

    private static int start(CommandContext<ServerCommandSource> ctx) {
        StructureRaceEvents.startMatch(ctx.getSource().getServer());
        ctx.getSource().sendFeedback(() -> Text.literal(
                StructureRaceConfig.BROADCAST_PREFIX + "已开始新一局比赛！"), false);
        return 1;
    }

    private static int resume(CommandContext<ServerCommandSource> ctx) {
        StructureRaceEvents.resumeMatch(ctx.getSource().getServer());
        ctx.getSource().sendFeedback(() -> Text.literal(
                StructureRaceConfig.BROADCAST_PREFIX + "比赛已恢复，继续计分。"), false);
        return 1;
    }

    private static int stop(CommandContext<ServerCommandSource> ctx) {
        StructureRaceEvents.stopMatch(ctx.getSource().getServer());
        ctx.getSource().sendFeedback(() -> Text.literal(
                StructureRaceConfig.BROADCAST_PREFIX + "已停止比赛。"), false);
        return 1;
    }

    private static int reset(CommandContext<ServerCommandSource> ctx) {
        StructureRaceEvents.resetMatch(ctx.getSource().getServer());
        ctx.getSource().sendFeedback(() -> Text.literal(
                StructureRaceConfig.BROADCAST_PREFIX + "已重置比赛与所有玩家数据。"), false);
        return 1;
    }

    private static int setMode(CommandContext<ServerCommandSource> ctx) {
        String mode = StringArgumentType.getString(ctx, "mode");
        if (!"score".equalsIgnoreCase(mode) && !"timer".equalsIgnoreCase(mode)) {
            ctx.getSource().sendError(Text.literal("无效模式，请使用 score 或 timer。"));
            return 0;
        }
        StructureRaceEvents.setWinCondition(ctx.getSource().getServer(), mode);
        ctx.getSource().sendFeedback(() -> Text.literal(
                StructureRaceConfig.BROADCAST_PREFIX
                        + "获胜模式已切换为: " + ("timer".equalsIgnoreCase(mode) ? "限时制" : "积分制")), true);
        return 1;
    }

    private static int setWinScore(CommandContext<ServerCommandSource> ctx) {
        int score = IntegerArgumentType.getInteger(ctx, "score");
        StructureRaceEvents.setWinScore(ctx.getSource().getServer(), score);
        ctx.getSource().sendFeedback(() -> Text.literal(
                StructureRaceConfig.BROADCAST_PREFIX + "积分制获胜分数已设为 " + score + " 分（下次比赛生效）。"), true);
        return 1;
    }

    private static int setDuration(CommandContext<ServerCommandSource> ctx) {
        int seconds = IntegerArgumentType.getInteger(ctx, "seconds");
        StructureRaceEvents.setMatchDuration(ctx.getSource().getServer(), seconds);
        ctx.getSource().sendFeedback(() -> Text.literal(
                StructureRaceConfig.BROADCAST_PREFIX + "限时制时长已设为 " + seconds
                        + " 秒（" + (seconds / 60) + " 分钟，下次比赛生效）。"), true);
        return 1;
    }

    private static int status(CommandContext<ServerCommandSource> ctx) {
        ctx.getSource().sendFeedback(() -> Text.literal(
                StructureRaceEvents.getStatus(ctx.getSource().getServer())), false);
        return 1;
    }

    private static int time(CommandContext<ServerCommandSource> ctx) {
        long remaining = StructureRaceEvents.getRemainingSeconds(ctx.getSource().getServer());
        if (remaining < 0) {
            ctx.getSource().sendFeedback(() -> Text.literal(
                    StructureRaceConfig.BROADCAST_PREFIX + "当前不是限时制或比赛未进行中。"), false);
        } else {
            ctx.getSource().sendFeedback(() -> Text.literal(
                    StructureRaceConfig.BROADCAST_PREFIX + "距比赛结束还有 "
                            + formatSeconds(remaining) + "。"), false);
        }
        return 1;
    }

    private static int top(CommandContext<ServerCommandSource> ctx) {
        List<String> lines = StructureRaceEvents.getLeaderboard();
        if (lines.isEmpty()) {
            ctx.getSource().sendFeedback(() -> Text.literal(
                    StructureRaceConfig.BROADCAST_PREFIX + "当前排行榜为空。"), false);
            return 1;
        }
        for (String line : lines) {
            ctx.getSource().sendFeedback(() -> Text.literal(line), false);
        }
        return 1;
    }

    // ==================== 队伍管理 ====================

    private static int teamCreate(CommandContext<ServerCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        boolean ok = StructureRaceEvents.createTeam(ctx.getSource().getServer(), name);
        if (!ok) {
            ctx.getSource().sendError(Text.literal("队伍 \"" + name + "\" 已存在。"));
            return 0;
        }
        ctx.getSource().sendFeedback(() -> Text.literal(
                StructureRaceConfig.BROADCAST_PREFIX + "已创建队伍 \"" + name + "\"。"), true);
        return 1;
    }

    private static int teamDisband(CommandContext<ServerCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        boolean ok = StructureRaceEvents.disbandTeam(ctx.getSource().getServer(), name);
        if (!ok) {
            ctx.getSource().sendError(Text.literal("队伍 \"" + name + "\" 不存在。"));
            return 0;
        }
        ctx.getSource().sendFeedback(() -> Text.literal(
                StructureRaceConfig.BROADCAST_PREFIX + "已解散队伍 \"" + name + "\"。"), true);
        return 1;
    }

    private static int teamAdd(CommandContext<ServerCommandSource> ctx) {
        String player = StringArgumentType.getString(ctx, "player");
        String team = StringArgumentType.getString(ctx, "team");
        int result = StructureRaceEvents.addPlayerToTeam(ctx.getSource().getServer(), player, team);
        switch (result) {
            case 1:
                ctx.getSource().sendError(Text.literal("玩家 \"" + player + "\" 不在线。"));
                return 0;
            case 2:
                ctx.getSource().sendError(Text.literal("队伍 \"" + team + "\" 不存在。"));
                return 0;
            default:
                ctx.getSource().sendFeedback(() -> Text.literal(
                        StructureRaceConfig.BROADCAST_PREFIX + "已将玩家 \"" + player + "\" 移入队伍 \"" + team + "\"。"), true);
                return 1;
        }
    }

    private static int teamRemove(CommandContext<ServerCommandSource> ctx) {
        String player = StringArgumentType.getString(ctx, "player");
        int result = StructureRaceEvents.removePlayerFromTeam(ctx.getSource().getServer(), player);
        switch (result) {
            case 1:
                ctx.getSource().sendError(Text.literal("玩家 \"" + player + "\" 不在线。"));
                return 0;
            case 2:
                ctx.getSource().sendError(Text.literal("玩家 \"" + player + "\" 不在任何队伍中。"));
                return 0;
            default:
                ctx.getSource().sendFeedback(() -> Text.literal(
                        StructureRaceConfig.BROADCAST_PREFIX + "已将玩家 \"" + player + "\" 移出队伍。"), true);
                return 1;
        }
    }

    private static int teamList(CommandContext<ServerCommandSource> ctx) {
        List<String> lines = StructureRaceEvents.listTeams(ctx.getSource().getServer());
        for (String line : lines) {
            ctx.getSource().sendFeedback(() -> Text.literal(line), false);
        }
        return 1;
    }

    private static int teamInfo(CommandContext<ServerCommandSource> ctx) {
        String team = StringArgumentType.getString(ctx, "team");
        List<String> lines = StructureRaceEvents.getTeamInfo(ctx.getSource().getServer(), team);
        if (lines == null) {
            ctx.getSource().sendError(Text.literal("队伍 \"" + team + "\" 不存在。"));
            return 0;
        }
        for (String line : lines) {
            ctx.getSource().sendFeedback(() -> Text.literal(line), false);
        }
        return 1;
    }

    private static int teamCheck(CommandContext<ServerCommandSource> ctx) {
        String team = StringArgumentType.getString(ctx, "team");
        String struct = StringArgumentType.getString(ctx, "structure");
        List<String> info = StructureRaceEvents.getTeamInfo(ctx.getSource().getServer(), team);
        if (info == null) {
            ctx.getSource().sendError(Text.literal("队伍 \"" + team + "\" 不存在。"));
            return 0;
        }
        boolean found = StructureRaceEvents.hasTeamDiscoveredStructure(ctx.getSource().getServer(), team, struct);
        if (found) {
            ctx.getSource().sendFeedback(() -> Text.literal(
                    "§a✓ §r队伍 " + team + " §a已经发现§r \"" + struct + "\"。"), false);
        } else {
            ctx.getSource().sendFeedback(() -> Text.literal(
                    "§c✗ §r队伍 " + team + " §c尚未发现§r \"" + struct + "\"。"), false);
        }
        return 1;
    }

    private static String formatSeconds(long seconds) {
        long m = seconds / 60;
        long s = seconds % 60;
        if (m > 0) return m + " 分 " + s + " 秒";
        return s + " 秒";
    }
}
