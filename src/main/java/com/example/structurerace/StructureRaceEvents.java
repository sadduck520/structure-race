package com.example.structurerace;

import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.Blocks;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.map.MapState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.network.message.MessageType;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ScoreboardPlayerScore;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureStart;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.structure.Structure;

/**
 * 结构竞速 (Structure Race) V2.6 - 事件监听类
 *
 * <p>核心规则（V2.6）：
 * <ul>
 *   <li><b>大厅</b>：独立 lobby 维度（40x40 玻璃平台 + 虚空），准备/结束阶段玩家在此组队，
 *       持有选队指南针（GUI）与规则书，冒险模式。</li>
 *   <li><b>8 支固定队伍</b>：红/蓝/黄/橙/绿/白/黑/紫，比赛进行中锁定换队；
 *       无队伍玩家 = 观众，比赛期间在主世界旁观。</li>
 *   <li>计分板按队伍显示（每队一条，无人队伍不显示）；Tab 列表只显示带队伍颜色的玩家名。</li>
 *   <li>平衡机制：里程计分、击杀计分、队伍召回、维度奖励、落后补偿、迷路指引。</li>
 *   <li>结算：宣布胜负/平局（title + 排名），10 秒后回大厅放烟花，并输出得分日志。</li>
 * </ul>
 */
public final class StructureRaceEvents {

    private static final Logger LOGGER = LoggerFactory.getLogger("StructureRace");

    // ==================== 常量 ====================

    private static final long COOLDOWN_TICKS = StructureRaceConfig.SCORE_COOLDOWN_SECONDS * 20L;
    private static final long BIOME_COOLDOWN_TICKS = StructureRaceConfig.BIOME_CHECK_COOLDOWN_TICKS;

    /** 机制1：每累计 500 格水平移动加 1 分 */
    private static final double DISTANCE_PER_POINT = 500.0;
    /** 单次采样位移超过该值视为传送，不累计距离 */
    private static final double DISTANCE_TELEPORT_THRESHOLD = 50.0;

    /** 机制2：每击杀 10 只敌对怪物加 1 分 */
    private static final int KILLS_PER_POINT = 10;
    /** 机制2：单玩家单局击杀计分上限（200 只 = 20 分） */
    private static final int MAX_KILLS = 200;

    /** 机制3：队伍召回冷却（5 分钟） */
    private static final long RECALL_COOLDOWN_TICKS = 300L * 20L;
    /** 机制3：召回消耗队伍分 */
    private static final int RECALL_COST = 10;

    /** 机制4：维度奖励分值 */
    private static final int NETHER_BONUS = 10;
    private static final int END_BONUS = 20;

    /** 机制5：落后队伍补偿判定分差（落后第一名 20 分即触发速度补偿） */
    private static final int COMPENSATION_GAP = 20;
    /** 机制5：落后补偿提示节流（每 1 分钟仅提示一次，避免刷屏） */
    private static final long COMPENSATION_HINT_INTERVAL_TICKS = 1200L;

    /** 机制7：队伍反超提醒 - 最高分超过该阈值（分）后开始追踪排名变化 */
    private static final int LEAD_CHANGE_THRESHOLD = 40;
    /** 机制7：上次各队排名（teamId → 名次，1 起） */
    private static final Map<String, Integer> previousRankings = new HashMap<>();
    /** 机制7：是否已进入排名追踪（至少一队超过阈值） */
    private static boolean leadTrackingActive = false;

    /** 机制6：迷路指引 - 个人「3 分钟无发现」计时（引用 Config 常量） */
    private static final long HINT_NO_FIND_TICKS = StructureRaceConfig.HINT_NO_FIND_TICKS;
    /** 机制6：迷路指引 - 提示后个人 7 分钟冷却 */
    private static final long HINT_COOLDOWN_TICKS = StructureRaceConfig.HINT_COOLDOWN_TICKS;

    // ==================== 内存状态 ====================

    private static final Map<UUID, PlayerState> PLAYER_STATES = new HashMap<>();
    private static final Map<UUID, String> playerTeamMap = new HashMap<>();

    /** 本局得分明细（调试用）：每次加分记录，比赛结束后统一输出 */
    private static final List<ScoreLogEntry> SCORE_LOG = new ArrayList<>();

    /** 大厅维度 key（独立维度：40x40 玻璃平台 + 虚空） */
    public static final RegistryKey<World> LOBBY_KEY = RegistryKey.of(
            RegistryKeys.WORLD, new Identifier("structure_race", "lobby"));
    private static final int LOBBY_PLATFORM_SIZE = 40;
    private static final int LOBBY_PLATFORM_Y = 64;
    private static boolean lobbyInitialized = false;

    /** 进服等待点：主世界出生点地底（基岩层 y=-64 上 3 格空间） */
    private static final int LOBBY_WAIT_Y = -64;
    private static BlockPos lobbyWaitPos;

    /** /race start 后开赛倒计时（tick）；>0 表示正在倒计时 */
    private static int preStartCountdownTicks = -1;
    private static long lastCountdownSecond = -1;

    /** 比赛结算延迟（tick）：宣布结果后 10 秒传送回大厅并放烟花 */
    private static final int END_RESULT_DELAY_TICKS = 200;
    private static int pendingEndTicks = -1;
    private static MinecraftServer pendingEndServer;

    // ==================== 胜利烟花（多波次，延长庆祝时间） ====================

    /** 烟花波数 */
    private static final int FIREWORK_WAVES = 8;
    /** 每波烟花数量（每波少量，分多波拉长庆祝时间） */
    private static final int FIREWORK_PER_WAVE = 5;
    /** 波次间隔（tick，2 秒） */
    private static final int FIREWORK_WAVE_INTERVAL = 40;
    private static int fireworkWavesLeft = 0;
    private static int fireworkWaveTicks = 0;

    /** 竞速目标结构 tag（用于迷路指引 locateStructure） */
    private static final TagKey<Structure> RACE_STRUCTURES_TAG = TagKey.of(
            RegistryKeys.STRUCTURE, new Identifier("structure_race", "race_structures"));

    private static final Formatting[] TEAM_COLORS = {
            Formatting.RED, Formatting.BLUE, Formatting.GREEN, Formatting.YELLOW,
            Formatting.LIGHT_PURPLE, Formatting.AQUA, Formatting.GOLD, Formatting.DARK_GREEN
    };

    private static boolean cachedMatchActive = false;
    private static String cachedWinCondition = "score";
    private static int cachedWinScore = StructureRaceConfig.WIN_SCORE;
    private static long lastAnnouncedSeconds = -1;
    private static int tickCounter;

    private StructureRaceEvents() {}

