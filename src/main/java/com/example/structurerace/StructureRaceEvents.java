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

    /** 机制6：连续 5 分钟无任何发现触发指引 */
    private static final long FIND_TIMEOUT_TICKS = 300L * 20L;
    /** 机制6：指引全局冷却（10 分钟） */
    private static final long GUIDE_GLOBAL_COOLDOWN_TICKS = 600L * 20L;

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

    /** 比赛结算延迟（tick）：宣布结果后 10 秒传送回大厅并放烟花 */
    private static final int END_RESULT_DELAY_TICKS = 200;
    private static int pendingEndTicks = -1;
    private static MinecraftServer pendingEndServer;

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
    private static long lastGlobalGuideTime = -GUIDE_GLOBAL_COOLDOWN_TICKS;
    private static int tickCounter;

    private StructureRaceEvents() {}

    // ==================== 事件注册 ====================

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(StructureRaceEvents::onServerTick);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                onPlayerSpawn(handler.getPlayer()));

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                onPlayerDisconnect(handler.getPlayer()));

        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) ->
                onPlayerSpawn(newPlayer));

        // 聊天消息：比赛进行中普通消息仅队友可见，`!` 前缀为全局消息
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(StructureRaceEvents::handleChatMessage);

        // 队伍选择器：右键指南针（带标记）打开选队 GUI
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClient) return TypedActionResult.pass(player.getStackInHand(hand));
            if (!(player instanceof ServerPlayerEntity sp)) return TypedActionResult.pass(player.getStackInHand(hand));
            ItemStack stack = player.getStackInHand(hand);
            if (stack.isOf(Items.COMPASS) && stack.hasNbt()
                    && stack.getNbt().getBoolean(StructureRaceConfig.TEAM_SELECTOR_TAG)) {
                TeamSelectorScreenHandler.open(sp);
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
            lastGlobalGuideTime = -GUIDE_GLOBAL_COOLDOWN_TICKS;
            tickCounter = 0;
            lobbyInitialized = false;
            pendingEndTicks = -1;
            pendingEndServer = null;
            LOGGER.info("[StructureRace] 新世界服务器启动，内存竞速状态已重置。");
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            ensureLobbyPlatform(server);
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
            // 准备/结束阶段：玩家必须待在大厅（冒险模式 + 选队装备）；擅自离开大厅的强制送回（OP 豁免）
            if (!cachedMatchActive) {
                // 结算延迟期间（宣布结果到回大厅的 10 秒）不拉回，让玩家在主世界看完 title
                if (pendingEndTicks > 0) continue;
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
            server.getPlayerManager().broadcast(Text.literal(
                    StructureRaceConfig.BROADCAST_PREFIX
                            + "§e⏰ §r距比赛结束还有 §6" + formatSeconds(remainingSeconds) + "§r！"), false);
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
            server.getPlayerManager().broadcast(Text.literal(
                    StructureRaceConfig.BROADCAST_PREFIX + "§e⏰ §r比赛结束，无人得分！"), false);
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

    // ==================== 玩家加入/离开 ====================

    private static void onPlayerSpawn(ServerPlayerEntity player) {
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
        // 仅首次加入（无持久化记录）时初始化指引计时，避免重进重置
        if (state.lastFindTime == 0) {
            state.lastFindTime = overworld.getTime();
        }

        // 准备/结束阶段：玩家出生在大厅玻璃平台，冒险模式，持有选队指南针与规则书
        if (!cachedMatchActive) {
            player.changeGameMode(GameMode.ADVENTURE);
            ensureLobbyGear(player);
            // 直接传送到大厅（无论是否 OP，单机玩家默认 OP 也须进大厅）；异常由 onServerTick 兜底拉回
            try {
                teleportToLobbyIfNotThere(player);
            } catch (Exception e) {
                LOGGER.warn("[StructureRace] 传送玩家 {} 到大厅失败: {}", player.getEntityName(), e.getMessage());
            }
        }

        // 比赛进行中：无队伍玩家 = 主世界旁观者（观众）
        StructureRaceState.TeamData team = saveState.getTeamByMember(player.getUuid());
        if (cachedMatchActive && team == null) {
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

    // ==================== 大厅装备（选队指南针 + 规则书） ====================

    /** 准备阶段：确保玩家持有选队指南针与规则书，且为冒险模式（防丢：每 2 秒补发一次） */
    private static void ensureLobbyGear(ServerPlayerEntity player) {
        if (player.getServerWorld().getRegistryKey() != LOBBY_KEY) return;
        PlayerInventory inv = player.getInventory();
        boolean hasCompass = false;
        boolean hasBook = false;
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
        }
        if (!hasCompass) inv.offerOrDrop(createTeamSelector());
        if (!hasBook) inv.offerOrDrop(createRuleBook());
        if (player.interactionManager.getGameMode() != GameMode.ADVENTURE) {
            player.changeGameMode(GameMode.ADVENTURE);
        }
    }

    /** 创建队伍选择器（带 NBT 标记的指南针） */
    public static ItemStack createTeamSelector() {
        ItemStack stack = new ItemStack(Items.COMPASS);
        NbtCompound nbt = new NbtCompound();
        nbt.putBoolean(StructureRaceConfig.TEAM_SELECTOR_TAG, true);
        nbt.putString("structure_race:item", "team_selector");
        stack.setNbt(nbt);
        stack.setCustomName(Text.literal("§b队伍选择器 §7(右键打开)"));
        return stack;
    }

    /** 创建玩法规则书（写好的书，含玩法/规则/指令/积分关系） */
    private static ItemStack createRuleBook() {
        ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
        NbtCompound nbt = new NbtCompound();
        nbt.putString("title", "结构竞速玩法指南");
        nbt.putString("author", "structure_race");
        nbt.putInt("generation", 0);
        nbt.putBoolean("resolved", true);
        NbtList pages = new NbtList();
        for (String page : StructureRaceConfig.getRuleBookPages()) {
            pages.add(NbtString.of(Text.Serializer.toJson(Text.literal(page))));
        }
        nbt.put("pages", pages);
        stack.setNbt(nbt);
        stack.setCustomName(Text.literal("§6结构竞速·玩法指南"));
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
        openBookScreen(player, createRuleBook());
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
        }
    }

    /** 打开「积分映射书」：结构分值 + 群系分值 + 其他积分规则 */
    public static void openPointBook(ServerPlayerEntity player) {
        List<String> pages = new ArrayList<>();
        // 结构分值
        StringBuilder sb = new StringBuilder("§l§n结构积分映射§r\n");
        int count = 0;
        for (RegistryKey<Structure> key : StructureRaceConfig.STRUCTURE_SCORES.keySet()) {
            int score = StructureRaceConfig.STRUCTURE_SCORES.get(key);
            String name = StructureRaceConfig.STRUCTURE_NAMES.getOrDefault(
                    key.getValue().getPath(), key.getValue().getPath());
            if (count > 0 && count % 12 == 0) {
                pages.add(sb.toString());
                sb = new StringBuilder("§l§n结构积分映射(续)§r\n");
            }
            sb.append(name).append(' ').append(score).append('\n');
            count++;
        }
        pages.add(sb.toString());
        // 群系分值
        sb = new StringBuilder("§l§n群系积分映射§r\n");
        count = 0;
        for (RegistryKey<Biome> key : StructureRaceConfig.BIOME_SCORES.keySet()) {
            int score = StructureRaceConfig.BIOME_SCORES.get(key);
            String name = StructureRaceConfig.BIOME_NAMES.getOrDefault(
                    key.getValue().toString(), key.getValue().getPath());
            if (count > 0 && count % 12 == 0) {
                pages.add(sb.toString());
                sb = new StringBuilder("§l§n群系积分映射(续)§r\n");
            }
            sb.append(name).append(' ').append(score).append('\n');
            count++;
        }
        pages.add(sb.toString());
        // 其他积分规则
        sb = new StringBuilder("§l§n其他积分规则§r\n");
        sb.append("里程：每500格+1分\n坐船行驶不计\n\n");
        sb.append("击杀：每10只敌对怪物\n+1分（含远程击杀）\n\n");
        sb.append("维度：首次进入下界+10\n首次进入末地+20\n（每队各一次）\n\n");
        sb.append("府邸：有探险家地图+50\n无地图+30");
        pages.add(sb.toString());
        openInfoBook(player, "积分映射", pages);
    }

    /** 打开「进度书」：总分、已找到结构及数量、已探索/未找到的可加分群系 */
    public static void openProgressBook(ServerPlayerEntity player) {
        StructureRaceState state = StructureRaceState.get(player.getServer().getOverworld());
        StructureRaceState.TeamData team = state.getTeamByMember(player.getUuid());
        List<String> pages = new ArrayList<>();
        if (team == null) {
            pages.add("§l§n竞速进度§r\n\n你尚未加入任何队伍。\n加入队伍后才能查看\n本队的探索进度。\n\n可用 §1/race join <颜色>§r\n或右键指南针组队。");
            openInfoBook(player, "竞速进度", pages);
            return;
        }
        String teamZh = StructureRaceConfig.TEAM_NAMES_ZH.getOrDefault(team.teamId, team.teamId);
        // 已找到结构及数量
        Map<String, Integer> structCount = new HashMap<>();
        for (String uniqueId : team.discoveredStructures) {
            structCount.merge(extractRegistryName(uniqueId), 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder("§l§n" + teamZh + " 探索进度§r\n\n");
        sb.append("总分：§l").append(team.totalScore).append("§r\n\n");
        sb.append("§l【已发现结构】§r\n");
        if (structCount.isEmpty()) {
            sb.append("（暂无）\n");
        } else {
            int c = 0;
            for (Map.Entry<String, Integer> e : structCount.entrySet()) {
                String name = StructureRaceConfig.STRUCTURE_NAMES.getOrDefault(e.getKey(), e.getKey());
                sb.append(name).append(" x").append(e.getValue()).append('\n');
                if (++c % 12 == 0) {
                    pages.add(sb.toString());
                    sb = new StringBuilder("§l【已发现结构(续)】§r\n");
                }
            }
        }
        sb.append("\n§l【已探索群系】§r\n");
        if (team.discoveredBiomes.isEmpty()) {
            sb.append("（暂无）\n");
        } else {
            int c = 0;
            for (String id : team.discoveredBiomes) {
                String name = StructureRaceConfig.BIOME_NAMES.getOrDefault(id, id);
                sb.append(name).append('\n');
                if (++c % 14 == 0) {
                    pages.add(sb.toString());
                    sb = new StringBuilder("§l【已探索群系(续)】§r\n");
                }
            }
        }
        pages.add(sb.toString());
        // 未找到的可加分群系
        sb = new StringBuilder("§l§n未找到的可加分群系§r\n");
        int c = 0;
        for (RegistryKey<Biome> key : StructureRaceConfig.BIOME_SCORES.keySet()) {
            String id = key.getValue().toString();
            if (team.discoveredBiomes.contains(id)) continue;
            String name = StructureRaceConfig.BIOME_NAMES.getOrDefault(id, key.getValue().getPath());
            int score = StructureRaceConfig.BIOME_SCORES.get(key);
            sb.append(name).append(' ').append(score).append('\n');
            if (++c % 14 == 0) {
                pages.add(sb.toString());
                sb = new StringBuilder("§l§n未找到群系(续)§r\n");
            }
        }
        if (c == 0) sb.append("（全部已探索！）");
        pages.add(sb.toString());
        openInfoBook(player, "竞速进度", pages);
    }



    // ==================== 结算（title + 排名 + 延迟传送 + 烟花） ====================

    /** 开始比赛结算：广播排名、按阵营显示 title，10 秒后传送回大厅并放烟花 */
    private static void startEndResult(MinecraftServer server, List<String> winnerTeamIds, boolean tie) {
        // 聊天栏：每队分数与排名
        server.getPlayerManager().broadcast(Text.literal(
                StructureRaceConfig.BROADCAST_PREFIX + "§6====== 比赛结束·排名 ======"), false);
        for (String line : getLeaderboard(server)) {
            server.getPlayerManager().broadcast(Text.literal("§7" + line), false);
        }

        StructureRaceState state = StructureRaceState.get(server.getOverworld());
        List<String> zhNames = new ArrayList<>();
        for (String id : winnerTeamIds) {
            zhNames.add(StructureRaceConfig.TEAM_NAMES_ZH.getOrDefault(id, id));
        }
        String winnerText = tie
                ? "平局！" + String.join("、", zhNames) + " 并列第一"
                : zhNames.get(0) + " 获得胜利！";

        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            StructureRaceState.TeamData pTeam = state.getTeamByMember(p.getUuid());
            boolean isWinner = pTeam != null && winnerTeamIds.contains(pTeam.teamId);
            if (tie) {
                sendTitle(p, "§6§l平局", "§r多个队伍并列第一", 10, 100, 20);
            } else if (isWinner) {
                sendTitle(p, "§a§l恭喜获得胜利！", "§r" + winnerText, 10, 100, 20);
            } else {
                sendTitle(p, "§c§l游戏结束", "§r" + winnerText, 10, 100, 20);
            }
        }

        // 10 秒后传送回大厅并放烟花
        pendingEndTicks = END_RESULT_DELAY_TICKS;
        pendingEndServer = server;
    }

    /** 结算延迟结束：全体回大厅 + 放烟花 */
    private static void executeEndResult(MinecraftServer server) {
        if (server == null) return;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            teleportToLobby(p);
        }
        spawnVictoryFireworks(server);
    }

    private static void spawnVictoryFireworks(MinecraftServer server) {
        ServerWorld lobby = server.getWorld(LOBBY_KEY);
        if (lobby == null) return;
        for (int i = 0; i < 24; i++) {
            double x = (Math.random() - 0.5) * 20;
            double z = (Math.random() - 0.5) * 20;
            double y = LOBBY_PLATFORM_Y + 6 + Math.random() * 8;
            ItemStack fw = createRandomFirework();
            FireworkRocketEntity rocket = new FireworkRocketEntity(lobby, 0.5 + x, y, 0.5 + z, fw);
            rocket.setVelocity(0, 0.1 + Math.random() * 0.15, 0);
            lobby.spawnEntity(rocket);
        }
        LOGGER.info("[StructureRace] 比赛结束：大厅烟花已燃放。");
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

    // ==================== 聊天消息系统 ====================

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
                    + "§f[全局] §r§a" + sender.getEntityName() + "§7: §r" + display);
            sender.getServer().getPlayerManager().broadcast(msg, false);
        } else if (team != null) {
            // 队聊：仅同队玩家可见
            Text msg = Text.literal(StructureRaceConfig.BROADCAST_PREFIX
                    + "§7[队聊·" + team.teamId + "] §r§a" + sender.getEntityName() + "§7: §r" + display);
            for (ServerPlayerEntity p : sender.getServer().getPlayerManager().getPlayerList()) {
                StructureRaceState.TeamData pTeam = state.getTeamByMember(p.getUuid());
                if (pTeam != null && pTeam.teamId.equals(team.teamId)) {
                    p.sendMessage(msg, false);
                }
            }
        } else {
            // 观众（无队伍）：按全局发送
            Text msg = Text.literal(StructureRaceConfig.BROADCAST_PREFIX
                    + "§7[观众] §r§a" + sender.getEntityName() + "§7: §r" + display);
            sender.getServer().getPlayerManager().broadcast(msg, false);
        }
        return false; // 取消默认广播，使用自定义分发
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
                if (state.distanceAccumulator >= DISTANCE_PER_POINT) {
                    StructureRaceState.TeamData team = getPlayerTeam(saveState, player.getUuid());
                    if (team != null) {
                        team.totalScore += 1;
                        state.lastFindTime = player.getServerWorld().getTime();
                        saveState.getPlayerData(player.getUuid()).lastFindTime = state.lastFindTime;
                        saveState.markDirty();
                        broadcastScore(player, team, "长途跋涉", 1, team.totalScore);
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
            pd.lastFindTime = killer.getServerWorld().getTime();
            state.lastFindTime = pd.lastFindTime;
            saveState.markDirty();
            broadcastScore(killer, team, "消灭怪物浪潮", 1, team.totalScore);
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
        saveState.markDirty();
        state.lastFindTime = player.getServerWorld().getTime();
        saveState.getPlayerData(player.getUuid()).lastFindTime = state.lastFindTime;
        broadcastScore(player, team, "踏入" + dimName, score, team.totalScore);
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
                // 提示该玩家（仅本人可见）
                sendActionBar(p, StructureRaceConfig.BROADCAST_PREFIX
                        + "§a落后补偿：已获得速度提升 I（落后第一名 ≥" + COMPENSATION_GAP + " 分）");
            }
        }
    }

    // ==================== 机制6：迷路指引 ====================

    private static void maybeGiveDirectionHint(ServerPlayerEntity player, StructureRaceState saveState) {
        PlayerState state = PLAYER_STATES.get(player.getUuid());
        if (state == null || state.won) return;

        long gameTime = player.getServerWorld().getTime();
        // 5 分钟内有过任何得分则不提示
        if (gameTime - state.lastFindTime < FIND_TIMEOUT_TICKS) return;
        // 距离过近（≤100 格）后，1 分钟后再查询
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

            // 全局冷却，避免多玩家/短时间刷屏
            if (gameTime - lastGlobalGuideTime < GUIDE_GLOBAL_COOLDOWN_TICKS) return;
            lastGlobalGuideTime = gameTime;
            state.lastFindTime = gameTime; // 提示后重置超时计时

            if (nearest == null) {
                // 检索半径内没有结构
                player.sendMessage(Text.literal(StructureRaceConfig.BROADCAST_PREFIX
                        + "§e周围 " + StructureRaceConfig.HINT_SEARCH_RADIUS
                        + " 格内暂未发现竞速结构，继续探索吧！"), false);
                return;
            }

            // 给出具体坐标与距离提示（仅该玩家可见）
            player.sendMessage(Text.literal(StructureRaceConfig.BROADCAST_PREFIX
                    + "§e最近的竞速结构在 §6(" + nearest.getX() + ", " + nearest.getZ()
                    + ")§r，距离 §6" + (int) dist + "§r 格（坐标 X=" + nearest.getX()
                    + " Z=" + nearest.getZ() + "），快去探索吧！"), false);
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
            team.totalScore += finalScore;
            state.lastScoreGameTime = gameTime;
            state.lastFindTime = gameTime;
            saveState.getPlayerData(player.getUuid()).lastFindTime = gameTime;
            saveState.markDirty();

            refreshTeamScoreboard(player.server, team);

            String structName = StructureRaceConfig.STRUCTURE_NAMES.getOrDefault(
                    structKey.getValue().getPath(), structKey.getValue().getPath());
            broadcastScore(player, team, "发现" + structName, finalScore, team.totalScore);

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
        state.lastFindTime = gameTime;
        saveState.getPlayerData(player.getUuid()).lastFindTime = gameTime;
        saveState.markDirty();

        refreshTeamScoreboard(player.server, team);

        String biomeName = StructureRaceConfig.BIOME_NAMES.getOrDefault(biomeId, biomeId);
        broadcastScore(player, team, "探索" + biomeName, scoreValue, team.totalScore);

        checkWinCondition(player, state, team, saveState);
    }

    // ==================== 胜利判定 ====================

    private static void checkWinCondition(ServerPlayerEntity player, PlayerState state,
                                          StructureRaceState.TeamData team, StructureRaceState saveState) {
        if ("timer".equals(cachedWinCondition)) return;
        if (team == null) return;
        if (team.totalScore >= cachedWinScore) {
            saveState.matchActive = false;
            saveState.markDirty();
            cachedMatchActive = false;
            dumpScoreLog(player.server);
            List<String> winners = new ArrayList<>();
            winners.add(team.teamId);
            startEndResult(player.server, winners, false);
            LOGGER.info("[StructureRace] 队伍 {} 获胜！{} 分", team.teamId, team.totalScore);
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

    /** 开始新一局比赛；若比赛已在进行中则拒绝（返回 false），避免重复启动重置玩家状态 */
    public static boolean startMatch(MinecraftServer server) {
        ServerWorld overworld = server.getOverworld();
        StructureRaceState state = StructureRaceState.get(overworld);
        if (state.matchActive) {
            LOGGER.info("[StructureRace] 比赛已在进行中，忽略重复 start。");
            return false;
        }
        state.resetAllPlayers();
        state.matchActive = true;
        state.matchStartTick = overworld.getTime();
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
        lastGlobalGuideTime = -GUIDE_GLOBAL_COOLDOWN_TICKS;
        pendingEndTicks = -1;
        pendingEndServer = null;
        cachedMatchActive = true;
        cachedWinCondition = state.winCondition;
        cachedWinScore = state.winScore;

        // 所有玩家从大厅传送到主世界出生点；清空背包；有队伍 = 生存参赛，无队伍 = 旁观者观战
        BlockPos spawnPos = overworld.getSpawnPos();
        // 预生成主世界出生点所在区块（以及周边一圈），避免跨维度传送后客户端看不到任何地形/实体
        overworld.getChunk(spawnPos.getX() >> 4, spawnPos.getZ() >> 4, net.minecraft.world.chunk.ChunkStatus.FULL, true);
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
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
        }

        // 为所有在线玩家重建竞速状态（模拟 JOIN 初始化）
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            onPlayerSpawn(player);
        }

        // 未加入任何队伍的玩家设为旁观者模式（观众）
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (state.getTeamByMember(player.getUuid()) == null) {
                player.changeGameMode(GameMode.SPECTATOR);
                LOGGER.info("[StructureRace] 玩家 {} 未入队（观众），已设为旁观者模式。", player.getEntityName());
            }
        }

        String modeMsg = "timer".equals(state.winCondition)
                ? "限时 " + (state.matchDurationTicks / 20 / 60) + " 分钟！时间结束时积分最高者获胜！"
                : "率先达到 " + state.winScore + " 分者获胜！";
        server.getPlayerManager().broadcast(Text.literal(
                StructureRaceConfig.BROADCAST_PREFIX + "§e🏁 比赛开始！§r" + modeMsg), false);
        LOGGER.info("[StructureRace] 新一局比赛开始，模式: {}", state.winCondition);
        return true;
    }

    public static void stopMatch(MinecraftServer server) {
        StructureRaceState state = StructureRaceState.get(server.getOverworld());
        state.matchActive = false;
        state.markDirty();
        cachedMatchActive = false;
        server.getPlayerManager().broadcast(Text.literal(
                StructureRaceConfig.BROADCAST_PREFIX + "§c比赛已停止，暂停计分。§r"), false);
    }

    public static void resumeMatch(MinecraftServer server) {
        StructureRaceState state = StructureRaceState.get(server.getOverworld());
        state.matchActive = true;
        state.markDirty();
        cachedMatchActive = true;
        server.getPlayerManager().broadcast(Text.literal(
                StructureRaceConfig.BROADCAST_PREFIX + "§a比赛已恢复，继续计分。§r"), false);
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
        cachedMatchActive = false;
        // 队伍分数已归零，刷新队伍计分板（无成员队伍不显示）
        refreshAllTeamScoreboards(server);
        // 重置后回到准备阶段：全体玩家回大厅
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            teleportToLobby(p);
        }
        server.getPlayerManager().broadcast(Text.literal(
                StructureRaceConfig.BROADCAST_PREFIX + "§c比赛已重置（未开始）。§r"), false);
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

        // 观众（旁观者）加入队伍：传送回出生点并切回生存
        if (player.isSpectator()) {
            BlockPos spawnPos = server.getOverworld().getSpawnPos();
            player.teleport(server.getOverworld(), spawnPos.getX() + 0.5, spawnPos.getY(),
                    spawnPos.getZ() + 0.5, player.getYaw(), player.getPitch());
        }
        player.changeGameMode(GameMode.SURVIVAL);

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
        server.getPlayerManager().broadcast(Text.literal(
                StructureRaceConfig.BROADCAST_PREFIX
                        + "§c[" + targetTeam.teamId + "]§r §a" + target.getEntityName()
                        + " §r被召回至队友身边（队伍 -§6" + RECALL_COST + "§r 分）"), false);
        LOGGER.info("[StructureRace] 玩家 {} 被召回（队伍 {}）", target.getEntityName(), targetTeam.teamId);
        return 0;
    }

    // ==================== 队伍信息查询 ====================

    public static List<String> listTeams(MinecraftServer server) {
        StructureRaceState state = StructureRaceState.get(server.getOverworld());
        List<String> lines = new ArrayList<>();
        if (state.getAllTeams().isEmpty()) {
            lines.add("当前没有队伍。");
            return lines;
        }
        for (StructureRaceState.TeamData team : state.getAllTeams().values()) {
            StringBuilder members = new StringBuilder();
            for (UUID uuid : team.members) {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
                if (members.length() > 0) members.append(", ");
                members.append(p != null ? p.getEntityName() : uuid.toString().substring(0, 8));
            }
            String zh = StructureRaceConfig.TEAM_NAMES_ZH.getOrDefault(team.teamId, team.teamId);
            Formatting color = StructureRaceConfig.TEAM_FORMATTING.get(team.teamId);
            lines.add((color != null ? color.toString() : "") + zh + "§r (§e" + team.totalScore
                    + "§r 分): " + (members.length() == 0 ? "§7无成员" : members));
        }
        return lines;
    }

    public static List<String> getTeamInfo(MinecraftServer server, String teamId) {
        StructureRaceState state = StructureRaceState.get(server.getOverworld());
        StructureRaceState.TeamData team = state.getTeam(teamId);
        if (team == null) return null;
        List<String> lines = new ArrayList<>();
        lines.add("§6队伍 " + team.teamId + "§r  总分: §e" + team.totalScore + "§r 分  "
                + "成员: " + team.members.size() + "人");

        StringBuilder structLine = new StringBuilder("§a已发现结构: §r");
        if (team.discoveredStructures.isEmpty()) {
            structLine.append("无");
        } else {
            List<String> names = new ArrayList<>();
            for (String uniqueId : team.discoveredStructures) {
                String regName = extractRegistryName(uniqueId);
                names.add(StructureRaceConfig.STRUCTURE_NAMES.getOrDefault(regName, regName));
            }
            structLine.append(String.join("、", names));
        }
        lines.add(structLine.toString());

        StringBuilder biomeLine = new StringBuilder("§a已发现群系: §r");
        if (team.discoveredBiomes.isEmpty()) {
            biomeLine.append("无");
        } else {
            List<String> names = new ArrayList<>();
            for (String biomeId : team.discoveredBiomes) {
                names.add(StructureRaceConfig.BIOME_NAMES.getOrDefault(biomeId, biomeId));
            }
            biomeLine.append(String.join("、", names));
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

    public static String getStatus(MinecraftServer server) {
        StructureRaceState state = StructureRaceState.get(server.getOverworld());
        String mode = "timer".equals(state.winCondition) ? "限时制" : "积分制";
        String active = state.matchActive ? "进行中" : "已停止";
        String timePart = "";
        if ("timer".equals(state.winCondition) && state.matchActive) {
            long remaining = state.matchDurationTicks - (server.getOverworld().getTime() - state.matchStartTick);
            timePart = "，剩余 " + formatSeconds(Math.max(0, remaining / 20));
        } else if ("timer".equals(state.winCondition)) {
            timePart = "，时长 " + (state.matchDurationTicks / 20 / 60) + " 分钟";
        }
        return "§6[竞速] §r当前模式: " + mode + "，状态: " + active + timePart
                + (("score".equals(state.winCondition)) ? "，获胜分数: " + state.winScore : "")
                + "，队伍数: " + state.getAllTeams().size();
    }

    public static long getRemainingSeconds(MinecraftServer server) {
        ServerWorld overworld = server.getOverworld();
        StructureRaceState state = StructureRaceState.get(overworld);
        if (!"timer".equals(state.winCondition) || !state.matchActive) return -1;
        long remaining = state.matchDurationTicks - (overworld.getTime() - state.matchStartTick);
        return Math.max(0, remaining / 20);
    }

    public static List<String> getLeaderboard(MinecraftServer server) {
        StructureRaceState state = StructureRaceState.get(server.getOverworld());
        List<StructureRaceState.TeamData> teams = new ArrayList<>(state.getAllTeams().values());
        teams.sort(Comparator.comparingInt((StructureRaceState.TeamData t) -> t.totalScore).reversed());
        List<String> lines = new ArrayList<>();
        int rank = 1;
        for (StructureRaceState.TeamData t : teams) {
            if (t.members.isEmpty()) continue; // 空队不参与排名
            String zh = StructureRaceConfig.TEAM_NAMES_ZH.getOrDefault(t.teamId, t.teamId);
            Formatting color = StructureRaceConfig.TEAM_FORMATTING.get(t.teamId);
            lines.add("§e" + rank + ". §r" + (color != null ? color : "") + zh
                    + "§r: §6" + t.totalScore + "§r 分（" + t.members.size() + "人）");
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
                                        String reason, int earned, int total) {
        SCORE_LOG.add(new ScoreLogEntry(player.server.getOverworld().getTime(),
                player.getEntityName(), team.teamId, reason, earned, total));
        Text message = Text.literal(
                StructureRaceConfig.BROADCAST_PREFIX
                        + "§c[" + team.teamId + "]§r §a" + player.getName().getString()
                        + " §r获得 §6+" + earned + "§r 分（" + reason
                        + "），队伍累计 §6" + total + "§r 分");
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
        private long lastFindTime; // 机制6：上次任何加分的时间
        private long lastHintTime; // 机制6：上次迷路指引查询时间（用于过近时 1 分钟重试）
        private boolean won;
        private BlockPos lastDistancePos; // 机制1
        private double distanceAccumulator; // 机制1
        private int killCount; // 机制2
        private String lastDimension; // 机制4：上次所在维度
    }
}
