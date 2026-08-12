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

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
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
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.structure.Structure;

/**
 * 结构竞速 (Structure Race) V2.2 - 事件监听类
 *
 * <p>新增组队系统：
 * <ul>
 *   <li>支持队伍创建/解散，管理员可把玩家移入/移出队伍。</li>
 *   <li>队伍级去重：队员共同发现的结构/群系只计分一次，队伍总分共享。</li>
 *   <li>组队与单人模式共存：无队伍者仍按个人计分，互不冲突。</li>
 *   <li>Tab 玩家列表按队伍显示不同颜色（基于原版计分板 Team）。</li>
 *   <li>队伍级胜利判定（积分制/限时制均支持）。</li>
 * </ul>
 */
public final class StructureRaceEvents {

    private static final Logger LOGGER = LoggerFactory.getLogger("StructureRace");

    private static final long COOLDOWN_TICKS = StructureRaceConfig.SCORE_COOLDOWN_SECONDS * 20L;
    private static final long BIOME_COOLDOWN_TICKS = StructureRaceConfig.BIOME_CHECK_COOLDOWN_TICKS;

    private static final Map<UUID, PlayerState> PLAYER_STATES = new HashMap<>();
    private static final Map<UUID, String> PLAYER_SCOREBOARD_KEYS = new HashMap<>();

    /** 玩家 UUID → 队伍 ID（内存缓存，与 PersistentState 的 teams 同步） */
    private static final Map<UUID, String> playerTeamMap = new HashMap<>();

    /** 队伍颜色循环表（用于 Tab 列表玩家名变色） */
    private static final Formatting[] TEAM_COLORS = {
            Formatting.RED, Formatting.BLUE, Formatting.GREEN, Formatting.YELLOW,
            Formatting.LIGHT_PURPLE, Formatting.AQUA, Formatting.GOLD, Formatting.DARK_GREEN
    };

    // ==================== 比赛状态内存缓存 ====================
    private static boolean cachedMatchActive = false;
    private static String cachedWinCondition = "score";
    private static int cachedWinScore = StructureRaceConfig.WIN_SCORE;
    private static long lastAnnouncedSeconds = -1;

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

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            PLAYER_STATES.clear();
            PLAYER_SCOREBOARD_KEYS.clear();
            playerTeamMap.clear();
            lastAnnouncedSeconds = -1;
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

