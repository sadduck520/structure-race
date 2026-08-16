package com.example.structurerace;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
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
 *   <li><b>{@code /race join &lt;颜色&gt;}</b> —— 加入/切换到指定队伍（无需 OP，仅准备阶段，red/blue/yellow/orange/green/white/black/purple）</li>
 *   <li><b>{@code /race leave}</b> —— 离开队伍成为观众（无需 OP，仅准备阶段）</li>
 *   <li><b>{@code /race recall [玩家]}</b> —— 队伍召回（需同队，消耗 10 分，5 分钟冷却）</li>
 *   <li><b>{@code /race team add &lt;玩家&gt; &lt;队伍&gt;}</b> —— 将玩家移入队伍（管理员强制分配，比赛进行中也生效）</li>
 *   <li><b>{@code /race team remove &lt;玩家&gt;}</b> —— 将玩家移出队伍（管理员）</li>
 *   <li><b>{@code /race team list}</b> —— 查看所有队伍与成员</li>
 *   <li><b>{@code /race team info &lt;队伍&gt;}</b> —— 查看队伍的已发现结构/群系（中文名）</li>
 *   <li><b>{@code /race team check &lt;队伍&gt; &lt;结构&gt;}</b> —— 查询某结构是否已被该队伍发现</li>
 * </ul>
 */
public final class StructureRaceCommand {

