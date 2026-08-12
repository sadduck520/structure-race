package com.example.structurerace;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.PersistentState;

/**
 * 结构竞速 (Structure Race) V2.2 - 世界存档持久化状态
 *
 * <p>存储：全局比赛状态、每位玩家的竞速进度、所有队伍数据。
 * 数据随世界存档保存：同存档重进可续玩、倒计时可延续，不同存档完全隔离。
 */
public final class StructureRaceState extends PersistentState {

    private static final Logger LOGGER = LoggerFactory.getLogger("StructureRace");

    public static final String STATE_KEY = "structure_race_state";

    // ==================== 全局比赛状态 ====================

    /** 比赛是否进行中（false 时暂停计分；默认 false，需 /race start 开启） */
    public boolean matchActive = false;

    /** 比赛开始时的主世界时间（tick），用于限时制倒计时 */
    public long matchStartTick;

    /** 获胜模式："score"（积分制）或 "timer"（限时制） */
    public String winCondition = "score";

    /** 积分制获胜分数上限 */
    public int winScore = StructureRaceConfig.WIN_SCORE;

    /** 限时制总时长（tick） */
    public long matchDurationTicks = StructureRaceConfig.MATCH_DURATION_SECONDS * 20L;

    // ==================== 玩家数据 ====================

    private final Map<UUID, PlayerPersistentData> playerData = new HashMap<>();

    public static final class PlayerPersistentData {
        /** 已发现结构的唯一标识集合（格式："registryKey:chunkPosLong"） */
        public final Set<String> discoveredStructures = new HashSet<>();

        /** 已发现群系的注册名集合（格式："minecraft:desert"） */
        public final Set<String> discoveredBiomes = new HashSet<>();

        /** 累计积分 */
        public int totalScore;

        /** 是否已获胜 */
        public boolean won;
    }

    // ==================== 全局去重（跨队伍独占） ====================

    /** 所有队伍已发现的结构集合（任意队伍发现后，其他队伍不再得分） */
    public final Set<String> globallyDiscoveredStructures = new java.util.HashSet<>();

    // ==================== 队伍数据 ====================

    private final Map<String, TeamData> teams = new LinkedHashMap<>();

    public static final class TeamData {
        /** 队伍 ID（名称） */
        public String teamId;

        /** 计分板队伍颜色索引（对应 Tab 列表名字颜色） */
        public int colorIndex;

        /** 队员 UUID 集合 */
        public final Set<UUID> members = new HashSet<>();

        /** 队伍级已发现结构集合（全队去重） */
        public final Set<String> discoveredStructures = new HashSet<>();

        /** 队伍级已发现群系集合（全队去重） */
        public final Set<String> discoveredBiomes = new HashSet<>();

        /** 队伍总分 */
        public int totalScore;
    }

    // ==================== 工厂 ====================

    public static StructureRaceState get(ServerWorld world) {
        return world.getPersistentStateManager().getOrCreate(
                StructureRaceState::fromNbt, StructureRaceState::new, STATE_KEY);
    }

    // ==================== 玩家查询 ====================

    public PlayerPersistentData getPlayerData(UUID uuid) {
        return playerData.computeIfAbsent(uuid, k -> new PlayerPersistentData());
    }

    public PlayerPersistentData getExistingPlayerData(UUID uuid) {
        return playerData.get(uuid);
    }

    public Map<UUID, PlayerPersistentData> getAllPlayerData() {
        return playerData;
    }

    public void resetAllPlayers() {
        playerData.clear();
    }

    // ==================== 队伍操作 ====================

    /** 创建新队伍（颜色自动按创建顺序循环分配） */
    public TeamData createTeam(String teamId) {
        TeamData t = new TeamData();
        t.teamId = teamId;
        t.colorIndex = teams.size() % StructureRaceConfig.TEAM_COLOR_COUNT;
        teams.put(teamId, t);
        markDirty();
        return t;
    }

    public TeamData getTeam(String teamId) {
        return teams.get(teamId);
    }

    public void removeTeam(String teamId) {
        teams.remove(teamId);
        markDirty();
    }

    public Map<String, TeamData> getAllTeams() {
        return teams;
    }

    /** 查找某玩家所属的队伍；无队伍返回 null */
    public TeamData getTeamByMember(UUID uuid) {
        for (TeamData t : teams.values()) {
            if (t.members.contains(uuid)) return t;
        }
        return null;
    }

    public void clearAllTeams() {
        teams.clear();
        markDirty();
    }