        LOGGER.info("[StructureRace] V2.2 事件监听器已注册完成。");
    }

    // ==================== Tick 回调 ====================

    private static void onServerTick(MinecraftServer server) {
        tickMatchTimer(server);

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player.age % StructureRaceConfig.CHECK_INTERVAL_TICKS != 0) continue;
            checkPlayerStructure(player);
            checkPlayerBiome(player);
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

        // 比较队伍
        for (StructureRaceState.TeamData team : state.getAllTeams().values()) {
            if (team.totalScore > best) {
                best = team.totalScore;
                winnerName = "队伍 " + team.teamId;
            }
        }
        // 比较单人玩家（不在队伍中的）
        for (Map.Entry<UUID, PlayerState> e : PLAYER_STATES.entrySet()) {
            if (playerTeamMap.containsKey(e.getKey())) continue;
            PlayerState ps = e.getValue();
            if (ps.totalScore > best) {
                best = ps.totalScore;
                winnerName = ps.playerName;
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

        // 恢复个人进度
        StructureRaceState.PlayerPersistentData pd = saveState.getExistingPlayerData(player.getUuid());
        if (pd != null) {
            state.discoveredStructures.addAll(pd.discoveredStructures);
            state.discoveredBiomes.addAll(pd.discoveredBiomes);
            state.totalScore = pd.totalScore;
            state.won = pd.won;
        }

        // 比赛正在进行中的中途加入者：如果未入队则设为旁观者模式
        StructureRaceState.TeamData team = saveState.getTeamByMember(player.getUuid());
        if (cachedMatchActive && team == null) {
            player.changeGameMode(net.minecraft.world.GameMode.SPECTATOR);
            LOGGER.info("[StructureRace] 玩家 {} 中途加入但无队伍，已设为旁观者。",
                    player.getEntityName());
        }

        // 恢复队伍归属并同步 Tab 颜色
        if (team != null) {
            playerTeamMap.put(player.getUuid(), team.teamId);
            addPlayerToScoreboardTeam(player, team);
        }

        Scoreboard scoreboard = player.getScoreboard();
        ScoreboardObjective objective = getOrCreateObjective(scoreboard);
        String key = computeScoreboardKey(player, scoreboard, objective);
        PLAYER_SCOREBOARD_KEYS.put(player.getUuid(), key);
        // 有队伍显示队总分，无队伍显示个人分
        int displayScore = team != null ? team.totalScore : state.totalScore;
        scoreboard.getPlayerScore(key, objective).setScore(displayScore);

        LOGGER.info("[StructureRace] 玩家 {} 加入：{} 分, 队伍={}, won={}",
                player.getName().getString(),
                team != null ? "队伍[" + team.teamId + "] " + team.totalScore : String.valueOf(state.totalScore),
                team != null ? team.teamId : "无", state.won);
    }

    private static void onPlayerDisconnect(ServerPlayerEntity player) {
        Scoreboard scoreboard = player.getScoreboard();
        ScoreboardObjective objective = scoreboard.getNullableObjective(
                StructureRaceConfig.SCOREBOARD_OBJECTIVE_NAME);
        String key = PLAYER_SCOREBOARD_KEYS.remove(player.getUuid());
        if (objective != null && key != null) {
            scoreboard.resetPlayerScore(key, objective);
        }
    }

    // ==================== 结构检测与计分 ====================

    private static void checkPlayerStructure(ServerPlayerEntity player) {
        if (player.isSpectator()) return;
        PlayerState state = PLAYER_STATES.get(player.getUuid());
        if (state == null || state.won) return;
        if (!cachedMatchActive) return;

        long gameTime = player.getServerWorld().getTime();
        if (gameTime - state.lastScoreGameTime < COOLDOWN_TICKS) return;

        BlockPos pos = player.getBlockPos();
        ServerWorld world = player.getServerWorld();
        StructureRaceState saveState = StructureRaceState.get(player.getServer().getOverworld());
        StructureRaceState.TeamData team = getPlayerTeam(saveState, player.getUuid());

        // 去重集合：有队伍用队伍集合，无队伍用个人集合
        Set<String> dedupSet = team != null ? team.discoveredStructures : state.discoveredStructures;

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
            if (dedupSet.contains(uniqueId)) return;

            // ===== 新结构！加分 =====
            dedupSet.add(uniqueId);
            saveState.globallyDiscoveredStructures.add(uniqueId);
            if (team != null) {
                team.totalScore += scoreValue;
            } else {
                state.totalScore += scoreValue;
            }
            state.lastScoreGameTime = gameTime;
            saveState.markDirty();

            int displayScore = team != null ? team.totalScore : state.totalScore;
            if (team != null) {
                updateTeamScoreboard(player.server, team);
            } else {
                updateScoreboard(player, displayScore);
            }

            String structName = structKey.getValue().getPath().replace('_', ' ');
            broadcastDiscover(player, team, structName, scoreValue, displayScore);

            checkWinCondition(player, state, team, saveState);
            return;
        }
    }

    // ==================== 群系检测与计分 ====================

    private static void checkPlayerBiome(ServerPlayerEntity player) {
        if (player.isSpectator()) return;
        PlayerState state = PLAYER_STATES.get(player.getUuid());
        if (state == null || state.won) return;
        if (!cachedMatchActive) return;

        long gameTime = player.getServerWorld().getTime();
        if (gameTime - state.lastBiomeCheckTime < BIOME_COOLDOWN_TICKS) return;
        state.lastBiomeCheckTime = gameTime;

        BlockPos pos = player.getBlockPos();
        ServerWorld world = player.getServerWorld();
        StructureRaceState saveState = StructureRaceState.get(player.getServer().getOverworld());
        StructureRaceState.TeamData team = getPlayerTeam(saveState, player.getUuid());
        Set<String> dedupSet = team != null ? team.discoveredBiomes : state.discoveredBiomes;

        RegistryEntry<Biome> biomeEntry = world.getBiome(pos);
        Optional<RegistryKey<Biome>> biomeKeyOpt = biomeEntry.getKey();
        if (biomeKeyOpt.isEmpty()) return;
        RegistryKey<Biome> biomeKey = biomeKeyOpt.get();

        Integer scoreValue = StructureRaceConfig.BIOME_SCORES.get(biomeKey);
        if (scoreValue == null) return;

        String biomeId = biomeKey.getValue().toString();
        if (dedupSet.contains(biomeId)) return;

        // ===== 新群系！加分 =====
        dedupSet.add(biomeId);
        if (team != null) {
            team.totalScore += scoreValue;
        } else {
            state.totalScore += scoreValue;
        }
        saveState.markDirty();

        int displayScore = team != null ? team.totalScore : state.totalScore;
        if (team != null) {
            updateTeamScoreboard(player.server, team);
        } else {
            updateScoreboard(player, displayScore);
        }

        String biomeName = biomeKey.getValue().getPath().replace('_', ' ');
        broadcastDiscover(player, team, "群系 " + biomeName, scoreValue, displayScore);

        checkWinCondition(player, state, team, saveState);
    }

    // ==================== 胜利判定 ====================

    private static void checkWinCondition(ServerPlayerEntity player, PlayerState state,
                                          StructureRaceState.TeamData team, StructureRaceState saveState) {
        if ("timer".equals(cachedWinCondition)) return; // 限时制由倒计时结算

        if (team != null) {
            if (team.totalScore >= cachedWinScore) {
                // 队伍获胜
                saveState.matchActive = false;
                saveState.markDirty();
                cachedMatchActive = false;
                player.server.getPlayerManager().broadcast(Text.literal(
                        StructureRaceConfig.BROADCAST_PREFIX
                                + "§e🎉 §6队伍 " + team.teamId + " §r率先达到 §6" + team.totalScore
                                + "§r 分，获得胜利！ §e🎉"), false);
                LOGGER.info("[StructureRace] 队伍 {} 获胜！{} 分", team.teamId, team.totalScore);
            }
        } else if (state.totalScore >= cachedWinScore) {
            state.won = true;
            saveState.matchActive = false;
            saveState.getPlayerData(player.getUuid()).won = true;
            saveState.markDirty();
            cachedMatchActive = false;
            broadcastWin(player, state.totalScore);
            LOGGER.info("[StructureRace] 玩家 {} 获得胜利！{} 分", player.getName().getString(), state.totalScore);
        }
    }

    // ==================== 队伍辅助 ====================

    private static StructureRaceState.TeamData getPlayerTeam(StructureRaceState state, UUID uuid) {
        String teamId = playerTeamMap.get(uuid);
        if (teamId == null) return null;
        StructureRaceState.TeamData team = state.getTeam(teamId);
        if (team == null) {
            playerTeamMap.remove(uuid); // 队伍已解散，清理缓存
            return null;
        }
        return team;
    }

    /** 将在线玩家加入原版计分板 Team，实现 Tab 列表名字变色 */
    private static void addPlayerToScoreboardTeam(ServerPlayerEntity player, StructureRaceState.TeamData team) {
        try {
            Scoreboard scoreboard = player.getScoreboard();
            Team sbTeam = getOrCreateScoreboardTeam(scoreboard, team);
            scoreboard.addPlayerToTeam(player.getEntityName(), sbTeam);
        } catch (Exception e) {
            LOGGER.warn("[StructureRace] 同步队伍颜色失败: {}", e.getMessage());
        }
    }

    /** 将在线玩家从原版计分板 Team 移除（恢复默认颜色） */
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

    /** 队伍积分变化时，刷新所有在线队员的计分板显示 */
    private static void updateTeamScoreboard(MinecraftServer server, StructureRaceState.TeamData team) {
        for (UUID uuid : team.members) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
            if (p != null) {
                updateScoreboard(p, team.totalScore);
            }
        }
    }

    // ==================== 比赛控制（供 /race 命令调用） ====================

    /** 开始新一局：清空所有分数与队伍已发现集合，重置队伍积分。 */
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
        }
        state.globallyDiscoveredStructures.clear();
        state.markDirty();

        PLAYER_STATES.clear();
        PLAYER_SCOREBOARD_KEYS.clear();
        lastAnnouncedSeconds = -1;
        cachedMatchActive = true;
        cachedWinCondition = state.winCondition;
        cachedWinScore = state.winScore;

        // 为所有在线玩家重建竞速状态（模拟 JOIN 初始化，否则清空后无法再计分）
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            onPlayerSpawn(player);
        }

        // 未加入任何队伍的玩家设为旁观者模式（比赛开始后不能以自由人身份参与）
        StructureRaceState raceState = StructureRaceState.get(server.getOverworld());
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (raceState.getTeamByMember(player.getUuid()) == null) {
                player.changeGameMode(net.minecraft.world.GameMode.SPECTATOR);
                LOGGER.info("[StructureRace] 玩家 {} 未入队，已设为旁观者模式。", player.getEntityName());
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

    /**
     * 恢复已暂停的比赛（不清空分数，不重置倒计时）。
     * 限时制的剩余时间从上次开始的时间继续计算，已过去的时间不扣回。
     */
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

    /** 热修改积分制获胜分数（在比赛开始前设置） */
    public static void setWinScore(MinecraftServer server, int score) {
        StructureRaceState state = StructureRaceState.get(server.getOverworld());
        state.winScore = Math.max(1, score);
        state.markDirty();
        cachedWinScore = state.winScore;
    }

    /** 热修改限时制时长（秒），并同步当前比赛的剩余时长（若比赛未开始则从下次 start 生效） */
    public static void setMatchDuration(MinecraftServer server, int seconds) {
        StructureRaceState state = StructureRaceState.get(server.getOverworld());
        state.matchDurationTicks = Math.max(1, seconds) * 20L;
        state.markDirty();
        LOGGER.info("[StructureRace] 限时制时长已修改为 {} 秒", seconds);
    }

    // ==================== 队伍管理（供 /race team 命令调用） ====================

    /** 创建队伍；已存在则返回 false */
    public static boolean createTeam(MinecraftServer server, String teamId) {
        StructureRaceState state = StructureRaceState.get(server.getOverworld());
        if (state.getTeam(teamId) != null) return false;
        state.createTeam(teamId);
        LOGGER.info("[StructureRace] 创建队伍: {}", teamId);
        return true;
    }

    /** 解散队伍：所有队员退出原版计分板 Team 并清理缓存 */
    public static boolean disbandTeam(MinecraftServer server, String teamId) {
        StructureRaceState state = StructureRaceState.get(server.getOverworld());
        StructureRaceState.TeamData team = state.getTeam(teamId);
        if (team == null) return false;

        for (UUID uuid : team.members) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
            if (p != null) {
                removePlayerFromScoreboardTeam(p, team);
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

        // 若玩家已有队伍，先移出
        StructureRaceState.TeamData oldTeam = state.getTeamByMember(player.getUuid());
        if (oldTeam != null && oldTeam != newTeam) {
            oldTeam.members.remove(player.getUuid());
            removePlayerFromScoreboardTeam(player, oldTeam);
        }

        newTeam.members.add(player.getUuid());
        playerTeamMap.put(player.getUuid(), newTeam.teamId);
        addPlayerToScoreboardTeam(player, newTeam);
        state.markDirty();

        // 将旁观者玩家传送回世界出生点并切回生存模式
        if (player.isSpectator()) {
            BlockPos spawnPos = server.getOverworld().getSpawnPos();
            player.teleport(server.getOverworld(), spawnPos.getX() + 0.5, spawnPos.getY(),
                    spawnPos.getZ() + 0.5, player.getYaw(), player.getPitch());
        }
        player.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);

        // 刷新计分板显示为该队总分
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

        // 刷新为该玩家个人分
        PlayerState ps = PLAYER_STATES.get(player.getUuid());
        updateScoreboard(player, ps != null ? ps.totalScore : 0);
        LOGGER.info("[StructureRace] 玩家 {} 已离开队伍 {}", playerName, team.teamId);
        return 0;
    }

    /** 返回所有队伍及成员的格式化列表 */
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

    /** 返回某玩家所属队伍；无队伍返回 null */
    public static String getPlayerTeamName(MinecraftServer server, String playerName) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerName);
        if (player == null) return null;
        StructureRaceState state = StructureRaceState.get(server.getOverworld());
        StructureRaceState.TeamData team = state.getTeamByMember(player.getUuid());
        return team != null ? team.teamId : null;
    }

    /**
     * 获取某队伍的已发现结构/群系详情（中文名、水平排列）。
     */
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

    /** 查询某结构是否已被指定队伍发现（支持中文名或英文注册名） */
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
            lines.add("§e" + rank + ". §r" + e.getValue().playerName + ": §6" + e.getValue().totalScore + "§r 分");
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

    private static String computeScoreboardKey(ServerPlayerEntity player, Scoreboard scoreboard,
                                                ScoreboardObjective objective) {
        String rawName = player.getEntityName();
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

    private static void broadcastDiscover(ServerPlayerEntity player, StructureRaceState.TeamData team,
                                           String what, int earned, int total) {
        String who = team != null
                ? "§c[" + team.teamId + "]§r " + player.getName().getString()
                : player.getName().getString();
        Text message = Text.literal(
                StructureRaceConfig.BROADCAST_PREFIX
                        + "§a" + who + " §r发现了 §e" + what + "§r！"
                        + " (+§6" + earned + "§r 分, 累计 §6" + total + "§r 分)");
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
        private boolean won;
    }
}
