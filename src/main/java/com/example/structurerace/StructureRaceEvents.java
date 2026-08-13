package com.example.structurerace;

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
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.Monster;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.StructureTags;
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
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.structure.Structure;

/**
 * 结构竞速 (Structure Race) V2.5 - 事件监听类
 *
 * <p>核心规则（V2.5 起）：
 * <ul>
 *   <li><b>观众模式</b>：只有加入队伍的玩家能参与竞速；无队伍玩家视为观众（旁观者），
 *       所有计分与机制对观众无效。</li>
 *   <li>计分板显示「队名+玩家名」+ 队伍总分，团队作为整体看待。</li>
 *   <li>平衡机制：里程计分、击杀计分、队伍召回、维度奖励、落后补偿、迷路指引。</li>
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

    /** 机制5：落后补偿判定分差 */
    private static final int COMPENSATION_GAP = 30;

    /** 机制6：连续 5 分钟无任何发现触发指引 */
    private static final long FIND_TIMEOUT_TICKS = 300L * 20L;
    /** 机制6：指引全局冷却（10 分钟） */
    private static final long GUIDE_GLOBAL_COOLDOWN_TICKS = 600L * 20L;
    private static final int GUIDE_RADIUS = 512;

    // ==================== 内存状态 ====================

    private static final Map<UUID, PlayerState> PLAYER_STATES = new HashMap<>();
    private static final Map<UUID, String> PLAYER_SCOREBOARD_KEYS = new HashMap<>();
    private static final Map<UUID, String> playerTeamMap = new HashMap<>();

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

        // 机制4：维度进入奖励（轮询检测维度变化）

        // 机制2：击杀敌对怪物
        ServerLivingEntityEvents.AFTER_DEATH.register(StructureRaceEvents::onEntityDeath);

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            PLAYER_STATES.clear();
            PLAYER_SCOREBOARD_KEYS.clear();
            playerTeamMap.clear();
            lastAnnouncedSeconds = -1;
            lastGlobalGuideTime = -GUIDE_GLOBAL_COOLDOWN_TICKS;
            tickCounter = 0;
            LOGGER.info("[StructureRace] 新世界服务器启动，内存竞速状态已重置。");
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

        // 机制5：落后队伍速度补偿（每 100 tick）
        if (tickCounter % 100 == 0) {
            applySpeedCompensation(server);
        }

        ServerWorld overworld = server.getOverworld();
        StructureRaceState saveState = StructureRaceState.get(overworld);

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player.isSpectator()) continue;
            if (!cachedMatchActive) continue;
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

        String winnerName = null;
        int best = -1;
        for (StructureRaceState.TeamData team : state.getAllTeams().values()) {
            if (team.totalScore > best) {
                best = team.totalScore;
                winnerName = "队伍 " + team.teamId;
            }
        }

        if (winnerName == null) {
            server.getPlayerManager().broadcast(Text.literal(
                    StructureRaceConfig.BROADCAST_PREFIX + "§e⏰ §r比赛结束，无人得分！"), false);
        } else {
            server.getPlayerManager().broadcast(Text.literal(
                    StructureRaceConfig.BROADCAST_PREFIX
                            + "§e⏰ §6" + winnerName + " §r以 §6" + best + "§r 分获得胜利！ §e🎉"), false);
            LOGGER.info("[StructureRace] 限时赛结束，胜者: {} ({}分)", winnerName, best);
        }
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

        // 比赛进行中：无队伍玩家 = 观众（旁观者）
        StructureRaceState.TeamData team = saveState.getTeamByMember(player.getUuid());
        if (cachedMatchActive && team == null) {
            player.changeGameMode(GameMode.SPECTATOR);
            LOGGER.info("[StructureRace] 玩家 {} 未入队（观众），已设为旁观者。", player.getEntityName());
        }

        if (team != null) {
            playerTeamMap.put(player.getUuid(), team.teamId);
            addPlayerToScoreboardTeam(player, team);
        }

        Scoreboard scoreboard = player.getScoreboard();
        ScoreboardObjective objective = getOrCreateObjective(scoreboard);
        String key = computeScoreboardKey(player, scoreboard, objective);
        PLAYER_SCOREBOARD_KEYS.put(player.getUuid(), key);
        int displayScore = team != null ? team.totalScore : state.totalScore;
        scoreboard.getPlayerScore(key, objective).setScore(displayScore);

        LOGGER.info("[StructureRace] 玩家 {} 加入：队伍={}, 显示分={}, won={}",
                player.getName().getString(), team != null ? team.teamId : "观众",
                displayScore, state.won);
    }

    private static void onPlayerDisconnect(ServerPlayerEntity player) {
        Scoreboard scoreboard = player.getScoreboard();
        ScoreboardObjective objective = scoreboard.getNullableObjective(
                StructureRaceConfig.SCOREBOARD_OBJECTIVE_NAME);
        String key = PLAYER_SCOREBOARD_KEYS.remove(player.getUuid());
        if (objective != null && key != null) {
            scoreboard.resetPlayerScore(key, objective);
        }
        // 移除内存状态：重进时从持久化数据干净重建，保证断线重连与服务器重启行为一致
        PLAYER_STATES.remove(player.getUuid());
    }

    // ==================== 机制1：跑图里程计分 ====================

    private static void trackDistance(ServerPlayerEntity player, StructureRaceState saveState) {
        PlayerState state = PLAYER_STATES.get(player.getUuid());
        if (state == null || state.won) return;

        BlockPos pos = player.getBlockPos();
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
                        updateTeamScoreboard(player.server, team);
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
        if (source.getAttacker() instanceof ServerPlayerEntity p) {
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
            updateTeamScoreboard(killer.server, team);
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
        updateTeamScoreboard(player.server, team);
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
            }
        }
    }

    // ==================== 机制6：迷路指引 ====================

    private static void maybeGiveDirectionHint(ServerPlayerEntity player, StructureRaceState saveState) {
        PlayerState state = PLAYER_STATES.get(player.getUuid());
        if (state == null || state.won) return;

        long gameTime = player.getServerWorld().getTime();
        if (gameTime - state.lastFindTime < FIND_TIMEOUT_TICKS) return;
        if (gameTime - lastGlobalGuideTime < GUIDE_GLOBAL_COOLDOWN_TICKS) return;
        lastGlobalGuideTime = gameTime;
        state.lastFindTime = gameTime; // 防止连续触发刷屏

        ServerWorld world = player.getServerWorld();
        BlockPos pos = player.getBlockPos();
        RegistryKey<World> dim = world.getRegistryKey();

        try {
            if (dim == World.NETHER) {
                // 下界：无堡垒 tag 可查询，给出通用提示（避免不安全的定位）
                player.sendMessage(Text.literal(StructureRaceConfig.BROADCAST_PREFIX
                        + "§e在险恶的下界中，尝试沿岩壁寻找阴暗的砖石建筑，或向高处探索。"), false);
            } else if (dim == World.END) {
                // 末地：无单结构 tag 可查询，给出通用提示
                player.sendMessage(Text.literal(StructureRaceConfig.BROADCAST_PREFIX
                        + "§e在末地外岛继续前行，寻找浮空的紫珀建筑；注意脚下虚空。"), false);
            } else {
                // 主世界：先找村庄，再找破损传送门
                BlockPos village = world.locateStructure(StructureTags.VILLAGE, pos, GUIDE_RADIUS, false);
                if (village != null) {
                    sendDirectionHint(player, pos, village, "村庄", null);
                    return;
                }
                BlockPos portal = world.locateStructure(StructureTags.RUINED_PORTAL, pos, GUIDE_RADIUS, false);
                if (portal != null) {
                    sendDirectionHint(player, pos, portal, "破损传送门", null);
                    return;
                }
                player.sendMessage(Text.literal(StructureRaceConfig.BROADCAST_PREFIX
                        + "§e附近 512 格内未找到村庄或传送门。尝试向高处眺望，或沿河流/道路前行寻找文明痕迹。"), false);
            }
        } catch (Exception e) {
            LOGGER.warn("[StructureRace] 指引查询失败: {}", e.getMessage());
        }
    }

    private static void sendDirectionHint(ServerPlayerEntity player, BlockPos from, BlockPos to,
                                           String name, String fallback) {
        if (to == null) {
            if (fallback != null) {
                player.sendMessage(Text.literal(StructureRaceConfig.BROADCAST_PREFIX + fallback), false);
            }
            return;
        }
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double dist = Math.round(Math.sqrt(dx * dx + dz * dz));
        player.sendMessage(Text.literal(StructureRaceConfig.BROADCAST_PREFIX
                + "最近的 §e" + name + "§r 在你 §6" + getDirection(dx, dz)
                + "§r 方向，约 §6" + (int) dist + "§r 格！"), false);
    }

    private static String getDirection(double dx, double dz) {
        int[][] dirs = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};
        String[] names = {"东", "东南", "南", "西南", "西", "西北", "北", "东北"};
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.5) return "脚下";
        int best = 0;
        double bestDot = -999;
        for (int i = 0; i < 8; i++) {
            double dot = (dx * dirs[i][0] + dz * dirs[i][1]) / len;
            if (dot > bestDot) {
                bestDot = dot;
                best = i;
            }
        }
        return names[best];
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

        Registry<Structure> registry = world.getRegistryManager().get(RegistryKeys.STRUCTURE);
        for (RegistryKey<Structure> structKey : StructureRaceConfig.getTargetStructures()) {
            Integer scoreValue = StructureRaceConfig.STRUCTURE_SCORES.get(structKey);
            if (scoreValue == null) continue;

            Structure structure = registry.get(structKey);
            if (structure == null) continue;

            StructureStart start;
            try {
                start = world.getStructureAccessor().getStructureContaining(pos, structure);
            } catch (Exception e) {
                continue;
            }
            if (start == null || !start.hasChildren()) continue;

            String uniqueId = structKey.getValue() + ":" + start.getPos().toLong();

            // 全局独占去重：已被任意队伍发现的实例，其他队伍不再加分
            if (saveState.globallyDiscoveredStructures.contains(uniqueId)) return;
            if (team.discoveredStructures.contains(uniqueId)) return;

            // ===== 新结构！加分 =====
            team.discoveredStructures.add(uniqueId);
            saveState.globallyDiscoveredStructures.add(uniqueId);
            team.totalScore += scoreValue;
            state.lastScoreGameTime = gameTime;
            state.lastFindTime = gameTime;
            saveState.getPlayerData(player.getUuid()).lastFindTime = gameTime;
            saveState.markDirty();

            updateTeamScoreboard(player.server, team);

            String structName = structKey.getValue().getPath().replace('_', ' ');
            broadcastScore(player, team, "发现" + structName, scoreValue, team.totalScore);

            checkWinCondition(player, state, team, saveState);
            return;
        }
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

        updateTeamScoreboard(player.server, team);

        String biomeName = biomeKey.getValue().getPath().replace('_', ' ');
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
            player.server.getPlayerManager().broadcast(Text.literal(
                    StructureRaceConfig.BROADCAST_PREFIX
                            + "§e🎉 §6队伍 " + team.teamId + " §r率先达到 §6" + team.totalScore
                            + "§r 分，获得胜利！ §e🎉"), false);
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
        sbTeam.setColor(TEAM_COLORS[team.colorIndex % TEAM_COLORS.length]);
        return sbTeam;
    }

    private static void updateTeamScoreboard(MinecraftServer server, StructureRaceState.TeamData team) {
        for (UUID uuid : team.members) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
            if (p != null) {
                updateScoreboard(p, team.totalScore);
            }
        }
    }

    /** 刷新某玩家的计分板条目名（加入/离开队伍后队名变化时调用） */
    private static void refreshScoreboardKey(ServerPlayerEntity player) {
        Scoreboard scoreboard = player.getScoreboard();
        ScoreboardObjective objective = getOrCreateObjective(scoreboard);
        String oldKey = PLAYER_SCOREBOARD_KEYS.remove(player.getUuid());
        if (oldKey != null) {
            scoreboard.resetPlayerScore(oldKey, objective);
        }
        String newKey = computeScoreboardKey(player, scoreboard, objective);
        PLAYER_SCOREBOARD_KEYS.put(player.getUuid(), newKey);
    }

    // ==================== 比赛控制（供 /race 命令调用） ====================

    public static void startMatch(MinecraftServer server) {
        ServerWorld overworld = server.getOverworld();
        StructureRaceState state = StructureRaceState.get(overworld);
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
        PLAYER_SCOREBOARD_KEYS.clear();
        lastAnnouncedSeconds = -1;
        lastGlobalGuideTime = -GUIDE_GLOBAL_COOLDOWN_TICKS;
        cachedMatchActive = true;
        cachedWinCondition = state.winCondition;
        cachedWinScore = state.winScore;

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

        // 先把所有在线玩家的计分板分数归零（必须在清空 PLAYER_SCOREBOARD_KEYS 之前）
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            String key = PLAYER_SCOREBOARD_KEYS.get(player.getUuid());
            if (key != null) {
                Scoreboard sb = player.getScoreboard();
                ScoreboardObjective obj = sb.getNullableObjective(StructureRaceConfig.SCOREBOARD_OBJECTIVE_NAME);
                if (obj != null) {
                    sb.getPlayerScore(key, obj).setScore(0);
                }
            }
        }
        PLAYER_STATES.clear();
        PLAYER_SCOREBOARD_KEYS.clear();
        cachedMatchActive = false;
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
                    refreshScoreboardKey(p);
                }
            }
            playerTeamMap.remove(uuid);
        }
        state.removeTeam(teamId);
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

        // 刷新计分板条目名（加上队名）并显示队总分
        refreshScoreboardKey(player);
        updateScoreboard(player, newTeam.totalScore);
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
        refreshScoreboardKey(player);
        PlayerState ps = PLAYER_STATES.get(player.getUuid());
        updateScoreboard(player, ps != null ? ps.totalScore : 0);
        LOGGER.info("[StructureRace] 玩家 {} 已离开队伍 {}", playerName, team.teamId);
        return 0;
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

        updateTeamScoreboard(server, targetTeam);
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
            lines.add("§6" + team.teamId + "§r (§e" + team.totalScore + "§r 分): "
                    + (members.length() == 0 ? "无成员" : members));
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

    public static List<String> getLeaderboard() {
        List<Map.Entry<UUID, PlayerState>> list = new ArrayList<>(PLAYER_STATES.entrySet());
        list.sort(Comparator.comparingInt((Map.Entry<UUID, PlayerState> e) -> e.getValue().totalScore).reversed());
        List<String> lines = new ArrayList<>();
        int rank = 1;
        for (Map.Entry<UUID, PlayerState> e : list) {
            String teamName = playerTeamMap.get(e.getKey());
            String name = teamName != null ? "[" + teamName + "]" + e.getValue().playerName : e.getValue().playerName;
            lines.add("§e" + rank + ". §r" + name + ": §6" + e.getValue().totalScore + "§r 分");
            rank++;
        }
        return lines;
    }

    // ==================== 计分板 ====================

    private static void updateScoreboard(ServerPlayerEntity player, int newScore) {
        Scoreboard scoreboard = player.getScoreboard();
        ScoreboardObjective objective = getOrCreateObjective(scoreboard);
        String key = PLAYER_SCOREBOARD_KEYS.get(player.getUuid());
        if (key == null) {
            key = computeScoreboardKey(player, scoreboard, objective);
            PLAYER_SCOREBOARD_KEYS.put(player.getUuid(), key);
        }
        scoreboard.getPlayerScore(key, objective).setScore(newScore);
    }

    private static ScoreboardObjective getOrCreateObjective(Scoreboard scoreboard) {
        ScoreboardObjective objective = scoreboard.getNullableObjective(
                StructureRaceConfig.SCOREBOARD_OBJECTIVE_NAME);
        if (objective == null) {
            objective = scoreboard.addObjective(
                    StructureRaceConfig.SCOREBOARD_OBJECTIVE_NAME,
                    ScoreboardCriterion.DUMMY,
                    Text.literal(StructureRaceConfig.SCOREBOARD_DISPLAY_NAME),
                    ScoreboardCriterion.RenderType.INTEGER);
            scoreboard.setObjectiveSlot(0, objective);
            scoreboard.setObjectiveSlot(1, objective);
            LOGGER.info("[StructureRace] 已创建计分板目标: {}",
                    StructureRaceConfig.SCOREBOARD_OBJECTIVE_NAME);
        }
        return objective;
    }

    /** 计分板条目名：有队伍显示「[队名]玩家名」，观众显示玩家名 */
    private static String computeScoreboardKey(ServerPlayerEntity player, Scoreboard scoreboard,
                                                ScoreboardObjective objective) {
        String teamName = playerTeamMap.get(player.getUuid());
        String rawName = teamName != null
                ? "[" + teamName + "]" + player.getEntityName()
                : player.getEntityName();
        String truncated = truncatePlayerName(rawName);
        Set<String> used = new HashSet<>();
        for (Map.Entry<UUID, String> e : PLAYER_SCOREBOARD_KEYS.entrySet()) {
            if (!e.getKey().equals(player.getUuid())) used.add(e.getValue());
        }
        String key = truncated;
        int suffix = 2;
        while (used.contains(key)) {
            key = truncatePlayerName(rawName) + "~" + suffix++;
        }
        return key;
    }

    private static String truncatePlayerName(String name) {
        int max = StructureRaceConfig.MAX_SCOREBOARD_NAME_LENGTH;
        if (name.length() <= max) return name;
        return name.substring(0, max - 1) + "\u2026";
    }

    // ==================== 广播 ====================

    private static void broadcastScore(ServerPlayerEntity player, StructureRaceState.TeamData team,
                                        String reason, int earned, int total) {
        Text message = Text.literal(
                StructureRaceConfig.BROADCAST_PREFIX
                        + "§c[" + team.teamId + "]§r §a" + player.getName().getString()
                        + " §r获得 §6+" + earned + "§r 分（" + reason
                        + "），队伍累计 §6" + total + "§r 分");
        player.server.getPlayerManager().broadcast(message, false);
    }

    private static void broadcastWin(ServerPlayerEntity player, int total) {
        Text message = Text.literal(
                StructureRaceConfig.BROADCAST_PREFIX
                        + "§e🎉 §6" + player.getName().getString()
                        + " §r率先达到 §6" + total + "§r 分，获得胜利！ §e🎉");
        player.server.getPlayerManager().broadcast(message, false);
    }

    // ==================== 内部状态类 ====================

    private static final class PlayerState {
        private String playerName;
        private final Set<String> discoveredStructures = new HashSet<>();
        private final Set<String> discoveredBiomes = new HashSet<>();
        private int totalScore;
        private long lastScoreGameTime;
        private long lastBiomeCheckTime;
        private long lastFindTime; // 机制6：上次任何加分的时间
        private boolean won;
        private BlockPos lastDistancePos; // 机制1
        private double distanceAccumulator; // 机制1
        private int killCount; // 机制2
        private String lastDimension; // 机制4：上次所在维度
    }
}