    // ==================== 事件注册 ====================

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(StructureRaceEvents::onServerTick);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                onPlayerSpawn(handler.getPlayer(), true));

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                onPlayerDisconnect(handler.getPlayer()));

        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) ->
                onPlayerSpawn(newPlayer, false));

        // 聊天消息：比赛进行中普通消息仅队友可见，`!` 前缀为全局消息
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(StructureRaceEvents::handleChatMessage);

        // 队伍选择器：右键指南针（带标记）打开选队 GUI；语言选择器：右键下界之星（带标记）打开语言 GUI
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClient) return TypedActionResult.pass(player.getStackInHand(hand));
            if (!(player instanceof ServerPlayerEntity sp)) return TypedActionResult.pass(player.getStackInHand(hand));
            ItemStack stack = player.getStackInHand(hand);
            if (stack.isOf(Items.COMPASS) && stack.hasNbt()
                    && stack.getNbt().getBoolean(StructureRaceConfig.TEAM_SELECTOR_TAG)) {
                TeamSelectorScreenHandler.open(sp);
                return TypedActionResult.success(stack);
            }
            if (stack.isOf(Items.NETHER_STAR) && stack.hasNbt()
                    && stack.getNbt().getBoolean(StructureRaceConfig.LANGUAGE_SELECTOR_TAG)) {
                LanguageSelectorScreenHandler.open(sp);
                return TypedActionResult.success(stack);
            }
            // 设置修改器：右键红石粉（带标记）打开比赛设置界面；仅管理员可用
            if (stack.isOf(Items.REDSTONE) && stack.hasNbt()
                    && stack.getNbt().getBoolean(StructureRaceConfig.SETTINGS_SELECTOR_TAG)) {
                if (sp.hasPermissionLevel(2)) {
                    SettingsScreenHandler.open(sp);
                } else {
                    sp.sendMessage(Text.literal(Lang.get(sp,
                            "§c你没有权限修改比赛设置（需要 OP 权限 2）。",
                            "§cYou do not have permission to change match settings (OP 2 required).")), false);
                }
                return TypedActionResult.success(stack);
            }
            return TypedActionResult.pass(stack);
        });

        // 机制4：维度进入奖励（轮询检测维度变化）

        // 机制2：击杀敌对怪物
        ServerLivingEntityEvents.AFTER_DEATH.register(StructureRaceEvents::onEntityDeath);

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            PLAYER_STATES.clear();
            playerTeamMap.clear();
            SCORE_LOG.clear();
            lastAnnouncedSeconds = -1;
            tickCounter = 0;
            lobbyInitialized = false;
            pendingEndTicks = -1;
            pendingEndServer = null;
            preStartCountdownTicks = -1;
            lastCountdownSecond = -1;
            lobbyWaitPos = null;
            fireworkWavesLeft = 0;
            fireworkWaveTicks = 0;
            previousRankings.clear();
            leadTrackingActive = false;
            LOGGER.info("[StructureRace] 新世界服务器启动，内存竞速状态已重置。");
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            ensureLobbyPlatform(server);
            prepareLobbyWaitPoint(server);
            refreshAllTeamScoreboards(server);
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            try {
                ServerWorld ow = server.getOverworld();
                if (ow != null) {
                    ow.getPersistentStateManager().save();
                    LOGGER.info("[StructureRace] 竞速存档数据已强制保存。");
                }
            } catch (Exception e) {
                LOGGER.warn("[StructureRace] 保存竞速数据失败: {}", e.getMessage());
            }
        });

        LOGGER.info("[StructureRace] V2.5 事件监听器已注册完成。");
    }

    // ==================== Tick 回调 ====================

    private static void onServerTick(MinecraftServer server) {
        tickCounter++;
        tickMatchTimer(server);

        // 开赛倒计时：5 秒倒计时后正式开赛（title 提示，同胜利结算样式）
        if (preStartCountdownTicks > 0) {
            preStartCountdownTicks--;
            long sec = (preStartCountdownTicks + 19) / 20;
            if (sec != lastCountdownSecond) {
                lastCountdownSecond = sec;
                if (sec > 0) {
                    for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                        sendTitle(p, "§e§l" + sec,
                                Lang.get(p, "§r比赛即将开始…", "§rStarting soon…"), 5, 15, 5);
                    }
                }
            }
            if (preStartCountdownTicks == 0) {
                preStartCountdownTicks = -1;
                try {
                    executeMatchStart(server);
                } catch (Exception e) {
                    LOGGER.error("[StructureRace] 开赛流程异常：{}", e);
                }
            }
        }

        // 大厅维度保持永远白天（每 20 tick 固定正午时刻），并清理被丢出的大厅保护物品掉落物
        if (tickCounter % 20 == 0) {
            ServerWorld lobby = server.getWorld(LOBBY_KEY);
            if (lobby != null) {
                lobby.setTimeOfDay(6000);
                for (net.minecraft.entity.Entity e : lobby.iterateEntities()) {
                    if (e instanceof ItemEntity ie && isProtectedLobbyItem(ie.getStack())) {
                        ie.discard();
                    }
                }
            }
            checkLeadChanges(server); // 机制7：队伍反超提醒
        }

        // 比赛结算延迟倒计时：到点后传送回大厅并放烟花
        if (pendingEndTicks > 0) {
            pendingEndTicks--;
            if (pendingEndTicks == 0) {
                pendingEndTicks = -1;
                MinecraftServer endServer = pendingEndServer;
                pendingEndServer = null;
                executeEndResult(endServer);
            }
        }

        // 胜利烟花多波次：每间隔放一波，延长庆祝时间
        if (fireworkWavesLeft > 0) {
            fireworkWaveTicks--;
            if (fireworkWaveTicks <= 0) {
                spawnVictoryFireworks(server);
                fireworkWavesLeft--;
                fireworkWaveTicks = FIREWORK_WAVE_INTERVAL;
            }
        }

        // 机制5：落后队伍速度补偿（每 100 tick）
        if (tickCounter % 100 == 0) {
            applySpeedCompensation(server);
        }

        // 大厅维护：清除大厅维度的敌对实体（史莱姆/僵尸等），保持纯净虚空
        if (tickCounter % 100 == 0) {
            ServerWorld lobby = server.getWorld(LOBBY_KEY);
            if (lobby != null) {
                for (net.minecraft.entity.Entity e : lobby.iterateEntities()) {
                    if (e instanceof Monster && e.isAlive()) {
                        e.remove(net.minecraft.entity.Entity.RemovalReason.DISCARDED);
                    }
                }
            }
        }

        ServerWorld overworld = server.getOverworld();
        StructureRaceState saveState = StructureRaceState.get(overworld);

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            PlayerState pst = PLAYER_STATES.get(player.getUuid());

            // 开赛倒计时阶段：所有玩家留在大厅等待（尚未传送/清背包）
            if (preStartCountdownTicks > 0) {
                if (pst != null) pst.pendingLobby = false;
                if (player.getServerWorld().getRegistryKey() != LOBBY_KEY) {
                    teleportToLobby(player);
                }
                continue;
            }

            // 准备/结束阶段：玩家必须待在大厅（冒险模式 + 选队装备）；擅自离开大厅的强制送回（OP 豁免）
            if (!cachedMatchActive) {
                // 结算延迟期间（宣布结果到回大厅的 10 秒）不拉回，让玩家在主世界看完 title
                if (pendingEndTicks > 0) continue;
                // 进服等待加载阶段：玩家在主世界地底等待点，倒计时后送大厅
                if (pst != null && pst.pendingLobby) {
                    pst.joinTicks++;
                    // 限制移动：每 tick 将玩家固定回等待点中心（配合失明/缓慢效果）
                    if (lobbyWaitPos != null) {
                        player.teleport(server.getOverworld(),
                                lobbyWaitPos.getX() + 0.5, lobbyWaitPos.getY() + 1, lobbyWaitPos.getZ() + 0.5,
                                player.getYaw(), player.getPitch());
                    }
                    giveWaitEffects(player);
                    // 大提示（同胜利提示样式）：地形加载中 + 每秒倒计时
                    int left = Math.max(1, (StructureRaceConfig.JOIN_WAIT_TICKS - pst.joinTicks) / 20 + 1);
                    if (pst.joinTicks % 20 == 0) {
                        sendTitle(player, Lang.get(player, "§e§l地形加载中…", "§e§lLoading terrain…"),
                                Lang.get(player, "§r" + left + " 秒后进入大厅", "§rEntering lobby in " + left + "s"),
                                5, 15, 5);
                    }
                    if (pst.joinTicks >= StructureRaceConfig.JOIN_WAIT_TICKS) {
                        pst.pendingLobby = false;
                        removeWaitEffects(player); // 进入大厅：清除失明/缓慢，解除移动限制
                        teleportToLobby(player);
                        sendTitle(player, Lang.get(player, "§a欢迎来到结构竞速大厅！", "§aWelcome to the lobby!"),
                                Lang.get(player, "§r请用指南针组队。", "§rUse the compass to join a team."),
                                10, 60, 10);
                        sendLobbyHint(player);
                    }
                    continue;
                }
                if (player.age % 40 == 0) {
                    ensureLobbyGear(player);
                }
                if (player.age % 20 == 0) {
                    // 不在大厅：拉回大厅（等待阶段不允许离开，OP 也不例外）；在大厅：防止掉下平台（虚空）
                    try {
                        if (player.getServerWorld().getRegistryKey() != LOBBY_KEY) {
                            teleportToLobby(player);
                        } else if (player.getY() < LOBBY_PLATFORM_Y) {
                            teleportToLobby(player);
                        }
                    } catch (Exception e) {
                        LOGGER.warn("[StructureRace] 拉回玩家 {} 到大厅失败: {}", player.getEntityName(), e.getMessage());
                    }
                }
                continue;
            }

            if (player.isSpectator()) continue;
            // 观众（无队伍）不参与任何机制
            if (getPlayerTeam(saveState, player.getUuid()) == null) continue;

            if (player.age % 20 == 0) {
                trackDistance(player, saveState); // 机制1
            }
            if (player.age % StructureRaceConfig.CHECK_INTERVAL_TICKS == 0) {
                checkDimensionChange(player, saveState); // 机制4
                checkPlayerStructure(player, saveState);
                checkPlayerBiome(player, saveState);
                maybeGiveDirectionHint(player, saveState); // 机制6
            }
        }
    }

    // ==================== 限时制倒计时 ====================

    private static void tickMatchTimer(MinecraftServer server) {
        ServerWorld overworld = server.getOverworld();
        if (overworld == null) return;

        StructureRaceState state = StructureRaceState.get(overworld);
        cachedMatchActive = state.matchActive;
        cachedWinCondition = state.winCondition;
        cachedWinScore = state.winScore;

        if (!"timer".equals(state.winCondition) || !state.matchActive) return;
        // 开赛倒计时阶段不计时（真正开赛时 matchStartTick 才更新）
        if (preStartCountdownTicks > 0) return;

        long currentTick = overworld.getTime();
        long elapsed = currentTick - state.matchStartTick;
        long remainingTicks = state.matchDurationTicks - elapsed;

        if (remainingTicks <= 0) {
            endTimerMatch(server, state);
            return;
        }

        long remainingSeconds = remainingTicks / 20;
        boolean shouldAnnounce = (remainingSeconds > 0 && remainingSeconds % 60 == 0)
                || (remainingSeconds <= 30 && remainingSeconds % 10 == 0);
        if (shouldAnnounce && remainingSeconds != lastAnnouncedSeconds) {
            lastAnnouncedSeconds = remainingSeconds;
            broadcastLang(server,
                    "§e⏰ §r距比赛结束还有 §6" + formatSeconds(remainingSeconds) + "§r！",
                    "§e⏰ §r" + formatSecondsEn(remainingSeconds) + " left until the match ends!");
        }
    }

    private static void endTimerMatch(MinecraftServer server, StructureRaceState state) {
        state.matchActive = false;
        state.markDirty();
        cachedMatchActive = false;

        // 平局处理：收集所有并列最高分的队伍
        List<String> topTeams = new ArrayList<>();
        int best = -1;
        for (StructureRaceState.TeamData team : state.getAllTeams().values()) {
            if (team.totalScore > best) {
                best = team.totalScore;
                topTeams.clear();
                topTeams.add(team.teamId);
            } else if (team.totalScore == best) {
                topTeams.add(team.teamId);
            }
        }

        // 本局得分明细（调试用）
        dumpScoreLog(server);

        if (topTeams.isEmpty()) {
            broadcastLang(server, "§e⏰ §r比赛结束，无人得分！", "§e⏰ §rMatch over, nobody scored!");
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                teleportToLobby(p);
            }
            return;
        }

        // 统一结算：排名 + title（胜利/失败/平局）+ 10 秒后回大厅放烟花
        boolean tie = topTeams.size() > 1;
        List<String> winnerTeamIds = new ArrayList<>(topTeams);
        startEndResult(server, winnerTeamIds, tie);
        LOGGER.info("[StructureRace] 限时赛结束，最高分: {} ({}分)，平局={}",
                String.join("、", winnerTeamIds), best, tie);
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

    // ==================== 玩家加入/离开 ====================

    private static void onPlayerSpawn(ServerPlayerEntity player, boolean firstJoin) {
        PlayerState state = PLAYER_STATES.computeIfAbsent(player.getUuid(), k -> {
            PlayerState ps = new PlayerState();
            ps.playerName = player.getEntityName();
            return ps;
        });
        state.playerName = player.getEntityName();

        ServerWorld overworld = player.getServer().getOverworld();
        StructureRaceState saveState = StructureRaceState.get(overworld);
        cachedMatchActive = saveState.matchActive;
        cachedWinCondition = saveState.winCondition;
        cachedWinScore = saveState.winScore;

        StructureRaceState.PlayerPersistentData pd = saveState.getExistingPlayerData(player.getUuid());
        if (pd != null) {
            state.discoveredStructures.addAll(pd.discoveredStructures);
            state.discoveredBiomes.addAll(pd.discoveredBiomes);
            state.totalScore = pd.totalScore;
            state.won = pd.won;
            state.killCount = pd.killCount;
            state.lastFindTime = pd.lastFindTime;
        }
        // 新玩家（无持久化记录）或上次记录为 0：立即开始个人指引计时（开局无冷却）
        if (state.lastFindTime == 0) {
            state.lastFindTime = overworld.getTime();
        }

        // 准备/结束阶段：玩家出生在大厅玻璃平台，冒险模式，持有选队指南针与规则书
        if (!cachedMatchActive) {
            player.changeGameMode(GameMode.ADVENTURE);
            ensureLobbyGear(player);
            if (firstJoin) {
                // 首次进服：先到主世界地底等待点加载世界数据（约 4 秒），再进大厅，
                // 避免「主世界区块尚未生成完就跨维度传送到大厅」导致维度数据串台
                state.pendingLobby = true;
                state.joinTicks = 0;
                teleportToWaitPoint(player);
                giveSaturation(player);
                giveWaitEffects(player); // 失明 + 缓慢，限制移动
                return;
            }
            // 死亡重生/重连：直接回大厅
            try {
                teleportToLobbyIfNotThere(player);
                sendLobbyHint(player);
            } catch (Exception e) {
                LOGGER.warn("[StructureRace] 传送玩家 {} 到大厅失败: {}", player.getEntityName(), e.getMessage());
            }
        }

        // 比赛进行中（含开赛倒计时）：无队伍玩家 = 主世界旁观者（观众）；倒计时阶段暂不处理，等开赛时统一传送
        StructureRaceState.TeamData team = saveState.getTeamByMember(player.getUuid());
        if (cachedMatchActive && preStartCountdownTicks <= 0 && team == null) {
            if (player.getServerWorld().getRegistryKey() != World.OVERWORLD) {
                teleportToOverworldSpawn(player);
            }
            player.changeGameMode(GameMode.SPECTATOR);
            LOGGER.info("[StructureRace] 玩家 {} 未入队（观众），已设为旁观者。", player.getEntityName());
        }

        if (team != null) {
            playerTeamMap.put(player.getUuid(), team.teamId);
            addPlayerToScoreboardTeam(player, team);
        }

        // 计分板：侧边栏按队伍显示（每队一条，无人队伍不显示）
        refreshAllTeamScoreboards(player.getServer());

        LOGGER.info("[StructureRace] 玩家 {} 加入：队伍={}, 显示分={}, won={}",
                player.getName().getString(), team != null ? team.teamId : "观众",
                team != null ? team.totalScore : 0, state.won);
    }

    private static void onPlayerDisconnect(ServerPlayerEntity player) {
        // 移除内存状态：重进时从持久化数据干净重建，保证断线重连与服务器重启行为一致
        PLAYER_STATES.remove(player.getUuid());
        playerTeamMap.remove(player.getUuid());
        // 玩家退出可能导致队伍变空，刷新队伍计分板
        refreshAllTeamScoreboards(player.getServer());
    }
    // ==================== 大厅（独立维度） ====================

    /** 生成大厅 40x40 玻璃平台（仅一次；平台外为虚空） */
    private static void ensureLobbyPlatform(MinecraftServer server) {
        if (lobbyInitialized) return;
        ServerWorld lobby = server.getWorld(LOBBY_KEY);
        if (lobby == null) {
            LOGGER.warn("[StructureRace] 大厅维度未加载，跳过平台生成。");
            return;
        }
        int half = LOBBY_PLATFORM_SIZE / 2;
        for (int x = -half; x < half; x++) {
            for (int z = -half; z < half; z++) {
                BlockPos pos = new BlockPos(x, LOBBY_PLATFORM_Y, z);
                if (lobby.getBlockState(pos).isAir()) {
                    lobby.setBlockState(pos, Blocks.GLASS.getDefaultState());
                }
            }
        }
        // 平台边缘两格高的隐形屏障墙，防止玩家掉落
        for (int i = -half; i < half; i++) {
            placeBarrierWall(lobby, i, -half);
            placeBarrierWall(lobby, i, half - 1);
            placeBarrierWall(lobby, -half, i);
            placeBarrierWall(lobby, half - 1, i);
        }
        // 比赛设置不再用命令方块站：管理员通过背包中的红石粉「设置修改器」或 /race settings 调整
        lobbyInitialized = true;
        LOGGER.info("[StructureRace] 大厅玻璃平台已生成 ({}x{}，y={}，含隐形屏障墙)",
                LOBBY_PLATFORM_SIZE, LOBBY_PLATFORM_SIZE, LOBBY_PLATFORM_Y);
    }

    private static void placeBarrierWall(ServerWorld lobby, int x, int z) {
        for (int dy = 0; dy <= 1; dy++) {
            BlockPos pos = new BlockPos(x, LOBBY_PLATFORM_Y + 1 + dy, z);
            if (lobby.getBlockState(pos).isAir()) {
                lobby.setBlockState(pos, Blocks.BARRIER.getDefaultState());
            }
        }
    }

    /**
     * 比赛设置已不再使用命令方块站：管理员可在大厅用背包中的「设置修改器（红石粉）」右键打开设置界面
     * （等价于 /race settings），或直接输入 /race settings；获胜分数按 ±25 分、限时时长按 ±10 分钟步进调整。
     */

    /** 传送玩家到大厅玻璃平台中心 */
    private static void teleportToLobby(ServerPlayerEntity player) {
        ServerWorld lobby = player.getServer().getWorld(LOBBY_KEY);
        if (lobby == null) return;
        ensureLobbyPlatform(player.getServer());
        player.teleport(lobby, 0.5, LOBBY_PLATFORM_Y + 2, 0.5, player.getYaw(), player.getPitch());
        // 重生点改为大厅（脚在玻璃上方，避免重生时卡进玻璃方块）
        player.setSpawnPoint(LOBBY_KEY, new BlockPos(0, LOBBY_PLATFORM_Y + 2, 0), 0.0f, true, false);
    }

    /** 玩家不在大厅时传送到大厅（准备/结束阶段入场用） */
    private static void teleportToLobbyIfNotThere(ServerPlayerEntity player) {
        if (player.getServerWorld().getRegistryKey() == LOBBY_KEY) return;
        teleportToLobby(player);
    }

    /** 传送玩家到主世界出生点（比赛开始 / 观众进入主世界用） */
    private static void teleportToOverworldSpawn(ServerPlayerEntity player) {
        ServerWorld ow = player.getServer().getOverworld();
        BlockPos spawn = ow.getSpawnPos();
        player.teleport(ow, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5,
                player.getYaw(), player.getPitch());
    }

    // ==================== 进服等待点（主世界地底 y=-64 基岩层） ====================

    /**
     * 在主世界出生点正下方地底（基岩层 y=-64）准备一个 7x7 玻璃等待区：
     * 清空 -63/-62 两层空气、-64 铺玻璃补洞（基岩保留作地板）、
     * 顶部 -61 全封玻璃顶（防岩浆/掉落物）、四周两格玻璃围栏。
     * 首次进服玩家在此等待世界数据加载完成后才进入大厅，避免维度数据串台。
     */
    private static void prepareLobbyWaitPoint(MinecraftServer server) {
        if (lobbyWaitPos != null) return; // 已生成过则跳过（避免重复写方块）
        ServerWorld ow = server.getOverworld();
        BlockPos spawn = ow.getSpawnPos();
        ow.getChunk(spawn.getX() >> 4, spawn.getZ() >> 4, net.minecraft.world.chunk.ChunkStatus.FULL, true);
        int wx = spawn.getX(), wz = spawn.getZ();
        int y0 = LOBBY_WAIT_Y;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                ow.setBlockState(new BlockPos(wx + dx, y0 + 1, wz + dz), Blocks.AIR.getDefaultState(), 3);
                ow.setBlockState(new BlockPos(wx + dx, y0 + 2, wz + dz), Blocks.AIR.getDefaultState(), 3);
                // 顶部玻璃顶（y0+3 = y=-61），防止岩浆/滴水砸到等待中的玩家
                ow.setBlockState(new BlockPos(wx + dx, y0 + 3, wz + dz), Blocks.GLASS.getDefaultState(), 3);
                BlockPos floor = new BlockPos(wx + dx, y0, wz + dz);
                if (ow.getBlockState(floor).isAir() || ow.getBlockState(floor).isReplaceable()) {
                    ow.setBlockState(floor, Blocks.GLASS.getDefaultState(), 3);
                }
            }
        }
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = y0 + 1; dy <= y0 + 2; dy++) {
                ow.setBlockState(new BlockPos(wx + dx, dy, wz - 3), Blocks.GLASS.getDefaultState(), 3);
                ow.setBlockState(new BlockPos(wx + dx, dy, wz + 3), Blocks.GLASS.getDefaultState(), 3);
            }
        }
        for (int dz = -3; dz <= 3; dz++) {
            for (int dy = y0 + 1; dy <= y0 + 2; dy++) {
                ow.setBlockState(new BlockPos(wx - 3, dy, wz + dz), Blocks.GLASS.getDefaultState(), 3);
                ow.setBlockState(new BlockPos(wx + 3, dy, wz + dz), Blocks.GLASS.getDefaultState(), 3);
            }
        }
        lobbyWaitPos = new BlockPos(wx, y0, wz);
        LOGGER.info("[StructureRace] 进服等待点已就绪：主世界出生点地底 ({}, {}, {})，含玻璃顶", wx, y0, wz);
    }

    /** 传送玩家到等待点（站立于 y=-64 地板上方），并确保等待点已生成 */
    private static void teleportToWaitPoint(ServerPlayerEntity player) {
        ServerWorld ow = player.getServer().getOverworld();
        prepareLobbyWaitPoint(player.getServer());
        BlockPos w = lobbyWaitPos;
        if (w == null) {
            PlayerState ps = PLAYER_STATES.get(player.getUuid());
            if (ps != null) ps.pendingLobby = false;
            teleportToLobby(player);
            return;
        }
        player.teleport(ow, w.getX() + 0.5, w.getY() + 1, w.getZ() + 0.5,
                player.getYaw(), player.getPitch());
        // 等待期间死亡重生点也设在等待区，避免意外死亡后掉出
        player.setSpawnPoint(World.OVERWORLD, w, 0.0f, true, false);
    }

    /** 给予无限时长饱和（大厅 / 等待点使用，开赛时移除） */
    private static void giveSaturation(ServerPlayerEntity player) {
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.SATURATION, -1, 0, false, true, true));
    }

    /** 等待点效果：失明 + 缓慢（255 级，几乎无法移动），限制玩家在等待区移动 */
    private static void giveWaitEffects(ServerPlayerEntity player) {
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, -1, 0, false, true, true));
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, -1, 255, false, true, true));
    }

    /** 移除等待点效果（进入大厅时调用） */
    private static void removeWaitEffects(ServerPlayerEntity player) {
        player.removeStatusEffect(StatusEffects.BLINDNESS);
        player.removeStatusEffect(StatusEffects.SLOWNESS);
    }

    // ==================== 大厅装备（选队指南针 + 规则书） ====================

    /** 准备阶段：确保玩家持有选队指南针、语言选择器、规则书，管理员另有设置修改器；且为冒险模式（防丢：每 2 秒补发一次） */
    private static void ensureLobbyGear(ServerPlayerEntity player) {
        if (player.getServerWorld().getRegistryKey() != LOBBY_KEY) return;
        giveSaturation(player); // 大厅玩家保持饱和（不饥饿）
        PlayerInventory inv = player.getInventory();
        boolean hasCompass = false;
        boolean hasBook = false;
        boolean hasLang = false;
        boolean hasSettings = false;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isOf(Items.COMPASS) && s.hasNbt()
                    && s.getNbt().getBoolean(StructureRaceConfig.TEAM_SELECTOR_TAG)) {
                hasCompass = true;
            }
            if (s.isOf(Items.WRITTEN_BOOK) && s.hasNbt()
                    && "structure_race".equals(s.getNbt().getString("author"))) {
                hasBook = true;
            }
            if (s.isOf(Items.NETHER_STAR) && s.hasNbt()
                    && s.getNbt().getBoolean(StructureRaceConfig.LANGUAGE_SELECTOR_TAG)) {
                hasLang = true;
            }
            if (s.isOf(Items.REDSTONE) && s.hasNbt()
                    && s.getNbt().getBoolean(StructureRaceConfig.SETTINGS_SELECTOR_TAG)) {
                hasSettings = true;
            }
        }
        if (!hasCompass) inv.offerOrDrop(createTeamSelector(player));
        if (!hasBook) inv.offerOrDrop(createRuleBook(player));
        if (!hasLang) inv.offerOrDrop(createLanguageSelector(player));
        // 设置修改器（红石粉）仅管理员（OP2）持有；普通玩家若持有则移除
        if (player.hasPermissionLevel(2)) {
            if (!hasSettings) inv.offerOrDrop(createSettingsSelector(player));
        } else {
            for (int i = 0; i < inv.size(); i++) {
                ItemStack s = inv.getStack(i);
                if (s.isOf(Items.REDSTONE) && s.hasNbt()
                        && s.getNbt().getBoolean(StructureRaceConfig.SETTINGS_SELECTOR_TAG)) {
                    inv.setStack(i, ItemStack.EMPTY);
                }
            }
        }
        if (player.interactionManager.getGameMode() != GameMode.ADVENTURE) {
            player.changeGameMode(GameMode.ADVENTURE);
        }
    }

    /** 创建队伍选择器（带 NBT 标记的指南针；物品名按玩家语言） */
    public static ItemStack createTeamSelector(ServerPlayerEntity player) {
        ItemStack stack = new ItemStack(Items.COMPASS);
        NbtCompound nbt = new NbtCompound();
        nbt.putBoolean(StructureRaceConfig.TEAM_SELECTOR_TAG, true);
        nbt.putString("structure_race:item", "team_selector");
        stack.setNbt(nbt);
        stack.setCustomName(Text.literal(Lang.get(player,
                "§b队伍选择器 §7(右键打开)", "§bTeam Selector §7(right-click)")));
        return stack;
    }

    /** 创建语言选择器（带 NBT 标记的下界之星；物品名按玩家语言） */
    public static ItemStack createLanguageSelector(ServerPlayerEntity player) {
        ItemStack stack = new ItemStack(Items.NETHER_STAR);
        NbtCompound nbt = new NbtCompound();
        nbt.putBoolean(StructureRaceConfig.LANGUAGE_SELECTOR_TAG, true);
        nbt.putString("structure_race:item", "language_selector");
        stack.setNbt(nbt);
        stack.setCustomName(Text.literal(Lang.get(player,
                "§b语言选择器 §7(右键打开)", "§bLanguage Selector §7(right-click)")));
        return stack;
    }

    /** 创建设置修改器（带 NBT 标记的红石粉；管理员专用，右键打开比赛设置界面，等价 /race settings） */
    public static ItemStack createSettingsSelector(ServerPlayerEntity player) {
        ItemStack stack = new ItemStack(Items.REDSTONE);
        NbtCompound nbt = new NbtCompound();
        nbt.putBoolean(StructureRaceConfig.SETTINGS_SELECTOR_TAG, true);
        nbt.putString("structure_race:item", "settings_selector");
        stack.setNbt(nbt);
        stack.setCustomName(Text.literal(Lang.get(player,
                "§d设置修改器 §7(右键打开比赛设置)", "§dSettings Editor §7(right-click)")));
        return stack;
    }

    /**
     * 大厅保护物品判断：队伍选择器 / 规则书 / 语言选择器 / 设置修改器。
     * 这些物品不可丢弃；丢出时会被 DROP_ITEM 拦截，并清除意外生成的掉落物后自动补发。
     */
    public static boolean isProtectedLobbyItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.isOf(Items.COMPASS) && stack.hasNbt()
                && stack.getNbt().getBoolean(StructureRaceConfig.TEAM_SELECTOR_TAG)) return true;
        if (stack.isOf(Items.NETHER_STAR) && stack.hasNbt()
                && stack.getNbt().getBoolean(StructureRaceConfig.LANGUAGE_SELECTOR_TAG)) return true;
        if (stack.isOf(Items.REDSTONE) && stack.hasNbt()
                && stack.getNbt().getBoolean(StructureRaceConfig.SETTINGS_SELECTOR_TAG)) return true;
        if (stack.isOf(Items.WRITTEN_BOOK) && stack.hasNbt()
                && "structure_race".equals(stack.getNbt().getString("author"))) return true;
        return false;
    }

    /** 创建玩法规则书（写好的书，含玩法/规则/指令/积分关系；按玩家语言生成） */
    private static ItemStack createRuleBook(ServerPlayerEntity player) {
        ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
        NbtCompound nbt = new NbtCompound();
        nbt.putString("title", Lang.get(player, "结构竞速玩法指南", "Structure Race Guide"));
        nbt.putString("author", "structure_race");
        nbt.putInt("generation", 0);
        nbt.putBoolean("resolved", true);
        NbtList pages = new NbtList();
        for (String page : StructureRaceConfig.getRuleBookPages(Lang.langOf(player))) {
            pages.add(NbtString.of(Text.Serializer.toJson(Text.literal(page))));
        }
        nbt.put("pages", pages);
        stack.setNbt(nbt);
        stack.setCustomName(Text.literal(Lang.get(player, "§6结构竞速·玩法指南", "§6Structure Race Guide")));
        return stack;
    }
    // ==================== 信息书（积分映射 / 竞速进度） ====================

    /** 给玩家打开一本只读书（WrittenBook） */
    public static void openInfoBook(ServerPlayerEntity player, String title, List<String> pageLines) {
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        NbtCompound nbt = new NbtCompound();
        nbt.putString("title", title);
        nbt.putString("author", "structure_race");
        nbt.putInt("generation", 0);
        nbt.putBoolean("resolved", true);
        NbtList pages = new NbtList();
        for (String page : pageLines) {
            pages.add(NbtString.of(Text.Serializer.toJson(Text.literal(page))));
        }
        nbt.put("pages", pages);
        book.setNbt(nbt);
        openBookScreen(player, book);
    }

    /**
     * 可靠地在客户端打开一本 WrittenBook：
     * <ol>
     *   <li>把书临时放入玩家主手槽位，并立即同步背包（客户端本地主手 = 书）；</li>
     *   <li>调用原版 useBook 发送打开书包（客户端按序处理，先收到槽位更新再收到开书包，检查主手为书后打开屏幕）；</li>
     *   <li>恢复原物品并再次同步背包，不影响已打开的书页。</li>
     * </ol>
     */
    public static void openBookScreen(ServerPlayerEntity player, ItemStack book) {
        if (player == null || book == null || book.isEmpty()) return;
        PlayerInventory inv = player.getInventory();
        int slot = inv.selectedSlot;
        ItemStack old = inv.getStack(slot);
        inv.setStack(slot, book);
        player.currentScreenHandler.sendContentUpdates();
        player.useBook(book, Hand.MAIN_HAND);
        inv.setStack(slot, old);
        player.currentScreenHandler.sendContentUpdates();
    }

    /** 打开规则书（玩法指南），任何阶段都可查看 */
    public static void openRuleBookScreen(ServerPlayerEntity player) {
        openBookScreen(player, createRuleBook(player));
    }

    /**
     * 处理客户端按键发来的开书请求（网络接收器在服务端主线程回调）。
     * 对应 StructureRaceNetworking 的 TYPE_* 常量。
     */
    public static void handleOpenBookRequest(ServerPlayerEntity player, String type) {
        if (player == null || player.networkHandler == null) return;
        if (StructureRaceNetworking.TYPE_RULES.equals(type)) {
            openRuleBookScreen(player);
        } else if (StructureRaceNetworking.TYPE_POINT.equals(type)) {
            openPointBook(player);
        } else if (StructureRaceNetworking.TYPE_PROGRESS.equals(type)) {
            openProgressBook(player);
        } else if (StructureRaceNetworking.TYPE_LANGUAGE.equals(type)) {
            LanguageSelectorScreenHandler.open(player);
        }
    }

    /** 打开「积分映射书」：目录 + 结构分值 + 群系分值 + 其他积分规则（按玩家语言） */
    public static void openPointBook(ServerPlayerEntity player) {
        List<String> pages = new ArrayList<>();
        pages.add(Lang.get(player,
                "§l§n积分映射·目录§r\n"
                        + "§l① 结构分值§r 下一页\n"
                        + "§l② 群系分值§r 其后\n"
                        + "§l③ 其他积分§r 最后\n"
                        + "§l【结构规则】§r\n"
                        + "发现即加分，同队不\n"
                        + "重复；结构被任意队\n"
                        + "伍发现即占有，\n"
                        + "先到先得。\n"
                        + "§l【群系规则】§r\n"
                        + "首次踏入加分，群系\n"
                        + "不占有，各队可\n"
                        + "分别获得；已探索\n"
                        + "群系不再加分。",
                "§l§nPoint List - Contents§r\n"
                        + "§l1. Structures§r next page\n"
                        + "§l2. Biomes§r after that\n"
                        + "§l3. Other Points§r last\n"
                        + "§l【Structure】§r\n"
                        + "First discovery scores.\n"
                        + "No repeat for a team.\n"
                        + "Claimed by one team =\n"
                        + "no points for others.\n"
                        + "§l【Biomes】§r\n"
                        + "First time in a biome\n"
                        + "scores. Not first-come;\n"
                        + "each team can earn\n"
                        + "separately. Explored\n"
                        + "biomes give no points."));
        // 结构分值
        StringBuilder sb = new StringBuilder(Lang.get(player, "§l① 结构分值§r\n", "§l1. Structures§r\n"));
        int count = 0;
        for (RegistryKey<Structure> key : StructureRaceConfig.STRUCTURE_SCORES.keySet()) {
            int score = StructureRaceConfig.STRUCTURE_SCORES.get(key);
            String name = Lang.structName(player, key.getValue().getPath());
            if (count > 0 && count % 12 == 0) {
                pages.add(sb.toString());
                sb = new StringBuilder(Lang.get(player, "§l① 结构分值(续)§r\n", "§l1. Structures (cont.)§r\n"));
            }
            sb.append(fitLine(name, 15)).append(' ').append(score).append('\n');
            count++;
        }
        pages.add(sb.toString());
        // 群系分值
        sb = new StringBuilder(Lang.get(player, "§l② 群系分值§r\n", "§l2. Biomes§r\n"));
        count = 0;
        for (RegistryKey<Biome> key : StructureRaceConfig.BIOME_SCORES.keySet()) {
            int score = StructureRaceConfig.BIOME_SCORES.get(key);
            String name = Lang.biomeName(player, key.getValue().toString());
            if (count > 0 && count % 12 == 0) {
                pages.add(sb.toString());
                sb = new StringBuilder(Lang.get(player, "§l② 群系分值(续)§r\n", "§l2. Biomes (cont.)§r\n"));
            }
            sb.append(fitLine(name, 15)).append(' ').append(score).append('\n');
            count++;
        }
        pages.add(sb.toString());
        // 其他积分规则
        sb = new StringBuilder(Lang.get(player, "§l③ 其他积分规则§r\n", "§l3. Other Points§r\n"));
        if (Lang.isEn(player)) {
            sb.append("Distance: +1 per 500\nblocks (boats not\ncounted)\n\n");
            sb.append("Kills: +1 per 10 hostile\nmobs (ranged included)\n\n");
            sb.append("Dimensions: Nether +10\nEnd +20 (once per team)\n\n");
            sb.append("Mansion: with map +50\nwithout map +30");
        } else {
            sb.append("里程：每500格+1分\n坐船行驶不计\n\n");
            sb.append("击杀：每10只敌对怪物\n+1分（含远程击杀）\n\n");
            sb.append("维度：首次进入下界+10\n首次进入末地+20\n（每队各一次）\n\n");
            sb.append("府邸：有探险家地图+50\n无地图+30");
        }
        pages.add(sb.toString());
        openInfoBook(player, Lang.get(player, "积分映射", "Point List"), pages);
    }


    /** 打开「进度书」：直接显示已发现结构、已探索群系、未找到的可加分群系（按玩家语言） */
    public static void openProgressBook(ServerPlayerEntity player) {
        StructureRaceState state = StructureRaceState.get(player.getServer().getOverworld());
        StructureRaceState.TeamData team = state.getTeamByMember(player.getUuid());
        List<String> pages = new ArrayList<>();
        boolean en = Lang.isEn(player);
        if (team == null) {
            pages.add(Lang.get(player,
                    "§l§n竞速进度§r\n\n你尚未加入任何队伍。\n加入队伍后才能查看\n本队的探索进度。\n\n可用 §1/race join <颜色>§r\n或右键指南针组队。\n\n§7按 §1U§r 键可刷新本页",
                    "§l§nRace Progress§r\n\nYou are not on any\nteam yet. Join a team\nto view your progress.\n\nUse §1/race join <color>§r\nor right-click the compass.\n\n§7Press §1U§r to refresh"));
            openInfoBook(player, Lang.get(player, "竞速进度", "Progress"), pages);
            return;
        }
        // 已找到结构及数量（直接从结构开始，无首页摘要/队员名单）
        Map<String, Integer> structCount = new HashMap<>();
        for (String uniqueId : team.discoveredStructures) {
            structCount.merge(extractRegistryName(uniqueId), 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder("§l").append(en ? "[Structures Found]" : "【已发现结构】").append("§r\n");
        if (structCount.isEmpty()) {
            sb.append(en ? "(none)\n" : "（暂无）\n");
        } else {
            int c = 0;
            for (Map.Entry<String, Integer> e : structCount.entrySet()) {
                String name = Lang.structName(player, e.getKey());
                sb.append(fitLine(name, 15)).append(" x").append(e.getValue()).append('\n');
                if (++c % 12 == 0) {
                    pages.add(sb.toString());
                    sb = new StringBuilder("§l").append(en ? "[Structures (cont.)]" : "【已发现结构(续)】").append("§r\n");
                }
            }
        }
        pages.add(sb.toString());
        // 已探索群系
        sb = new StringBuilder("§l").append(en ? "[Biomes Explored]" : "【已探索群系】").append("§r\n");
        if (team.discoveredBiomes.isEmpty()) {
            sb.append(en ? "(none)\n" : "（暂无）\n");
        } else {
            int c = 0;
            for (String id : team.discoveredBiomes) {
                String name = Lang.biomeName(player, id);
                sb.append(fitLine(name, 15)).append('\n');
                if (++c % 12 == 0) {
                    pages.add(sb.toString());
                    sb = new StringBuilder("§l").append(en ? "[Biomes (cont.)]" : "【已探索群系(续)】").append("§r\n");
                }
            }
        }
        pages.add(sb.toString());
        // 未找到的可加分群系
        sb = new StringBuilder("§l§n").append(en ? "Unexplored Scoring Biomes" : "未找到的可加分群系").append("§r\n");
        int c = 0;
        for (RegistryKey<Biome> key : StructureRaceConfig.BIOME_SCORES.keySet()) {
            String id = key.getValue().toString();
            if (team.discoveredBiomes.contains(id)) continue;
            String name = Lang.biomeName(player, id);
            int score = StructureRaceConfig.BIOME_SCORES.get(key);
            sb.append(fitLine(name, 15)).append(' ').append(score).append('\n');
            if (++c % 12 == 0) {
                pages.add(sb.toString());
                sb = new StringBuilder("§l§n").append(en ? "Biomes (cont.)" : "未找到群系(续)").append("§r\n");
            }
        }
        if (c == 0) sb.append(en ? "(all explored!)\n" : "（全部已探索！）\n");
        pages.add(sb.toString());
        openInfoBook(player, Lang.get(player, "竞速进度", "Progress"), pages);
    }

    /** 书页行宽适配：按显示宽度截断（全角按 2 个半角宽），超出部分以省略号收尾，避免写书页面截断 */
    private static String fitLine(String s, int maxHalfWidth) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += s.charAt(i) > 0xFF ? 2 : 1;
            if (w > maxHalfWidth) return s.substring(0, i) + "…";
        }
        return s;
    }




    // ==================== 结算（title + 排名 + 延迟传送 + 烟花） ====================

    /** 比赛结束后展示「玩家荣誉」榜（最远探索者/结构大师/珍稀发现者/怪物猎人/团队核心/关键发现） */
    private static void showHonors(MinecraftServer server) {
        StructureRaceState state = StructureRaceState.get(server.getOverworld());

        String farName = null, structName = null, gemName = null, hunterName = null, coreName = null, keyName = null;
        double farDist = 0;
        int structCnt = 0, gemPts = 0, killCnt = 0, keyPts = 0, corePct = 0;
        String keyZh = null, keyEn = null;
        double coreRatio = -1;

        for (Map.Entry<UUID, PlayerState> e : PLAYER_STATES.entrySet()) {
            PlayerState ps = e.getValue();
            if (ps.playerName == null) continue;
            if (ps.totalDistance > farDist) { farDist = ps.totalDistance; farName = ps.playerName; }
            if (ps.structuresFound > structCnt) { structCnt = ps.structuresFound; structName = ps.playerName; }
            if (ps.structurePoints > gemPts) { gemPts = ps.structurePoints; gemName = ps.playerName; }
            if (ps.killCount > killCnt) { killCnt = ps.killCount; hunterName = ps.playerName; }
            if (ps.bestEventPoints > keyPts) {
                keyPts = ps.bestEventPoints; keyName = ps.playerName;
                keyZh = ps.bestEventNameZh; keyEn = ps.bestEventNameEn;
            }
            StructureRaceState.TeamData team = state.getTeamByMember(e.getKey());
            if (team != null && team.totalScore > 0) {
                double ratio = (double) ps.personalScore / team.totalScore;
                if (ratio > coreRatio) {
                    coreRatio = ratio;
                    coreName = ps.playerName;
                    corePct = (int) Math.round(ratio * 100);
                }
            }
        }
        String distFmt = String.format("%,.0f", farDist);
        String dash = "--";

        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            p.sendMessage(Text.literal(Lang.get(p,
                    "§e§l⭐ 玩家荣誉 §r", "§e§l⭐ Player Honors §r")), false);
            p.sendMessage(Text.literal(Lang.get(p,
                    "§6🧭 最远探索者 §f" + (farName != null ? farName : dash) + "§7 - §e" + distFmt + " 格",
                    "§6🧭 Farthest Explorer §f" + (farName != null ? farName : dash) + "§7 - §e" + distFmt + " blocks")), false);
            p.sendMessage(Text.literal(Lang.get(p,
                    "§6🏰 结构发现大师 §f" + (structName != null ? structName : dash) + "§7 - §e" + structCnt + " 个结构",
                    "§6🏰 Structure Master §f" + (structName != null ? structName : dash) + "§7 - §e" + structCnt + " structures")), false);
            p.sendMessage(Text.literal(Lang.get(p,
                    "§6💎 珍稀发现者 §f" + (gemName != null ? gemName : dash) + "§7 - §e" + gemPts + " 结构积分",
                    "§6💎 Rarest Finder §f" + (gemName != null ? gemName : dash) + "§7 - §e" + gemPts + " structure points")), false);
            p.sendMessage(Text.literal(Lang.get(p,
                    "§6⚔️ 怪物猎人 §f" + (hunterName != null ? hunterName : dash) + "§7 - §e" + killCnt + " 击杀",
                    "§6⚔️ Monster Hunter §f" + (hunterName != null ? hunterName : dash) + "§7 - §e" + killCnt + " kills")), false);
            p.sendMessage(Text.literal(Lang.get(p,
                    "§6🤝 团队核心 §f" + (coreName != null ? coreName : dash) + "§7 - §e" + corePct + "% 队伍贡献",
                    "§6🤝 Team Core §f" + (coreName != null ? coreName : dash) + "§7 - §e" + corePct + "% team contribution")), false);
            p.sendMessage(Text.literal(Lang.get(p,
                    "§6📡 关键发现 §f" + (keyName != null ? keyName : dash) + "§7 - §e" + (keyZh != null ? keyZh : "-") + " +" + keyPts + " 分",
                    "§6📡 Key Discovery §f" + (keyName != null ? keyName : dash) + "§7 - §e" + (keyEn != null ? keyEn : "-") + " +" + keyPts + " pts")), false);
        }
        LOGGER.info("[StructureRace] 赛后荣誉已展示：最远={} {}格, 结构={} {}个, 珍稀={} {}分, 猎人={} {}杀, 核心={} {}%, 关键={} {} +{}",
                farName, distFmt, structName, structCnt, gemName, gemPts, hunterName, killCnt,
                coreName, corePct, keyName, keyZh, keyPts);
    }

    /** 开始比赛结算：广播排名、按阵营显示 title，10 秒后传送回大厅并放烟花 */
    private static void startEndResult(MinecraftServer server, List<String> winnerTeamIds, boolean tie) {
        // 聊天栏：每队分数与排名（按各自语言）
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            p.sendMessage(Text.literal(Lang.get(p,
                    StructureRaceConfig.BROADCAST_PREFIX + "§6====== 比赛结束·排名 ======",
                    StructureRaceConfig.BROADCAST_PREFIX + "§6====== Match Over - Rankings ======")), false);
            for (String line : getLeaderboard(server, Lang.langOf(p))) {
                p.sendMessage(Text.literal("§7" + line), false);
            }
        }

        // 展示「玩家荣誉」榜（最远探索者/结构大师/珍稀发现者/怪物猎人/团队核心/关键发现）
        showHonors(server);

        StructureRaceState state = StructureRaceState.get(server.getOverworld());
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            StructureRaceState.TeamData pTeam = state.getTeamByMember(p.getUuid());
            boolean isWinner = pTeam != null && winnerTeamIds.contains(pTeam.teamId);
            if (tie) {
                sendTitle(p, Lang.get(p, "§6§l平局", "§6§lDraw"),
                        Lang.get(p, "§r多个队伍并列第一", "§rMultiple teams tied for first!"), 10, 100, 20);
            } else {
                String wid = winnerTeamIds.get(0);
                String zhWin = "§r" + teamColorCode(wid) + teamZhName(wid) + "§r 获得胜利！";
                String enWin = "§r" + teamColorCode(wid)
                        + StructureRaceConfig.TEAM_NAMES_EN.getOrDefault(wid, wid) + "§r Team wins!";
                if (isWinner) {
                    sendTitle(p, Lang.get(p, "§a§l恭喜获得胜利！", "§a§lVictory!"),
                            Lang.get(p, zhWin, enWin), 10, 100, 20);
                } else {
                    sendTitle(p, Lang.get(p, "§c§l游戏结束", "§c§lGame Over"),
                            Lang.get(p, zhWin, enWin), 10, 100, 20);
                }
            }
        }

        // 10 秒后传送回大厅并放烟花
        pendingEndTicks = END_RESULT_DELAY_TICKS;
        pendingEndServer = server;
    }

    /** 结算延迟结束：全体回大厅 + 启动多波次胜利烟花 */
    private static void executeEndResult(MinecraftServer server) {
        if (server == null) return;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            teleportToLobby(p);
        }
        // 启动多波次烟花（立即放第一波，之后每隔 2 秒一波）
        fireworkWavesLeft = FIREWORK_WAVES;
        fireworkWaveTicks = 0;
    }

    /** 燃放一波胜利烟花（每波少量，由 onServerTick 周期触发形成多波次庆祝） */
    private static void spawnVictoryFireworks(MinecraftServer server) {
        ServerWorld lobby = server.getWorld(LOBBY_KEY);
        if (lobby == null) return;
        for (int i = 0; i < FIREWORK_PER_WAVE; i++) {
            double x = (Math.random() - 0.5) * 20;
            double z = (Math.random() - 0.5) * 20;
            double y = LOBBY_PLATFORM_Y + 6 + Math.random() * 8;
            ItemStack fw = createRandomFirework();
            FireworkRocketEntity rocket = new FireworkRocketEntity(lobby, 0.5 + x, y, 0.5 + z, fw);
            rocket.setVelocity(0, 0.1 + Math.random() * 0.15, 0);
            lobby.spawnEntity(rocket);
        }
        LOGGER.info("[StructureRace] 比赛结束：大厅烟花燃放一波（剩余 {} 波）。",
                Math.max(0, fireworkWavesLeft - 1));
    }

    private static ItemStack createRandomFirework() {
        ItemStack stack = new ItemStack(Items.FIREWORK_ROCKET);
        NbtCompound fireworks = new NbtCompound();
        NbtList explosions = new NbtList();
        NbtCompound e = new NbtCompound();
        int[] colors = {0xE74C3C, 0x3498DB, 0xF1C40F, 0x2ECC71, 0xFFFFFF, 0x9B59B6, 0xE67E22};
        e.putIntArray("Colors", new int[]{colors[(int) (Math.random() * colors.length)]});
        e.putByte("Type", (byte) (Math.random() * 4));
        explosions.add(e);
        fireworks.put("Explosions", explosions);
        NbtCompound nbt = new NbtCompound();
        nbt.put("Fireworks", fireworks);
        stack.setNbt(nbt);
        return stack;
    }

    /** 发送 title（含子标题与渐入/停留/渐出） */
    private static void sendTitle(ServerPlayerEntity player, String title, String subtitle,
                                  int fadeIn, int stay, int fadeOut) {
        player.networkHandler.sendPacket(new TitleFadeS2CPacket(fadeIn, stay, fadeOut));
        player.networkHandler.sendPacket(new TitleS2CPacket(Text.literal(title)));
        if (subtitle != null && !subtitle.isEmpty()) {
            player.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal(subtitle)));
        }
    }

    /** 发送 actionbar 提示 */
    private static void sendActionBar(ServerPlayerEntity player, String message) {
        player.sendMessage(Text.literal(message), true);
    }

    /** 进入大厅后的消息栏提醒：Tab 查看组队 + 快捷键与常用指令引导 */
    private static void sendLobbyHint(ServerPlayerEntity player) {
        player.sendMessage(Text.literal(Lang.get(player,
                "§7提示：按 §eTab§7 查看组队 · 右键 §e指南针§r 选队 · §e下界之星§r 语言",
                "§7Tip: §eTab§7 teams · right-click §ecompass§r pick team · §eN. star§r lang")), false);
        player.sendMessage(Text.literal(Lang.get(player,
                "§7快捷键：§eK§r 规则书 · §eP§r 积分映射 · §eU§r 进度 · §eY§r 语言",
                "§7Hotkeys: §eK§r guide · §eP§r points · §eU§r progress · §eY§r language")), false);
        player.sendMessage(Text.literal(Lang.get(player,
                "§7指令：§e/race start§r 开始比赛 · §e/race settings§r 修改设置 · §e/race status§r 查看状态",
                "§7Commands: §e/race start§r start · §e/race settings§r settings · §e/race status§r status")), false);
        if (player.hasPermissionLevel(2)) {
            player.sendMessage(Text.literal(Lang.get(player,
                    "§7管理员：右键背包中的 §d红石粉§r 可打开比赛设置界面",
                    "§7Admin: right-click the §dredstone§r item to open match settings")), false);
        }
    }

    /** 向所有在线玩家广播双语文本（按各自语言） */
    private static void broadcastLang(MinecraftServer server, String zh, String en) {
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            p.sendMessage(Text.literal(Lang.get(p, zh, en)), false);
        }
    }

    /** 向所有在线玩家广播双语格式化文本（按各自语言） */
    private static void broadcastLang(MinecraftServer server, String zh, String en, Object... args) {
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            p.sendMessage(Text.literal(Lang.fmt(p, zh, en, args)), false);
        }
    }

    // ==================== 聊天消息系统 ====================

    /** 队伍 ID → 颜色代码（如 "§c"），未知队伍用白色 */
    private static String teamColorCode(String teamId) {
        Formatting f = StructureRaceConfig.TEAM_FORMATTING.get(teamId);
        return f != null ? f.toString() : "§f";
    }

    /** 队伍 ID → 中文队名 */
    private static String teamZhName(String teamId) {
        return StructureRaceConfig.TEAM_NAMES_ZH.getOrDefault(teamId, teamId);
    }

    /** 玩家显示名：带其队伍颜色（无队伍/观众为白色） */
    private static String playerDisplayName(ServerPlayerEntity sender, StructureRaceState state) {
        StructureRaceState.TeamData t = state.getTeamByMember(sender.getUuid());
        return (t != null ? teamColorCode(t.teamId) : "§f") + sender.getEntityName() + "§r";
    }

    /**
     * 聊天消息拦截（仅比赛进行中生效）：
     * <ul>
     *   <li>队员普通聊天 = 队聊：仅同队玩家（含自己）可见。</li>
     *   <li>{@code !消息} = 全局消息：所有在线玩家（含观众）可见。</li>
     *   <li>观众（无队伍）普通聊天 = 全局：观众无队聊对象，按全局处理便于交流。</li>
     * </ul>
     * 返回 false 表示取消默认广播，由本方法自行分发。
     */
    private static boolean handleChatMessage(SignedMessage message, ServerPlayerEntity sender,
                                             MessageType.Parameters boundChatType) {
        // 比赛未开始/已结束：保持默认全局聊天
        if (!cachedMatchActive) return true;

        String content = message.getSignedContent();
        boolean global = content.startsWith("!");
        String display = (global ? content.substring(1) : content).trim();
        if (display.isEmpty()) return false;

        StructureRaceState state = StructureRaceState.get(sender.getServer().getOverworld());
        StructureRaceState.TeamData team = state.getTeamByMember(sender.getUuid());

        if (global) {
            // 全局消息：所有人可见
            Text msg = Text.literal(StructureRaceConfig.BROADCAST_PREFIX
                    + Lang.get(sender, "§f[全局] §r", "§f[Global] §r")
                    + playerDisplayName(sender, state) + "§7: §r" + display);
            sender.getServer().getPlayerManager().broadcast(msg, false);
        } else if (team != null) {
            // 队聊：仅同队玩家可见，前缀与玩家名均使用队伍颜色 + 双语队名
            String teamPrefix = Lang.get(sender, "[队聊·", "[Team·")
                    + Lang.teamName(sender, team.teamId) + "] ";
            Text msg = Text.literal(StructureRaceConfig.BROADCAST_PREFIX
                    + teamColorCode(team.teamId) + teamPrefix + "§r"
                    + playerDisplayName(sender, state) + "§7: §r" + display);
            for (ServerPlayerEntity p : sender.getServer().getPlayerManager().getPlayerList()) {
                StructureRaceState.TeamData pTeam = state.getTeamByMember(p.getUuid());
                if (pTeam != null && pTeam.teamId.equals(team.teamId)) {
                    p.sendMessage(msg, false);
                }
            }
        } else {
            // 观众（无队伍）：按全局发送
            Text msg = Text.literal(StructureRaceConfig.BROADCAST_PREFIX
                    + Lang.get(sender, "§7[观众] §r", "§7[Spectator] §r")
                    + playerDisplayName(sender, state) + "§7: §r" + display);
            sender.getServer().getPlayerManager().broadcast(msg, false);
        }
        return false; // 取消默认广播，使用自定义分发
    }

    /** 记录玩家单次最高得分事件（用于赛后荣誉） */
    private static void updateBestEvent(PlayerState ps, String nameZh, String nameEn, int points) {
        if (points > ps.bestEventPoints) {
            ps.bestEventPoints = points;
            ps.bestEventNameZh = nameZh;
            ps.bestEventNameEn = nameEn;
        }
    }

    // ==================== 机制1：跑图里程计分 ====================

    private static void trackDistance(ServerPlayerEntity player, StructureRaceState saveState) {
        PlayerState state = PLAYER_STATES.get(player.getUuid());
        if (state == null || state.won) return;

        BlockPos pos = player.getBlockPos();
        // 机制1：坐船行驶不计里程（防止海上无成本刷里程分）；更新参考点但不累计
        if (player.hasVehicle() && player.getVehicle() instanceof BoatEntity) {
            state.lastDistancePos = pos;
            return;
        }
        if (state.lastDistancePos != null) {
            double dx = pos.getX() - state.lastDistancePos.getX();
            double dz = pos.getZ() - state.lastDistancePos.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            // 单次大位移视为传送（含下界折跃、掉虚空重生），不累计
            if (dist < DISTANCE_TELEPORT_THRESHOLD) {
                state.distanceAccumulator += dist;
                state.totalDistance += dist; // 赛后荣誉：累计跑图距离
                if (state.distanceAccumulator >= DISTANCE_PER_POINT) {
                    StructureRaceState.TeamData team = getPlayerTeam(saveState, player.getUuid());
                    if (team != null) {
                        team.totalScore += 1;
                        state.personalScore += 1;
                        saveState.markDirty();
                        broadcastScore(player, team, "长途跋涉", "Long trek", 1, team.totalScore);
                        refreshTeamScoreboard(player.server, team);
                        checkWinCondition(player, state, team, saveState);
                    }
                    state.distanceAccumulator = 0;
                }
            }
        }
        state.lastDistancePos = pos;
    }

    // ==================== 机制2：击杀怪物计分 ====================

    private static void onEntityDeath(LivingEntity entity, DamageSource source) {
        if (!cachedMatchActive) return;
        if (entity.getServer() == null) return;
        if (!(entity instanceof Monster)) return;

        ServerPlayerEntity killer = null;
        // 直接近战与远程（弓箭/三叉戟/药水等弹射物）击杀都计入
        if (source.getAttacker() instanceof ServerPlayerEntity p) {
            killer = p;
        } else if (source.getSource() instanceof ServerPlayerEntity p) {
            killer = p;
        }
        if (killer == null) return;

        PlayerState state = PLAYER_STATES.get(killer.getUuid());
        if (state == null || state.won) return;

        StructureRaceState saveState = StructureRaceState.get(killer.getServer().getOverworld());
        StructureRaceState.TeamData team = getPlayerTeam(saveState, killer.getUuid());
        if (team == null) return; // 观众不参与

        if (state.killCount >= MAX_KILLS) return;
        state.killCount++;
        StructureRaceState.PlayerPersistentData pd = saveState.getPlayerData(killer.getUuid());
        pd.killCount = state.killCount; // 即使未到加分点也持久化，防止断线丢失

        if (state.killCount % KILLS_PER_POINT == 0) {
            team.totalScore += 1;
            state.personalScore += 1; // 赛后荣誉：个人总分
            saveState.markDirty();
            broadcastScore(killer, team, "消灭怪物浪潮", "Mob wave", 1, team.totalScore);
            refreshTeamScoreboard(killer.server, team);
            checkWinCondition(killer, state, team, saveState);
        }
    }

    // ==================== 机制4：维度进入奖励（轮询检测） ====================

    private static void checkDimensionChange(ServerPlayerEntity player, StructureRaceState saveState) {
        PlayerState state = PLAYER_STATES.get(player.getUuid());
        if (state == null || state.won) return;

        RegistryKey<World> current = player.getServerWorld().getRegistryKey();
        String cur = current.getValue().getPath(); // "overworld" / "the_nether" / "the_end"
        String last = state.lastDimension;
        state.lastDimension = cur;
        // 维度未变化则不重复处理；last==null（首次加入/重启后）时仍需检查当前维度是否应奖励
        if (last != null && last.equals(cur)) return;

        StructureRaceState.TeamData team = getPlayerTeam(saveState, player.getUuid());
        if (team == null) return;

        int score = 0;
        String dimName = null;
        if ("the_nether".equals(cur) && !team.hasEnteredNether) {
            team.hasEnteredNether = true;
            score = NETHER_BONUS;
            dimName = "下界";
        } else if ("the_end".equals(cur) && !team.hasEnteredEnd) {
            team.hasEnteredEnd = true;
            score = END_BONUS;
            dimName = "末地";
        }
        if (score == 0) return;

        team.totalScore += score;
        state.personalScore += score; // 赛后荣誉：个人总分
        saveState.markDirty();
        String dimEn = "the_nether".equals(cur) ? "Nether" : "the End";
        updateBestEvent(state, "踏入" + dimName, "Entered " + dimEn, score);
        broadcastScore(player, team, "踏入" + dimName, "Entered " + dimEn, score, team.totalScore);
        refreshTeamScoreboard(player.server, team);
        checkWinCondition(player, state, team, saveState);
    }

    // ==================== 机制5：落后队伍速度补偿 ====================

    private static void applySpeedCompensation(MinecraftServer server) {
        if (!cachedMatchActive) return;
        StructureRaceState saveState = StructureRaceState.get(server.getOverworld());

        int maxScore = 0;
        for (StructureRaceState.TeamData t : saveState.getAllTeams().values()) {
            if (t.totalScore > maxScore) maxScore = t.totalScore;
        }

        for (StructureRaceState.TeamData team : saveState.getAllTeams().values()) {
            boolean compensate = team.totalScore <= maxScore - COMPENSATION_GAP;
            for (UUID uuid : team.members) {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
                if (p == null || p.isSpectator() || !p.isAlive()) continue;
                PlayerState ps = PLAYER_STATES.get(uuid);
                if (ps == null || ps.won) continue;
                if (!compensate) continue;

                StatusEffectInstance speed = p.getStatusEffect(StatusEffects.SPEED);
                // 已有速度II（或更高）：等效果结束，下轮再补速度I
                if (speed != null && speed.getAmplifier() >= 1) continue;
                // 无速度效果，或只有速度I：给予/刷新速度I（200 tick）
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 200, 0, false, true, true));
                // 提示节流：每 1 分钟仅提示一次，避免刷屏
                long now = p.getServerWorld().getTime();
                if (now - ps.lastCompHintTime >= COMPENSATION_HINT_INTERVAL_TICKS) {
                    ps.lastCompHintTime = now;
                    sendActionBar(p, Lang.get(p,
                            "§a落后补偿：已获得速度提升 I（落后第一名 ≥" + COMPENSATION_GAP + " 分）",
                            "§aComeback bonus: Speed I granted (behind the leader by ≥" + COMPENSATION_GAP + " pts)"));
                }
            }
        }
    }

    // ==================== 机制7：队伍反超提醒 ====================

    /** 排名变化检测（每 20 tick）：超过 40 分阈值后，按场景发 title 提醒（持续约 1 秒） */
    private static void checkLeadChanges(MinecraftServer server) {
        if (!cachedMatchActive) return;
        StructureRaceState state = StructureRaceState.get(server.getOverworld());
        List<StructureRaceState.TeamData> teams = new ArrayList<>();
        for (StructureRaceState.TeamData t : state.getAllTeams().values()) {
            if (!t.members.isEmpty()) teams.add(t);
        }
        if (teams.isEmpty()) return;
        teams.sort(Comparator.comparingInt((StructureRaceState.TeamData t) -> t.totalScore).reversed());

        int maxScore = teams.get(0).totalScore;
        if (maxScore <= LEAD_CHANGE_THRESHOLD) { // 未超过阈值不追踪
            if (leadTrackingActive) {
                leadTrackingActive = false;
                previousRankings.clear();
            }
            return;
        }
        if (!leadTrackingActive) { // 首次进入追踪：记录排名，不触发
            leadTrackingActive = true;
            previousRankings.clear();
            for (int i = 0; i < teams.size(); i++) {
                previousRankings.put(teams.get(i).teamId, i + 1);
            }
            return;
        }

        Map<String, Integer> curRankings = new HashMap<>();
        for (int i = 0; i < teams.size(); i++) {
            curRankings.put(teams.get(i).teamId, i + 1);
        }
        String newLeaderId = teams.get(0).teamId;
        String prevLeaderId = null;
        for (Map.Entry<String, Integer> e : previousRankings.entrySet()) {
            if (e.getValue() == 1) {
                prevLeaderId = e.getKey();
                break;
            }
        }

        for (StructureRaceState.TeamData t : teams) {
            Integer prev = previousRankings.get(t.teamId);
            if (prev == null) continue;
            int cur = curRankings.get(t.teamId);
            if (cur == prev) continue;

            if (cur < prev) { // 排名上升
                if (cur == 1) { // 新领先者
                    broadcastTitleLang(server,
                            "§e§l" + coloredTeamName(newLeaderId) + " 成为新的领先者！",
                            "§e§l" + coloredTeamNameEn(newLeaderId) + " is the new leader!");
                    sendTitleToTeamMembers(server, newLeaderId,
                            "§a§l恭喜成为新的领跑者！", "§a§lYou are the new leader!");
                    if (prevLeaderId != null && !prevLeaderId.equals(newLeaderId)) {
                        sendTitleToTeamMembers(server, prevLeaderId,
                                "§c§l" + coloredTeamName(newLeaderId) + " 超越了您，努力反超！",
                                "§c§l" + coloredTeamNameEn(newLeaderId) + " overtook you. Fight back!");
                    }
                } else { // 上升到第 N 名
                    String surpassedId = findTeamIdByRank(previousRankings, cur, t.teamId);
                    sendTitleToTeamMembers(server, t.teamId,
                            "§a§l超越 " + (surpassedId != null ? coloredTeamName(surpassedId) : "对手")
                                    + "，成为第 " + cur + " 名！",
                            "§a§lPassed " + (surpassedId != null ? coloredTeamNameEn(surpassedId) : "rivals")
                                    + ", now #" + cur + "!");
                }
            } else { // 排名下降
                if (prev == 1) { // 原第一被超越，鼓励反超
                    sendTitleToTeamMembers(server, t.teamId,
                            "§c§l" + coloredTeamName(newLeaderId) + " 超越了您，努力反超！",
                            "§c§l" + coloredTeamNameEn(newLeaderId) + " overtook you. Fight back!");
                } else { // 被某队超越，掉到第 N 名
                    String overtakerId = findTeamIdByRank(curRankings, cur - 1, null);
                    sendTitleToTeamMembers(server, t.teamId,
                            "§c§l被 " + (overtakerId != null ? coloredTeamName(overtakerId) : "对手")
                                    + " 超越，掉到第 " + cur + " 名！",
                            "§c§lOvertaken by "
                                    + (overtakerId != null ? coloredTeamNameEn(overtakerId) : "rivals")
                                    + ", dropped to #" + cur + "!");
                }
            }
        }
        previousRankings.clear();
        previousRankings.putAll(curRankings);
    }

    private static String teamEnName(String teamId) {
        return StructureRaceConfig.TEAM_NAMES_EN.getOrDefault(teamId, teamId);
    }

    /** 带队伍颜色的中文队名（如「§c红队§r」） */
    private static String coloredTeamName(String teamId) {
        return teamColorCode(teamId) + teamZhName(teamId) + "§r";
    }

    /** 带队伍颜色的英文队名 */
    private static String coloredTeamNameEn(String teamId) {
        return teamColorCode(teamId) + teamEnName(teamId) + "§r";
    }

    /** 在排名表 rank 名次处查找队伍（排除 excludeId） */
    private static String findTeamIdByRank(Map<String, Integer> rankings, int rank, String excludeId) {
        for (Map.Entry<String, Integer> e : rankings.entrySet()) {
            if (e.getValue() == rank && !e.getKey().equals(excludeId)) return e.getKey();
        }
        return null;
    }

    /** 全服 title（按各自语言，持续约 3 秒） */
    private static void broadcastTitleLang(MinecraftServer server, String zh, String en) {
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            sendTitle(p, Lang.get(p, zh, en), "", 10, 40, 20);
        }
    }

    /** 给某队伍在线成员发送 title（按各自语言，持续约 3 秒） */
    private static void sendTitleToTeamMembers(MinecraftServer server, String teamId, String zh, String en) {
        StructureRaceState state = StructureRaceState.get(server.getOverworld());
        StructureRaceState.TeamData team = state.getTeam(teamId);
        if (team == null) return;
        for (UUID uuid : team.members) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
            if (p != null) {
                sendTitle(p, Lang.get(p, zh, en), "", 10, 40, 20);
            }
        }
    }

    // ==================== 机制6：迷路指引 ====================

    private static void maybeGiveDirectionHint(ServerPlayerEntity player, StructureRaceState saveState) {
        PlayerState state = PLAYER_STATES.get(player.getUuid());
        if (state == null || state.won) return;

        long gameTime = player.getServerWorld().getTime();
        // 个人计时：距上次发现（或上次提示后的虚拟时间）不足 3 分钟则不提示。
        // 提示时会把 lastFindTime 移到「now + 7 分钟」，从而同时实现「提示后 7 分钟冷却 +
        // 冷却结束后重新开始 3 分钟计时」；开局 lastFindTime=开赛时刻，无开局冷却。
        if (gameTime - state.lastFindTime < HINT_NO_FIND_TICKS) return;
        // 距离过近（≤100 格）后，1 分钟后再查询（节流，避免高频 locate）
        if (gameTime - state.lastHintTime < StructureRaceConfig.HINT_RETRY_TICKS) return;
        state.lastHintTime = gameTime;

        ServerWorld world = player.getServerWorld();
        BlockPos pos = player.getBlockPos();

        try {
            // 用竞速结构 tag 一次性检索最近结构
            BlockPos nearest = world.locateStructure(RACE_STRUCTURES_TAG, pos,
                    StructureRaceConfig.HINT_SEARCH_RADIUS, false);
            double dist = nearest == null ? -1 : Math.sqrt(nearest.getSquaredDistance(pos));

            // 最近结构在 100 格以内：不做提示，1 分钟后再查（玩家很快就能自己找到）
            if (nearest != null && dist <= StructureRaceConfig.HINT_MIN_DISTANCE) {
                return;
            }

            // 给出指引：进入 7 分钟个人冷却（把 lastFindTime 移到未来），并持久化
            state.lastFindTime = gameTime + HINT_COOLDOWN_TICKS;
            saveState.getPlayerData(player.getUuid()).lastFindTime = state.lastFindTime;
            saveState.markDirty();

            if (nearest == null) {
                // 检索半径内没有结构
                player.sendMessage(Text.literal(StructureRaceConfig.BROADCAST_PREFIX + Lang.get(player,
                        "§e周围 " + StructureRaceConfig.HINT_SEARCH_RADIUS
                                + " 格内暂未发现竞速结构，继续探索吧！§7（下次提示需 7 分钟后）§r",
                        "§eNo race structures within " + StructureRaceConfig.HINT_SEARCH_RADIUS
                                + " blocks. Keep exploring!§7 (next hint in 7 min)§r")), false);
                return;
            }

            // 给出具体坐标与距离提示（仅该玩家可见）
            player.sendMessage(Text.literal(StructureRaceConfig.BROADCAST_PREFIX + Lang.get(player,
                    "§e最近的竞速结构在 §6(" + nearest.getX() + ", " + nearest.getZ()
                            + ")§r，距离 §6" + (int) dist
                            + "§r 格（坐标 X=" + nearest.getX() + " Z=" + nearest.getZ()
                            + "），快去探索吧！§7（下次提示需 7 分钟后）§r",
                    "§eNearest race structure: §6(" + nearest.getX() + ", " + nearest.getZ()
                            + ")§r, §6" + (int) dist + "§r blocks away (X=" + nearest.getX()
                            + " Z=" + nearest.getZ() + "). Go explore!§7 (next hint in 7 min)§r")), false);
        } catch (Exception e) {
            LOGGER.warn("[StructureRace] 指引查询失败: {}", e.getMessage());
        }
    }

    // ==================== 结构检测与计分 ====================

    private static void checkPlayerStructure(ServerPlayerEntity player, StructureRaceState saveState) {
        PlayerState state = PLAYER_STATES.get(player.getUuid());
        if (state == null || state.won) return;
        if (player.isSpectator()) return;

        StructureRaceState.TeamData team = getPlayerTeam(saveState, player.getUuid());
        if (team == null) return; // 观众不参与

        long gameTime = player.getServerWorld().getTime();
        if (gameTime - state.lastScoreGameTime < COOLDOWN_TICKS) return;

        BlockPos pos = player.getBlockPos();
        ServerWorld world = player.getServerWorld();

        // 性能优化：该位置不在任何结构引用附近时直接跳过
        StructureAccessor accessor = world.getStructureAccessor();
        if (!accessor.hasStructureReferences(pos)) return;

        Registry<Structure> registry = world.getRegistryManager().get(RegistryKeys.STRUCTURE);
        for (RegistryKey<Structure> structKey : StructureRaceConfig.getTargetStructures()) {
            Integer scoreValue = StructureRaceConfig.STRUCTURE_SCORES.get(structKey);
            if (scoreValue == null) continue;

            Structure structure = registry.get(structKey);
            if (structure == null) continue;

            StructureStart start;
            try {
                start = accessor.getStructureContaining(pos, structure);
            } catch (Exception e) {
                continue;
            }
            if (start == null || !start.hasChildren()) continue;

            String uniqueId = structKey.getValue() + ":" + start.getPos().toLong();

            // 全局独占去重：已被任意队伍发现的实例，其他队伍不再加分
            if (saveState.globallyDiscoveredStructures.contains(uniqueId)) continue;
            if (team.discoveredStructures.contains(uniqueId)) continue;

            // ===== 新结构！加分 =====
            team.discoveredStructures.add(uniqueId);
            saveState.globallyDiscoveredStructures.add(uniqueId);
            // 林地府邸：背包有探险家地图（带探索标记） +50，否则 +30
            int finalScore = scoreValue;
            if (structKey.getValue().getPath().equals("mansion")) {
                finalScore = hasExplorerMap(player) ? 50 : 30;
            }
            String structName = StructureRaceConfig.STRUCTURE_NAMES.getOrDefault(
                    structKey.getValue().getPath(), structKey.getValue().getPath());
            String structNameEn = Lang.structName(player, structKey.getValue().getPath());
            team.totalScore += finalScore;
            state.lastScoreGameTime = gameTime;
            state.lastFindTime = gameTime;
            // 赛后荣誉统计
            state.structuresFound++;
            state.structurePoints += finalScore;
            state.personalScore += finalScore;
            updateBestEvent(state, "发现" + structName, "Found " + structNameEn, finalScore);
            saveState.getPlayerData(player.getUuid()).lastFindTime = gameTime;
            saveState.markDirty();

            refreshTeamScoreboard(player.server, team);

            broadcastScore(player, team, "发现" + structName, "Found " + structNameEn, finalScore, team.totalScore);

            checkWinCondition(player, state, team, saveState);
            return;
        }
    }

    /** 判断玩家背包中是否持有探险家地图（带探索标记的已填充地图） */
    private static boolean hasExplorerMap(ServerPlayerEntity player) {
        for (ItemStack stack : player.getInventory().main) {
            if (stack.isEmpty() || !stack.isOf(Items.FILLED_MAP)) continue;
            MapState mapState = FilledMapItem.getMapState(stack, player.getServerWorld());
            if (mapState != null && mapState.getIcons().iterator().hasNext()) {
                return true;
            }
        }
        return false;
    }

    // ==================== 群系检测与计分 ====================

    private static void checkPlayerBiome(ServerPlayerEntity player, StructureRaceState saveState) {
        PlayerState state = PLAYER_STATES.get(player.getUuid());
        if (state == null || state.won) return;
        if (player.isSpectator()) return;

        StructureRaceState.TeamData team = getPlayerTeam(saveState, player.getUuid());
        if (team == null) return; // 观众不参与

        long gameTime = player.getServerWorld().getTime();
        if (gameTime - state.lastBiomeCheckTime < BIOME_COOLDOWN_TICKS) return;
        state.lastBiomeCheckTime = gameTime;

        BlockPos pos = player.getBlockPos();
        ServerWorld world = player.getServerWorld();
        RegistryEntry<Biome> biomeEntry = world.getBiome(pos);

        Optional<RegistryKey<Biome>> biomeKeyOpt = biomeEntry.getKey();
        if (biomeKeyOpt.isEmpty()) return;
        RegistryKey<Biome> biomeKey = biomeKeyOpt.get();

        Integer scoreValue = StructureRaceConfig.BIOME_SCORES.get(biomeKey);
        if (scoreValue == null) return;

        String biomeId = biomeKey.getValue().toString();
        if (team.discoveredBiomes.contains(biomeId)) return;

        // ===== 新群系！加分 =====
        team.discoveredBiomes.add(biomeId);
        team.totalScore += scoreValue;
        state.personalScore += scoreValue; // 赛后荣誉：个人总分
        saveState.markDirty();

        refreshTeamScoreboard(player.server, team);

        String biomeName = StructureRaceConfig.BIOME_NAMES.getOrDefault(biomeId, biomeId);
        updateBestEvent(state, "探索" + biomeName, "Explored " + Lang.biomeName(player, biomeId), scoreValue);
        broadcastScore(player, team, "探索" + biomeName,
                "Explored " + Lang.biomeName(player, biomeId), scoreValue, team.totalScore);

        checkWinCondition(player, state, team, saveState);
    }

    // ==================== 胜利判定 ====================

    private static void checkWinCondition(ServerPlayerEntity player, PlayerState state,
                                          StructureRaceState.TeamData team, StructureRaceState saveState) {
        if ("timer".equals(cachedWinCondition)) return;
        if (team == null) return;
        if (team.totalScore >= cachedWinScore) {
            // 反超并获胜：获胜队伍此前不是第一名（反超追踪激活时判定）
            Integer prevRank = previousRankings.get(team.teamId);
            boolean comebackWin = prevRank != null && prevRank > 1;
            if (comebackWin) {
                // 特别显示「反超并获胜」（胜利提示样式，全服可见）
                broadcastTitleLang(player.server,
                        "§e§l" + teamZhName(team.teamId) + " 反超并获胜！",
                        "§e§l" + teamEnName(team.teamId) + " comeback WIN!");
                sendTitleToTeamMembers(player.server, team.teamId,
                        "§a§l恭喜反超并获胜！", "§a§lComeback victory!");
            }
            saveState.matchActive = false;
            saveState.markDirty();
            cachedMatchActive = false;
            dumpScoreLog(player.server);
            List<String> winners = new ArrayList<>();
            winners.add(team.teamId);
            startEndResult(player.server, winners, false);
            LOGGER.info("[StructureRace] 队伍 {} 获胜！{} 分（反超获胜={}）",
                    team.teamId, team.totalScore, comebackWin);
        }
    }

    // ==================== 队伍辅助 ====================

    private static StructureRaceState.TeamData getPlayerTeam(StructureRaceState state, UUID uuid) {
        String teamId = playerTeamMap.get(uuid);
        if (teamId == null) return null;
        StructureRaceState.TeamData team = state.getTeam(teamId);
        if (team == null) {
            playerTeamMap.remove(uuid);
            return null;
        }
        return team;
    }

    private static void addPlayerToScoreboardTeam(ServerPlayerEntity player, StructureRaceState.TeamData team) {
        try {
            Scoreboard scoreboard = player.getScoreboard();
            Team sbTeam = getOrCreateScoreboardTeam(scoreboard, team);
            scoreboard.addPlayerToTeam(player.getEntityName(), sbTeam);
        } catch (Exception e) {
            LOGGER.warn("[StructureRace] 同步队伍颜色失败: {}", e.getMessage());
        }
    }

    private static void removePlayerFromScoreboardTeam(ServerPlayerEntity player, StructureRaceState.TeamData team) {
        try {
            Scoreboard scoreboard = player.getScoreboard();
            Team sbTeam = getOrCreateScoreboardTeam(scoreboard, team);
            scoreboard.removePlayerFromTeam(player.getEntityName(), sbTeam);
        } catch (Exception e) {
            LOGGER.warn("[StructureRace] 移除队伍颜色失败: {}", e.getMessage());
        }
    }

    private static Team getOrCreateScoreboardTeam(Scoreboard scoreboard, StructureRaceState.TeamData team) {
        String sbName = "race_" + team.teamId;
        if (sbName.length() > 16) sbName = sbName.substring(0, 16);
        Team sbTeam = scoreboard.getTeam(sbName);
        if (sbTeam == null) {
            sbTeam = scoreboard.addTeam(sbName);
        }
        sbTeam.setColor(StructureRaceConfig.TEAM_FORMATTING.getOrDefault(team.teamId,
                TEAM_COLORS[team.colorIndex % TEAM_COLORS.length]));
        return sbTeam;
    }

    /** 刷新单支队伍的计分板条目：有成员显示「队名 + 总分」，无成员不显示 */
    private static void refreshTeamScoreboard(MinecraftServer server, StructureRaceState.TeamData team) {
        Scoreboard scoreboard = server.getScoreboard();
        ScoreboardObjective objective = getOrCreateObjective(scoreboard);
        // key 由队伍 ID 纯函数生成，不依赖内存 Map，重启后也能正确清除空队残留
        String key = teamScoreboardKey(team);
        if (team.members.isEmpty()) {
            scoreboard.resetPlayerScore(key, objective);
            return;
        }
        scoreboard.getPlayerScore(key, objective).setScore(team.totalScore);
    }

    /** 刷新所有队伍的计分板（入场/加减分/开始重置时调用） */
    private static void refreshAllTeamScoreboards(MinecraftServer server) {
        Scoreboard scoreboard = server.getScoreboard();
        ScoreboardObjective objective = getOrCreateObjective(scoreboard);
        // Tab 玩家列表（slot 0）不显示分数：旧存档可能残留，每次刷新时强制清除
        scoreboard.setObjectiveSlot(0, null);
        StructureRaceState state = StructureRaceState.get(server.getOverworld());
        for (StructureRaceState.TeamData team : state.getAllTeams().values()) {
            refreshTeamScoreboard(server, team);
        }
    }

    /** 计分板条目名：队伍颜色 + 中文队名（如「§c红队」） */
    private static String teamScoreboardKey(StructureRaceState.TeamData team) {
        Formatting color = StructureRaceConfig.TEAM_FORMATTING.get(team.teamId);
        String zh = StructureRaceConfig.TEAM_NAMES_ZH.getOrDefault(team.teamId, team.teamId);
        return (color != null ? color.toString() : "") + zh;
    }

    // ==================== 比赛控制（供 /race 命令调用） ====================

    /** 开始新一局比赛；若比赛已在进行中则拒绝（返回 false），避免重复启动重置玩家状态。
     * 进入 5 秒开赛倒计时（title 逐秒提示），倒计时结束后才真正传送/清背包/开赛。 */
    public static boolean startMatch(MinecraftServer server) {
        ServerWorld overworld = server.getOverworld();
        StructureRaceState state = StructureRaceState.get(overworld);
        if (state.matchActive) {
            LOGGER.info("[StructureRace] 比赛已在进行中，忽略重复 start。");
            return false;
        }
        state.resetAllPlayers();
        state.matchActive = true;
        state.matchStartTick = overworld.getTime(); // 先记录倒计时起点；正式开赛时由 executeMatchStart 重新记录
        for (StructureRaceState.TeamData t : state.getAllTeams().values()) {
            t.totalScore = 0;
            t.discoveredStructures.clear();
            t.discoveredBiomes.clear();
            t.lastRecallTime = 0;
            t.hasEnteredNether = false;
            t.hasEnteredEnd = false;
        }
        state.globallyDiscoveredStructures.clear();
        state.markDirty();

        PLAYER_STATES.clear();
        SCORE_LOG.clear(); // 新一局，清空上局得分记录
        lastAnnouncedSeconds = -1;
        pendingEndTicks = -1;
        pendingEndServer = null;
        fireworkWavesLeft = 0;
        fireworkWaveTicks = 0;
        previousRankings.clear();
        leadTrackingActive = false;
        cachedMatchActive = true;
        cachedWinCondition = state.winCondition;
        cachedWinScore = state.winScore;

        // 进入 5 秒开赛倒计时（倒计时结束才执行 executeMatchStart）
        preStartCountdownTicks = StructureRaceConfig.START_COUNTDOWN_TICKS;
        lastCountdownSecond = -1;

        // 为所有在线玩家重建竞速状态（队伍颜色/计分板）；倒计时阶段观众暂不传送
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            onPlayerSpawn(player, false);
        }

        broadcastLang(server,
                "§e🏁 比赛即将开始！§r5 秒后传送至主世界！",
                "§e🏁 Match starting soon!§r Teleporting to overworld in 5 seconds!");
        LOGGER.info("[StructureRace] 新一局比赛进入开赛倒计时，模式: {}", state.winCondition);
        return true;
    }

    /** 倒计时结束后的正式开赛：时间重置白天、传送主世界出生点、清背包、切模式、移除饱和、初始化指引计时 */
    private static void executeMatchStart(MinecraftServer server) {
        StructureRaceState state = StructureRaceState.get(server.getOverworld());
        ServerWorld overworld = server.getOverworld();
        BlockPos spawnPos = overworld.getSpawnPos();
        // 预生成主世界出生点所在区块，避免跨维度传送后客户端看不到任何地形/实体
        overworld.getChunk(spawnPos.getX() >> 4, spawnPos.getZ() >> 4,
                net.minecraft.world.chunk.ChunkStatus.FULL, true);
        // 时间重置为白天（time set 0）
        overworld.setTimeOfDay(0);
        long now = overworld.getTime();
        // 真正开赛才记录起始时刻（倒计时不计入限时）
        state.matchStartTick = now;
        state.markDirty();

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            try {
                player.getInventory().clear(); // 比赛开始清空背包，防止携带大厅物品/作弊物品
                player.teleport(overworld, spawnPos.getX() + 0.5, spawnPos.getY(),
                        spawnPos.getZ() + 0.5, player.getYaw(), player.getPitch());
                // 重生点改为主世界（比赛期间死亡不回到大厅）
                player.setSpawnPoint(World.OVERWORLD, spawnPos, 0.0f, true, false);
                if (state.getTeamByMember(player.getUuid()) != null) {
                    player.changeGameMode(GameMode.SURVIVAL);
                } else {
                    player.changeGameMode(GameMode.SPECTATOR);
                }
                player.removeStatusEffect(StatusEffects.SATURATION); // 开赛移除大厅饱和
                // 个人迷路指引：开局归零（无开局冷却），3 分钟无发现即提示
                PlayerState ps = PLAYER_STATES.get(player.getUuid());
                if (ps != null) {
                    ps.pendingLobby = false;
                    ps.joinTicks = 0;
                    ps.lastFindTime = now;
                }
                StructureRaceState.PlayerPersistentData pd = state.getPlayerData(player.getUuid());
                pd.lastFindTime = now;
            } catch (Exception e) {
                LOGGER.warn("[StructureRace] 开赛处理玩家 {} 失败: {}", player.getEntityName(), e.getMessage());
            }
        }
        state.markDirty();

        // 为所有在线玩家重建竞速状态（模拟 JOIN 初始化）
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            onPlayerSpawn(player, false);
        }

        // 未加入任何队伍的玩家设为旁观者模式（观众）
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (state.getTeamByMember(player.getUuid()) == null) {
                player.changeGameMode(GameMode.SPECTATOR);
                LOGGER.info("[StructureRace] 玩家 {} 未入队（观众），已设为旁观者模式。", player.getEntityName());
            }
        }

        String zhMode = "timer".equals(state.winCondition)
                ? "限时 " + (state.matchDurationTicks / 20 / 60) + " 分钟！时间结束时积分最高者获胜！"
                : "率先达到 " + state.winScore + " 分者获胜！";
        String enMode = "timer".equals(state.winCondition)
                ? "Timer: " + (state.matchDurationTicks / 20 / 60) + " min! Highest score at the end wins!"
                : "First to reach " + state.winScore + " points wins!";
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            p.sendMessage(Text.literal(Lang.get(p,
                    StructureRaceConfig.BROADCAST_PREFIX + "§e🏁 比赛开始！§r" + zhMode,
                    StructureRaceConfig.BROADCAST_PREFIX + "§e🏁 Match started!§r " + enMode)), false);
            sendTitle(p, Lang.get(p, "§a§l比赛开始！", "§a§lMatch Started!"),
                    Lang.get(p, "§r" + zhMode, "§r" + enMode), 10, 60, 10);
        }
        LOGGER.info("[StructureRace] 新一局比赛正式开始，模式: {}", state.winCondition);
    }

    public static void stopMatch(MinecraftServer server) {
        StructureRaceState state = StructureRaceState.get(server.getOverworld());
        state.matchActive = false;
        state.markDirty();
        cachedMatchActive = false;
        preStartCountdownTicks = -1;
        lastCountdownSecond = -1;
        broadcastLang(server, "§c比赛已停止，暂停计分。§r", "§cMatch stopped, scoring paused.§r");
    }

    public static void resumeMatch(MinecraftServer server) {
        StructureRaceState state = StructureRaceState.get(server.getOverworld());
        state.matchActive = true;
        state.markDirty();
        cachedMatchActive = true;
        broadcastLang(server, "§a比赛已恢复，继续计分。§r", "§aMatch resumed, scoring continues.§r");
        LOGGER.info("[StructureRace] 比赛恢复，模式: {}", state.winCondition);
    }

    public static void resetMatch(MinecraftServer server) {
        StructureRaceState state = StructureRaceState.get(server.getOverworld());
        state.resetAllPlayers();
        for (StructureRaceState.TeamData t : state.getAllTeams().values()) {
            t.totalScore = 0;
            t.discoveredStructures.clear();
            t.discoveredBiomes.clear();
            t.lastRecallTime = 0;
            t.hasEnteredNether = false;
            t.hasEnteredEnd = false;
        }
        state.globallyDiscoveredStructures.clear();
        state.matchActive = false;
        state.markDirty();

        PLAYER_STATES.clear();
        // 若比赛未经正常结算就被 reset，先输出本局得分记录
        dumpScoreLog(server);
        SCORE_LOG.clear(); // 兜底清空
        pendingEndTicks = -1;
        pendingEndServer = null;
        preStartCountdownTicks = -1;
        lastCountdownSecond = -1;
        fireworkWavesLeft = 0;
        fireworkWaveTicks = 0;
        cachedMatchActive = false;
        // 队伍分数已归零，刷新队伍计分板（无成员队伍不显示）
        refreshAllTeamScoreboards(server);
        // 重置后回到准备阶段：全体玩家回大厅
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            teleportToLobby(p);
        }
        broadcastLang(server, "§c比赛已重置（未开始）。§r", "§cMatch reset (not started).§r");
    }

    public static void setWinCondition(MinecraftServer server, String mode) {
        StructureRaceState state = StructureRaceState.get(server.getOverworld());
        String normalized = mode.toLowerCase();
        if ("timer".equals(normalized) || "score".equals(normalized)) {
            state.winCondition = normalized;
            state.markDirty();
            cachedWinCondition = normalized;
            LOGGER.info("[StructureRace] 获胜模式已切换为: {}", normalized);
        }
    }

    public static void setWinScore(MinecraftServer server, int score) {
        StructureRaceState state = StructureRaceState.get(server.getOverworld());
        state.winScore = Math.max(1, score);
        state.markDirty();
        cachedWinScore = state.winScore;
    }

    public static void setMatchDuration(MinecraftServer server, int seconds) {
        StructureRaceState state = StructureRaceState.get(server.getOverworld());
        state.matchDurationTicks = Math.max(1, seconds) * 20L;
        state.markDirty();
        LOGGER.info("[StructureRace] 限时制时长已修改为 {} 秒", seconds);
    }

    // ==================== 队伍管理（供 /race team 命令调用） ====================

    public static boolean createTeam(MinecraftServer server, String teamId) {
        StructureRaceState state = StructureRaceState.get(server.getOverworld());
        if (state.getTeam(teamId) != null) return false;
        state.createTeam(teamId);
        LOGGER.info("[StructureRace] 创建队伍: {}", teamId);
        return true;
    }

    public static boolean disbandTeam(MinecraftServer server, String teamId) {
        StructureRaceState state = StructureRaceState.get(server.getOverworld());
        StructureRaceState.TeamData team = state.getTeam(teamId);
        if (team == null) return false;

        for (UUID uuid : team.members) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
            if (p != null) {
                removePlayerFromScoreboardTeam(p, team);
                // 解散队伍时若比赛进行中，成员变为观众
                if (cachedMatchActive) {
                    p.changeGameMode(GameMode.SPECTATOR);
                }
            }
            playerTeamMap.remove(uuid);
        }
        state.removeTeam(teamId);
        // 刷新队伍计分板（被解散队伍条目一并移除）
        refreshAllTeamScoreboards(server);
        LOGGER.info("[StructureRace] 解散队伍: {}", teamId);
        return true;
    }

    /** 将指定玩家移入指定队伍（自动离开原队伍）；返回 0=成功, 1=玩家不在线, 2=队伍不存在 */
    public static int addPlayerToTeam(MinecraftServer server, String playerName, String teamId) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerName);
        if (player == null) return 1;

        StructureRaceState state = StructureRaceState.get(server.getOverworld());
        StructureRaceState.TeamData newTeam = state.getTeam(teamId);
        if (newTeam == null) return 2;

        StructureRaceState.TeamData oldTeam = state.getTeamByMember(player.getUuid());
        if (oldTeam != null && oldTeam != newTeam) {
            oldTeam.members.remove(player.getUuid());
            removePlayerFromScoreboardTeam(player, oldTeam);
            // 换队：击杀进度清零（个人击杀数归属当前队伍）
            PlayerState ps = PLAYER_STATES.get(player.getUuid());
            if (ps != null) {
                ps.killCount = 0;
                state.getPlayerData(player.getUuid()).killCount = 0;
            }
        }

        newTeam.members.add(player.getUuid());
        playerTeamMap.put(player.getUuid(), newTeam.teamId);
        addPlayerToScoreboardTeam(player, newTeam);
        state.markDirty();

        // 观众（旁观者）加入队伍：传送回出生点
        if (player.isSpectator()) {
            BlockPos spawnPos = server.getOverworld().getSpawnPos();
            player.teleport(server.getOverworld(), spawnPos.getX() + 0.5, spawnPos.getY(),
                    spawnPos.getZ() + 0.5, player.getYaw(), player.getPitch());
        }
        // 准备阶段保持冒险模式（大厅不允许挖方块）；比赛进行中加入队伍才切生存
        if (cachedMatchActive) {
            player.changeGameMode(GameMode.SURVIVAL);
        } else {
            player.changeGameMode(GameMode.ADVENTURE);
        }

        // 刷新队伍计分板（加入/换队后重算各队条目）
        refreshAllTeamScoreboards(server);
        LOGGER.info("[StructureRace] 玩家 {} 加入队伍 {}", playerName, teamId);
        return 0;
    }

    /** 将指定玩家移出所在队伍；返回 0=成功, 1=玩家不在线, 2=玩家无队伍 */
    public static int removePlayerFromTeam(MinecraftServer server, String playerName) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerName);
        if (player == null) return 1;

        StructureRaceState state = StructureRaceState.get(server.getOverworld());
        StructureRaceState.TeamData team = state.getTeamByMember(player.getUuid());
        if (team == null) return 2;

        team.members.remove(player.getUuid());
        removePlayerFromScoreboardTeam(player, team);
        playerTeamMap.remove(player.getUuid());
        state.markDirty();

        // 比赛进行中：离开队伍 = 观众
        if (cachedMatchActive) {
            player.changeGameMode(GameMode.SPECTATOR);
        }
        // 刷新队伍计分板（队伍可能变空，无人队伍不显示）
        refreshAllTeamScoreboards(server);
        LOGGER.info("[StructureRace] 玩家 {} 已离开队伍 {}", playerName, team.teamId);
        return 0;
    }

    /**
     * 玩家自助加入/换队（/race join）。返回 0=成功, 1=比赛进行中禁止换队, 2=队伍不存在。
     * 仅准备阶段（waiting）可自由组队；比赛进行中由管理员强制调整。
     */
    public static int joinTeam(MinecraftServer server, ServerPlayerEntity player, String teamId) {
        if (cachedMatchActive) return 1; // 比赛进行中不能换队
        StructureRaceState state = StructureRaceState.get(server.getOverworld());
        if (state.getTeam(teamId) == null) return 2;
        return addPlayerToTeam(server, player.getEntityName(), teamId) == 0 ? 0 : 3;
    }

    /**
     * 玩家自助离开队伍（/race leave）。返回 0=成功, 1=比赛进行中禁止, 2=无队伍。
     */
    public static int leaveTeam(MinecraftServer server, ServerPlayerEntity player) {
        if (cachedMatchActive) return 1; // 比赛进行中不能离队
        return removePlayerFromTeam(server, player.getEntityName()) == 0 ? 0 : 2;
    }

    /** 机制3：队伍召回。返回 0=成功，其他值见错误码。 */
    public static int recallPlayer(MinecraftServer server, ServerPlayerEntity actor, String targetName) {
        ServerPlayerEntity target = (targetName == null || targetName.isEmpty())
                ? actor : server.getPlayerManager().getPlayer(targetName);
        if (target == null) return 1; // 目标不在线

        StructureRaceState state = StructureRaceState.get(server.getOverworld());
        StructureRaceState.TeamData actorTeam = state.getTeamByMember(actor.getUuid());
        StructureRaceState.TeamData targetTeam = state.getTeamByMember(target.getUuid());
        if (targetTeam == null) return 2; // 被召回者无队伍

        // 非同队召回需 OP 权限
        if (actor != target && (actorTeam == null || !actorTeam.teamId.equals(targetTeam.teamId))
                && !actor.hasPermissionLevel(2)) {
            return 3;
        }

        long now = server.getOverworld().getTime();
        if (now - targetTeam.lastRecallTime < RECALL_COOLDOWN_TICKS) return 4; // 冷却中
        if (targetTeam.totalScore < RECALL_COST) return 5; // 积分不足

        // 寻找最近的存活队友（排除被召回者自己、旁观者、虚空）
        ServerPlayerEntity nearest = null;
        double bestDist = Double.MAX_VALUE;
        for (UUID uuid : targetTeam.members) {
            if (uuid.equals(target.getUuid())) continue;
            ServerPlayerEntity mate = server.getPlayerManager().getPlayer(uuid);
            if (mate == null || mate.isSpectator() || !mate.isAlive()) continue;
            if (mate.getY() < -64) continue;
            double d = mate.squaredDistanceTo(target);
            if (d < bestDist) {
                bestDist = d;
                nearest = mate;
            }
        }
        if (nearest == null) return 6; // 无其他存活队友（含单人队伍）

        // 执行召回
        targetTeam.totalScore -= RECALL_COST;
        targetTeam.lastRecallTime = now;
        state.markDirty();

        target.teleport(nearest.getServerWorld(), nearest.getX(), nearest.getY(), nearest.getZ(),
                nearest.getYaw(), nearest.getPitch());
        target.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 100, 2, false, true, true));
        target.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 200, 1, false, true, true));

        refreshTeamScoreboard(server, targetTeam);
        broadcastLang(server,
                "§c[" + teamZhName(targetTeam.teamId) + "]§r §a" + target.getEntityName()
                        + " §r被召回至队友身边（队伍 -§6" + RECALL_COST + "§r 分）",
                "§c[" + StructureRaceConfig.TEAM_NAMES_EN.getOrDefault(targetTeam.teamId, targetTeam.teamId)
                        + "]§r §a" + target.getEntityName()
                        + " §rwas recalled to their teammate (team -§6" + RECALL_COST + "§r pts)");
        LOGGER.info("[StructureRace] 玩家 {} 被召回（队伍 {}）", target.getEntityName(), targetTeam.teamId);
        return 0;
    }

    // ==================== 队伍信息查询 ====================

    public static List<String> listTeams(MinecraftServer server, String lang) {
        StructureRaceState state = StructureRaceState.get(server.getOverworld());
        List<String> lines = new ArrayList<>();
        if (state.getAllTeams().isEmpty()) {
            lines.add(Lang.get(lang, "当前没有队伍。", "There are no teams."));
            return lines;
        }
        for (StructureRaceState.TeamData team : state.getAllTeams().values()) {
            StringBuilder members = new StringBuilder();
            for (UUID uuid : team.members) {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
                if (members.length() > 0) members.append(", ");
                members.append(p != null ? p.getEntityName() : uuid.toString().substring(0, 8));
            }
            String name = Lang.get(lang,
                    StructureRaceConfig.TEAM_NAMES_ZH.getOrDefault(team.teamId, team.teamId),
                    StructureRaceConfig.TEAM_NAMES_EN.getOrDefault(team.teamId, team.teamId));
            Formatting color = StructureRaceConfig.TEAM_FORMATTING.get(team.teamId);
            lines.add((color != null ? color.toString() : "") + name + "§r (§e" + team.totalScore
                    + "§r " + Lang.get(lang, "分", "pts") + "): "
                    + (members.length() == 0 ? "§7" + Lang.get(lang, "无成员", "no members") : members));
        }
        return lines;
    }

    public static List<String> getTeamInfo(MinecraftServer server, String teamId, String lang) {
        StructureRaceState state = StructureRaceState.get(server.getOverworld());
        StructureRaceState.TeamData team = state.getTeam(teamId);
        if (team == null) return null;
        List<String> lines = new ArrayList<>();
        lines.add("§6" + Lang.get(lang, "队伍", "Team") + " " + team.teamId + "§r  "
                + Lang.get(lang, "总分", "Score") + ": §e" + team.totalScore + "§r "
                + Lang.get(lang, "分  ", "pts  ") + Lang.get(lang, "成员", "Members") + ": "
                + team.members.size() + Lang.get(lang, "人", " ppl"));

        StringBuilder structLine = new StringBuilder("§a" + Lang.get(lang, "已发现结构", "Structures found") + ": §r");
        if (team.discoveredStructures.isEmpty()) {
            structLine.append(Lang.get(lang, "无", "none"));
        } else {
            List<String> names = new ArrayList<>();
            for (String uniqueId : team.discoveredStructures) {
                String regName = extractRegistryName(uniqueId);
                names.add(Lang.get(lang,
                        StructureRaceConfig.STRUCTURE_NAMES.getOrDefault(regName, regName),
                        StructureRaceConfig.STRUCTURE_NAMES_EN.getOrDefault(regName, regName)));
            }
            structLine.append(String.join(Lang.get(lang, "、", ", "), names));
        }
        lines.add(structLine.toString());

        StringBuilder biomeLine = new StringBuilder("§a" + Lang.get(lang, "已发现群系", "Biomes found") + ": §r");
        if (team.discoveredBiomes.isEmpty()) {
            biomeLine.append(Lang.get(lang, "无", "none"));
        } else {
            List<String> names = new ArrayList<>();
            for (String biomeId : team.discoveredBiomes) {
                names.add(Lang.get(lang,
                        StructureRaceConfig.BIOME_NAMES.getOrDefault(biomeId, biomeId),
                        StructureRaceConfig.BIOME_NAMES_EN.getOrDefault(biomeId, biomeId)));
            }
            biomeLine.append(String.join(Lang.get(lang, "、", ", "), names));
        }
        lines.add(biomeLine.toString());
        return lines;
    }

    public static boolean hasTeamDiscoveredStructure(MinecraftServer server, String teamId, String structName) {
        StructureRaceState state = StructureRaceState.get(server.getOverworld());
        StructureRaceState.TeamData team = state.getTeam(teamId);
        if (team == null) return false;
        String regName = resolveStructureName(structName);
        for (String uniqueId : team.discoveredStructures) {
            if (extractRegistryName(uniqueId).equals(regName)) return true;
        }
        return false;
    }

    private static String extractRegistryName(String uniqueId) {
        String[] parts = uniqueId.split(":");
        return parts.length >= 2 ? parts[1] : uniqueId;
    }

    private static String resolveStructureName(String input) {
        if (StructureRaceConfig.STRUCTURE_NAMES.containsKey(input)) return input;
        for (Map.Entry<String, String> e : StructureRaceConfig.STRUCTURE_NAMES.entrySet()) {
            if (e.getValue().equals(input)) return e.getKey();
        }
        return input;
    }

    // ==================== 状态查询 ====================

    public static String getStatus(MinecraftServer server, String lang) {
        StructureRaceState state = StructureRaceState.get(server.getOverworld());
        String mode = "timer".equals(state.winCondition)
                ? Lang.get(lang, "限时制", "Timer mode") : Lang.get(lang, "积分制", "Score mode");
        String active = state.matchActive ? Lang.get(lang, "进行中", "Active") : Lang.get(lang, "已停止", "Stopped");
        String timePart = "";
        if ("timer".equals(state.winCondition) && state.matchActive) {
            long remaining = state.matchDurationTicks - (server.getOverworld().getTime() - state.matchStartTick);
            timePart = Lang.get(lang, "，剩余 ", ", remaining ") + (Lang.isEnLang(lang)
                    ? formatSecondsEn(Math.max(0, remaining / 20)) : formatSeconds(Math.max(0, remaining / 20)));
        } else if ("timer".equals(state.winCondition)) {
            timePart = Lang.get(lang, "，时长 ", ", duration ")
                    + (state.matchDurationTicks / 20 / 60) + (Lang.isEnLang(lang) ? " min" : " 分钟");
        }
        return Lang.get(lang, "§6[竞速] §r当前模式: ", "§6[Race] §rMode: ") + mode
                + Lang.get(lang, "，状态: ", ", status: ") + active + timePart
                + (("score".equals(state.winCondition)) ? Lang.get(lang, "，获胜分数: ", ", win score: ") + state.winScore : "")
                + Lang.get(lang, "，队伍数: ", ", teams: ") + state.getAllTeams().size();
    }

    public static long getRemainingSeconds(MinecraftServer server) {
        ServerWorld overworld = server.getOverworld();
        StructureRaceState state = StructureRaceState.get(overworld);
        if (!"timer".equals(state.winCondition) || !state.matchActive) return -1;
        long remaining = state.matchDurationTicks - (overworld.getTime() - state.matchStartTick);
        return Math.max(0, remaining / 20);
    }

    public static List<String> getLeaderboard(MinecraftServer server, String lang) {
        StructureRaceState state = StructureRaceState.get(server.getOverworld());
        List<StructureRaceState.TeamData> teams = new ArrayList<>(state.getAllTeams().values());
        teams.sort(Comparator.comparingInt((StructureRaceState.TeamData t) -> t.totalScore).reversed());
        List<String> lines = new ArrayList<>();
        int rank = 1;
        for (StructureRaceState.TeamData t : teams) {
            if (t.members.isEmpty()) continue; // 空队不参与排名
            String name = Lang.get(lang,
                    StructureRaceConfig.TEAM_NAMES_ZH.getOrDefault(t.teamId, t.teamId),
                    StructureRaceConfig.TEAM_NAMES_EN.getOrDefault(t.teamId, t.teamId));
            Formatting color = StructureRaceConfig.TEAM_FORMATTING.get(t.teamId);
            lines.add("§e" + rank + ". §r" + (color != null ? color : "") + name
                    + "§r: §6" + t.totalScore + "§r " + Lang.get(lang, "分（", "pts (")
                    + t.members.size() + Lang.get(lang, "人）", " ppl)"));
            rank++;
        }
        return lines;
    }

    // ==================== 计分板 ====================

    private static ScoreboardObjective getOrCreateObjective(Scoreboard scoreboard) {
        ScoreboardObjective objective = scoreboard.getNullableObjective(
                StructureRaceConfig.SCOREBOARD_OBJECTIVE_NAME);
        if (objective == null) {
            objective = scoreboard.addObjective(
                    StructureRaceConfig.SCOREBOARD_OBJECTIVE_NAME,
                    ScoreboardCriterion.DUMMY,
                    Text.literal(StructureRaceConfig.SCOREBOARD_DISPLAY_NAME),
                    ScoreboardCriterion.RenderType.INTEGER);
            // 仅侧边栏显示（Tab 玩家列表不显示分数，只显示带队伍颜色的玩家名）
            scoreboard.setObjectiveSlot(1, objective);
            LOGGER.info("[StructureRace] 已创建计分板目标: {}",
                    StructureRaceConfig.SCOREBOARD_OBJECTIVE_NAME);
        }
        return objective;
    }

    // ==================== 广播 ====================

    private static void broadcastScore(ServerPlayerEntity player, StructureRaceState.TeamData team,
                                        String reasonZh, String reasonEn, int earned, int total) {
        SCORE_LOG.add(new ScoreLogEntry(player.server.getOverworld().getTime(),
                player.getEntityName(), team.teamId, reasonZh, earned, total));
        StructureRaceState state = StructureRaceState.get(player.server.getOverworld());
        Text message = Text.literal(
                StructureRaceConfig.BROADCAST_PREFIX
                        + teamColorCode(team.teamId) + "[" + Lang.teamName(player, team.teamId) + "]§r "
                        + playerDisplayName(player, state)
                        + Lang.get(player,
                                " §r获得 §6+" + earned + "§r 分（" + reasonZh + "），队伍累计 §6" + total + "§r 分",
                                " §rearned §6+" + earned + "§r pts (" + reasonEn + "), team total §6" + total + "§r pts"));
        player.server.getPlayerManager().broadcast(message, false);
    }

    /** 输出本局得分明细到服务器日志，并写入文件（便于赛后分析），随后清空记录 */
    private static void dumpScoreLog(MinecraftServer server) {
        if (SCORE_LOG.isEmpty()) return;
        long startTick = StructureRaceState.get(server.getOverworld()).matchStartTick;
        StringBuilder sb = new StringBuilder();
        sb.append("========== 本局得分明细（共 ").append(SCORE_LOG.size()).append(" 条）==========\n");
        for (ScoreLogEntry e : SCORE_LOG) {
            long elapsed = Math.max(0, e.gameTime - startTick);
            String line = String.format("[%s] %s [%s] +%d 分（%s），队伍累计 %d 分",
                    formatTickTime(elapsed), e.playerName, e.teamId, e.points, e.reason, e.teamTotal);
            LOGGER.info(line);
            sb.append(line).append('\n');
        }
        LOGGER.info("========== 本局得分明细结束 ==========");
        sb.append("========== 本局得分明细结束 ==========\n");
        writeScoreLogFile(sb.toString());
        SCORE_LOG.clear();
    }

    /** 将得分记录写入 structure_race_logs/scorelog_<时间戳>.txt */
    private static void writeScoreLogFile(String content) {
        try {
            String stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(LocalDateTime.now());
            java.nio.file.Path dir = java.nio.file.Paths.get("structure_race_logs");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.write(dir.resolve("scorelog_" + stamp + ".txt"),
                    content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            LOGGER.info("[StructureRace] 本局得分日志已写入 structure_race_logs/scorelog_{}.txt", stamp);
        } catch (IOException e) {
            LOGGER.warn("[StructureRace] 写入得分日志失败: {}", e.getMessage());
        }
    }

    private static String formatTickTime(long ticks) {
        long totalSeconds = ticks / 20;
        long m = totalSeconds / 60;
        long s = totalSeconds % 60;
        return String.format("%02d:%02d", m, s);
    }

    // ==================== 内部状态类 ====================

    private static final class ScoreLogEntry {
        final long gameTime;
        final String playerName;
        final String teamId;
        final String reason;
        final int points;
        final int teamTotal;

        ScoreLogEntry(long gameTime, String playerName, String teamId, String reason, int points, int teamTotal) {
            this.gameTime = gameTime;
            this.playerName = playerName;
            this.teamId = teamId;
            this.reason = reason;
            this.points = points;
            this.teamTotal = teamTotal;
        }
    }

    private static final class PlayerState {
        private String playerName;
        private final Set<String> discoveredStructures = new HashSet<>();
        private final Set<String> discoveredBiomes = new HashSet<>();
        private int totalScore;
        private long lastScoreGameTime;
        private long lastBiomeCheckTime;
        private long lastFindTime; // 机制6：上次发现时间；提示后为「now + 7分钟」的未来值（实现个人冷却）
        private long lastHintTime; // 机制6：上次迷路指引查询时间（用于过近时 1 分钟重试）
        private boolean won;
        private BlockPos lastDistancePos; // 机制1
        private double distanceAccumulator; // 机制1
        private int killCount; // 机制2
        private String lastDimension; // 机制4：上次所在维度
        private boolean pendingLobby; // 进服等待加载中（在主世界地底等待点）
        private int joinTicks; // 进服等待累计 tick
        private long lastCompHintTime; // 机制5：上次落后补偿提示时间（1 分钟节流）

        // ===== 赛后荣誉统计 =====
        private double totalDistance; // 累计跑图距离（格）
        private int structuresFound; // 发现结构数量
        private int structurePoints; // 结构积分累计
        private int personalScore; // 玩家个人获得的总分（荣誉/团队贡献用）
        private String bestEventNameZh; // 单次最高得分事件（中文）
        private String bestEventNameEn; // 单次最高得分事件（英文）
        private int bestEventPoints; // 单次最高得分
    }
}