    // ==================== 序列化 ====================

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        nbt.putBoolean("matchActive", matchActive);
        nbt.putLong("matchStartTick", matchStartTick);
        nbt.putString("winCondition", winCondition);
        nbt.putInt("winScore", winScore);
        nbt.putLong("matchDurationTicks", matchDurationTicks);

        NbtList players = new NbtList();
        for (Map.Entry<UUID, PlayerPersistentData> entry : playerData.entrySet()) {
            NbtCompound p = new NbtCompound();
            p.putUuid("uuid", entry.getKey());
            p.putInt("totalScore", entry.getValue().totalScore);
            p.putBoolean("won", entry.getValue().won);
            p.put("structures", stringSetToNbt(entry.getValue().discoveredStructures));
            p.put("biomes", stringSetToNbt(entry.getValue().discoveredBiomes));
            players.add(p);
        }
        nbt.put("players", players);

        NbtList teamList = new NbtList();
        for (TeamData t : teams.values()) {
            NbtCompound tn = new NbtCompound();
            tn.putString("id", t.teamId);
            tn.putInt("color", t.colorIndex);
            tn.putInt("totalScore", t.totalScore);

            NbtList members = new NbtList();
            for (UUID u : t.members) {
                NbtCompound mu = new NbtCompound();
                mu.putUuid("uuid", u);
                members.add(mu);
            }
            tn.put("members", members);
            tn.put("structures", stringSetToNbt(t.discoveredStructures));
            tn.put("biomes", stringSetToNbt(t.discoveredBiomes));
            teamList.add(tn);
        }
        nbt.put("teams", teamList);

        nbt.put("global_structures", stringSetToNbt(globallyDiscoveredStructures));

        return nbt;
    }

    private static NbtList stringSetToNbt(Set<String> set) {
        NbtList list = new NbtList();
        for (String s : set) {
            list.add(NbtString.of(s));
        }
        return list;
    }

    public static StructureRaceState fromNbt(NbtCompound nbt) {
        StructureRaceState state = new StructureRaceState();
        state.matchActive = nbt.getBoolean("matchActive");
        state.matchStartTick = nbt.getLong("matchStartTick");
        state.winCondition = nbt.getString("winCondition");
        state.winScore = nbt.getInt("winScore");
        state.matchDurationTicks = nbt.getLong("matchDurationTicks");

        NbtElement pe = nbt.get("players");
        if (pe instanceof NbtList players) {
            for (int i = 0; i < players.size(); i++) {
                NbtElement el = players.get(i);
                if (!(el instanceof NbtCompound p)) continue;
                UUID uuid = p.getUuid("uuid");
                if (uuid == null) continue;
                PlayerPersistentData d = new PlayerPersistentData();
                d.totalScore = p.getInt("totalScore");
                d.won = p.getBoolean("won");
                d.discoveredStructures.addAll(readStringSet(p, "structures"));
                d.discoveredBiomes.addAll(readStringSet(p, "biomes"));
                state.playerData.put(uuid, d);
            }
        }

        NbtElement te = nbt.get("teams");
        if (te instanceof NbtList teamList) {
            for (int i = 0; i < teamList.size(); i++) {
                NbtElement el = teamList.get(i);
                if (!(el instanceof NbtCompound tn)) continue;
                TeamData t = new TeamData();
                t.teamId = tn.getString("id");
                t.colorIndex = tn.getInt("color");
                t.totalScore = tn.getInt("totalScore");
                NbtElement me = tn.get("members");
                if (me instanceof NbtList members) {
                    for (int j = 0; j < members.size(); j++) {
                        NbtElement m = members.get(j);
                        if (m instanceof NbtCompound mc) {
                            UUID u = mc.getUuid("uuid");
                            if (u != null) t.members.add(u);
                        }
                    }
                }
                t.discoveredStructures.addAll(readStringSet(tn, "structures"));
                t.discoveredBiomes.addAll(readStringSet(tn, "biomes"));
                state.teams.put(t.teamId, t);
            }
        }

        LOGGER.info("[StructureRace] 存档加载：模式={}, 进行中={}, 玩家{}个, 队伍{}个",
                state.winCondition, state.matchActive, state.playerData.size(), state.teams.size());

        state.globallyDiscoveredStructures.addAll(readStringSet(nbt, "global_structures"));

        return state;
    }

    private static Set<String> readStringSet(NbtCompound parent, String key) {
        Set<String> set = new HashSet<>();
        NbtElement ve = parent.get(key);
        if (ve instanceof NbtList list) {
            for (int j = 0; j < list.size(); j++) {
                set.add(list.getString(j));
            }
        }
        return set;
    }
}