    private StructureRaceCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            // 注意：/race 只能注册一次（重复注册会覆盖根节点，导致玩家命令被 OP 限制污染）
            dispatcher.register(CommandManager.literal("race")
                    // ===== 玩家命令（无需 OP）=====
                    .then(CommandManager.literal("join")
                            .then(CommandManager.argument("color", StringArgumentType.word())
                                    .executes(ctx -> join(ctx))))
                    .then(CommandManager.literal("leave")
                            .executes(ctx -> leave(ctx)))
                    .then(CommandManager.literal("point")
                            .executes(ctx -> point(ctx)))
                    .then(CommandManager.literal("progress")
                            .executes(ctx -> progress(ctx)))
                    .then(CommandManager.literal("recall")
                            .executes(ctx -> recall(ctx, ""))
                            .then(CommandManager.argument("player", StringArgumentType.word())
                                    .executes(ctx -> recall(ctx, StringArgumentType.getString(ctx, "player")))))
                    // ===== 管理员命令（需要 OP 权限 2）=====
                    .then(CommandManager.literal("start").requires(StructureRaceCommand::isOp).executes(ctx -> start(ctx)))
                    .then(CommandManager.literal("resume").requires(StructureRaceCommand::isOp).executes(ctx -> resume(ctx)))
                    .then(CommandManager.literal("stop").requires(StructureRaceCommand::isOp).executes(ctx -> stop(ctx)))
                    .then(CommandManager.literal("reset").requires(StructureRaceCommand::isOp).executes(ctx -> reset(ctx)))
                    .then(CommandManager.literal("mode").requires(StructureRaceCommand::isOp)
                            .then(CommandManager.argument("mode", StringArgumentType.word())
                                    .executes(ctx -> setMode(ctx))))
                    .then(CommandManager.literal("config").requires(StructureRaceCommand::isOp)
                            .then(CommandManager.literal("winscore")
                                    .then(CommandManager.argument("score", IntegerArgumentType.integer(1))
                                            .executes(ctx -> setWinScore(ctx))))
                            .then(CommandManager.literal("duration")
                                    .then(CommandManager.argument("seconds", IntegerArgumentType.integer(1))
                                            .executes(ctx -> setDuration(ctx)))))
                    .then(CommandManager.literal("status").requires(StructureRaceCommand::isOp).executes(ctx -> status(ctx)))
                    .then(CommandManager.literal("time").requires(StructureRaceCommand::isOp).executes(ctx -> time(ctx)))
                    .then(CommandManager.literal("top").requires(StructureRaceCommand::isOp).executes(ctx -> top(ctx)))
                    .then(CommandManager.literal("settings").requires(StructureRaceCommand::isOp).executes(ctx -> settings(ctx)))
                    .then(CommandManager.literal("team").requires(StructureRaceCommand::isOp)
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

            // /language 与 /lang：打开语言选择界面（无需 OP）；可选直接指定语言
            dispatcher.register(CommandManager.literal("language")
                    .executes(ctx -> language(ctx))
                    .then(CommandManager.literal("zh_cn").executes(ctx -> setLanguage(ctx, Lang.ZH)))
                    .then(CommandManager.literal("en_us").executes(ctx -> setLanguage(ctx, Lang.EN))));
            dispatcher.register(CommandManager.literal("lang")
                    .executes(ctx -> language(ctx)));
        });
    }

    /** 管理员权限判断（OP 2） */
    private static boolean isOp(ServerCommandSource src) {
        return src.hasPermissionLevel(2);
    }

    // ==================== 语言 ====================

    private static int language(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendError(Text.literal("该指令必须由玩家执行。 / This command is for players only."));
            return 0;
        }
        LanguageSelectorScreenHandler.open(player);
        return 1;
    }

    private static int setLanguage(CommandContext<ServerCommandSource> ctx, String lang) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendError(Text.literal("该指令必须由玩家执行。 / This command is for players only."));
            return 0;
        }
        Lang.setLanguage(player, lang);
        player.sendMessage(Text.literal(Lang.get(player,
                "§a语言已切换为：简体中文。", "§aLanguage switched to: English.")), false);
        return 1;
    }

    // ==================== 比赛控制 ====================

    private static ServerPlayerEntity playerOf(CommandContext<ServerCommandSource> ctx) {
        try {
            return ctx.getSource().getPlayer();
        } catch (Exception e) {
            return null;
        }
    }

    private static String langOf(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity p = playerOf(ctx);
        return p != null ? Lang.langOf(p) : Lang.ZH;
    }

    /** 双语反馈（带广播前缀） */
    private static void sendL(CommandContext<ServerCommandSource> ctx, String zh, String en, boolean broadcastToOps) {
        ctx.getSource().sendFeedback(() -> Text.literal(
                StructureRaceConfig.BROADCAST_PREFIX + Lang.get(langOf(ctx), zh, en)), broadcastToOps);
    }

    /** 双语错误反馈 */
    private static void sendErrL(CommandContext<ServerCommandSource> ctx, String zh, String en) {
        ctx.getSource().sendError(Text.literal(Lang.get(langOf(ctx), zh, en)));
    }


    private static int settings(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = playerOf(ctx);
        if (player == null) {
            sendErrL(ctx, "该指令必须由玩家执行。", "This command must be run by a player.");
            return 0;
        }
        SettingsScreenHandler.open(player);
        return 1;
    }

    private static int join(CommandContext<ServerCommandSource> ctx) {
        String color = StringArgumentType.getString(ctx, "color").toLowerCase();
        ServerPlayerEntity player = playerOf(ctx);
        if (player == null) {
            sendErrL(ctx, "该指令必须由玩家执行。", "This command must be run by a player.");
            return 0;
        }
        int result = StructureRaceEvents.joinTeam(ctx.getSource().getServer(), player, color);
        switch (result) {
            case 1:
                sendErrL(ctx, "比赛进行中不能换队，请等待比赛结束或由管理员调整。",
                        "You cannot switch teams during a match. Wait for it to end.");
                return 0;
            case 2:
                sendErrL(ctx, "队伍 \"" + color + "\" 不存在。可用队伍："
                                + String.join("、", StructureRaceConfig.DEFAULT_TEAM_IDS),
                        "Team \"" + color + "\" does not exist. Available: "
                                + String.join(", ", StructureRaceConfig.DEFAULT_TEAM_IDS));
                return 0;
            default:
                sendL(ctx, "你已加入 §6"
                                + StructureRaceConfig.TEAM_NAMES_ZH.getOrDefault(color, color) + "§r。",
                        "You joined §6"
                                + StructureRaceConfig.TEAM_NAMES_EN.getOrDefault(color, color) + "§r.",
                        false);
                return 1;
        }
    }

    private static int point(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = playerOf(ctx);
        if (player == null) {
            sendErrL(ctx, "该指令必须由玩家执行。", "This command must be run by a player.");
            return 0;
        }
        StructureRaceEvents.openPointBook(player);
        return 1;
    }

    private static int progress(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = playerOf(ctx);
        if (player == null) {
            sendErrL(ctx, "该指令必须由玩家执行。", "This command must be run by a player.");
            return 0;
        }
        StructureRaceEvents.openProgressBook(player);
        return 1;
    }

    private static int leave(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = playerOf(ctx);
        if (player == null) {
            sendErrL(ctx, "该指令必须由玩家执行。", "This command must be run by a player.");
            return 0;
        }
        int result = StructureRaceEvents.leaveTeam(ctx.getSource().getServer(), player);
        switch (result) {
            case 1:
                sendErrL(ctx, "比赛进行中不能离队，请等待比赛结束或由管理员调整。",
                        "You cannot leave your team during a match.");
                return 0;
            case 2:
                sendErrL(ctx, "你不在任何队伍中。", "You are not on any team.");
                return 0;
            default:
                sendL(ctx, "你已离开队伍。", "You left your team.", false);
                return 1;
        }
    }

    private static int recall(CommandContext<ServerCommandSource> ctx, String target) {
        ServerPlayerEntity actor = playerOf(ctx);
        if (actor == null) {
            sendErrL(ctx, "该指令必须由玩家执行。", "This command must be run by a player.");
            return 0;
        }
        int result = StructureRaceEvents.recallPlayer(ctx.getSource().getServer(), actor, target);
        switch (result) {
            case 1:
                sendErrL(ctx, "目标玩家不在线。", "Target player is offline.");
                return 0;
            case 2:
                sendErrL(ctx, "被召回玩家不在任何队伍中。", "Target player is not on any team.");
                return 0;
            case 3:
                sendErrL(ctx, "只能召回同队玩家（或由管理员执行）。",
                        "You can only recall teammates (or an admin).");
                return 0;
            case 4:
                sendErrL(ctx, "队伍召回冷却中（5 分钟）。", "Team recall is on cooldown (5 min).");
                return 0;
            case 5:
                sendErrL(ctx, "队伍积分不足，召回需要消耗 10 分。",
                        "Not enough team points. Recall costs 10 points.");
                return 0;
            case 6:
                sendErrL(ctx, "队伍中没有其他存活队友（单人队伍无法召回）。",
                        "No other alive teammate to recall (solo team).");
                return 0;
            default:
                sendL(ctx, "召回已执行。", "Recall executed.", false);
                return 1;
        }
    }

    private static int start(CommandContext<ServerCommandSource> ctx) {
        boolean ok = StructureRaceEvents.startMatch(ctx.getSource().getServer());
        if (!ok) {
            sendErrL(ctx, "比赛已在进行中，无需重复开始！", "A match is already in progress!");
            return 0;
        }
        sendL(ctx, "已开始新一局比赛（5 秒倒计时）！", "A new match is starting (5-sec countdown)!", false);
        return 1;
    }

    private static int resume(CommandContext<ServerCommandSource> ctx) {
        StructureRaceEvents.resumeMatch(ctx.getSource().getServer());
        sendL(ctx, "比赛已恢复，继续计分。", "Match resumed, scoring continues.", false);
        return 1;
    }

    private static int stop(CommandContext<ServerCommandSource> ctx) {
        StructureRaceEvents.stopMatch(ctx.getSource().getServer());
        sendL(ctx, "已停止比赛。", "Match stopped.", false);
        return 1;
    }

    private static int reset(CommandContext<ServerCommandSource> ctx) {
        StructureRaceEvents.resetMatch(ctx.getSource().getServer());
        sendL(ctx, "已重置比赛与所有玩家数据。", "Match and all player data reset.", false);
        return 1;
    }

    private static int setMode(CommandContext<ServerCommandSource> ctx) {
        String mode = StringArgumentType.getString(ctx, "mode");
        if (!"score".equalsIgnoreCase(mode) && !"timer".equalsIgnoreCase(mode)) {
            sendErrL(ctx, "无效模式，请使用 score 或 timer。", "Invalid mode. Use score or timer.");
            return 0;
        }
        StructureRaceEvents.setWinCondition(ctx.getSource().getServer(), mode);
        sendL(ctx, "获胜模式已切换为: " + ("timer".equalsIgnoreCase(mode) ? "限时制" : "积分制"),
                "Win condition set to: " + ("timer".equalsIgnoreCase(mode) ? "Timer" : "Score"),
                true);
        return 1;
    }

    private static int setWinScore(CommandContext<ServerCommandSource> ctx) {
        int score = IntegerArgumentType.getInteger(ctx, "score");
        StructureRaceEvents.setWinScore(ctx.getSource().getServer(), score);
        sendL(ctx, "积分制获胜分数已设为 " + score + " 分（下次比赛生效）。",
                "Score-mode win score set to " + score + " (takes effect next match).", true);
        return 1;
    }

    private static int setDuration(CommandContext<ServerCommandSource> ctx) {
        int seconds = IntegerArgumentType.getInteger(ctx, "seconds");
        StructureRaceEvents.setMatchDuration(ctx.getSource().getServer(), seconds);
        sendL(ctx, "限时制时长已设为 " + seconds + " 秒（" + (seconds / 60) + " 分钟，下次比赛生效）。",
                "Timer-mode duration set to " + seconds + " s (" + (seconds / 60) + " min, next match).", true);
        return 1;
    }

    private static int status(CommandContext<ServerCommandSource> ctx) {
        ctx.getSource().sendFeedback(() -> Text.literal(
                StructureRaceEvents.getStatus(ctx.getSource().getServer(), langOf(ctx))), false);
        return 1;
    }

    private static int time(CommandContext<ServerCommandSource> ctx) {
        long remaining = StructureRaceEvents.getRemainingSeconds(ctx.getSource().getServer());
        if (remaining < 0) {
            sendL(ctx, "当前不是限时制或比赛未进行中。",
                    "Not in timer mode or no match is active.", false);
        } else {
            boolean en = Lang.isEnLang(langOf(ctx));
            sendL(ctx, "距比赛结束还有 " + formatSeconds(remaining) + "。",
                    (en ? "" : "") + formatSecondsEn(remaining) + " left until the match ends.",
                    false);
        }
        return 1;
    }

    private static int top(CommandContext<ServerCommandSource> ctx) {
        List<String> lines = StructureRaceEvents.getLeaderboard(ctx.getSource().getServer(), langOf(ctx));
        if (lines.isEmpty()) {
            sendL(ctx, "当前排行榜为空（没有有人的队伍）。",
                    "The leaderboard is empty (no teams with members).", false);
            return 1;
        }
        for (String line : lines) {
            ctx.getSource().sendFeedback(() -> Text.literal(line), false);
        }
        return 1;
    }

    private static int teamAdd(CommandContext<ServerCommandSource> ctx) {
        String player = StringArgumentType.getString(ctx, "player");
        String team = StringArgumentType.getString(ctx, "team");
        int result = StructureRaceEvents.addPlayerToTeam(ctx.getSource().getServer(), player, team);
        switch (result) {
            case 1:
                sendErrL(ctx, "玩家 \"" + player + "\" 不在线。", "Player \"" + player + "\" is offline.");
                return 0;
            case 2:
                sendErrL(ctx, "队伍 \"" + team + "\" 不存在。", "Team \"" + team + "\" does not exist.");
                return 0;
            default:
                sendL(ctx, "已将玩家 \"" + player + "\" 移入队伍 \"" + team + "\"。",
                        "Moved player \"" + player + "\" to team \"" + team + "\".", true);
                return 1;
        }
    }

    private static int teamRemove(CommandContext<ServerCommandSource> ctx) {
        String player = StringArgumentType.getString(ctx, "player");
        int result = StructureRaceEvents.removePlayerFromTeam(ctx.getSource().getServer(), player);
        switch (result) {
            case 1:
                sendErrL(ctx, "玩家 \"" + player + "\" 不在线。", "Player \"" + player + "\" is offline.");
                return 0;
            case 2:
                sendErrL(ctx, "玩家 \"" + player + "\" 不在任何队伍中。",
                        "Player \"" + player + "\" is not on any team.");
                return 0;
            default:
                sendL(ctx, "已将玩家 \"" + player + "\" 移出队伍。",
                        "Removed player \"" + player + "\" from their team.", true);
                return 1;
        }
    }

    private static int teamList(CommandContext<ServerCommandSource> ctx) {
        List<String> lines = StructureRaceEvents.listTeams(ctx.getSource().getServer(), langOf(ctx));
        for (String line : lines) {
            ctx.getSource().sendFeedback(() -> Text.literal(line), false);
        }
        return 1;
    }

    private static int teamInfo(CommandContext<ServerCommandSource> ctx) {
        String team = StringArgumentType.getString(ctx, "team");
        List<String> lines = StructureRaceEvents.getTeamInfo(ctx.getSource().getServer(), team, langOf(ctx));
        if (lines == null) {
            sendErrL(ctx, "队伍 \"" + team + "\" 不存在。", "Team \"" + team + "\" does not exist.");
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
        List<String> info = StructureRaceEvents.getTeamInfo(ctx.getSource().getServer(), team, langOf(ctx));
        if (info == null) {
            sendErrL(ctx, "队伍 \"" + team + "\" 不存在。", "Team \"" + team + "\" does not exist.");
            return 0;
        }
        boolean found = StructureRaceEvents.hasTeamDiscoveredStructure(ctx.getSource().getServer(), team, struct);
        if (found) {
            sendL(ctx, "§a✓ §r队伍 " + team + " §a已经发现§r \"" + struct + "\"。",
                    "§a✓ §rTeam " + team + " §ahas found§r \"" + struct + "\".", false);
        } else {
            sendL(ctx, "§c✗ §r队伍 " + team + " §c尚未发现§r \"" + struct + "\"。",
                    "§c✗ §rTeam " + team + " §chas NOT found§r \"" + struct + "\".", false);
        }
        return 1;
    }

    private static String formatSeconds(long seconds) {

        long m = seconds / 60;
        long s = seconds % 60;
        if (m > 0) return m + " 分 " + s + " 秒";
        return s + " 秒";
    }

    private static String formatSecondsEn(long seconds) {
        long m = seconds / 60;
        long s = seconds % 60;
        if (m > 0) return m + " min " + s + " s";
        return s + " s";
    }
}
