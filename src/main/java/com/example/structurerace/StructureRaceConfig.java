package com.example.structurerace;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.structure.Structure;

/**
 * 结构竞速 (Structure Race) V2.0 - 配置类
 *
 * <p>集中存放所有可调参数与常量。包括结构/群系的差异化分值映射。
 * 后续可通过 DynamicConfig 实现游戏内热修改。
 */
public final class StructureRaceConfig {

    private StructureRaceConfig() {
        // 工具类，禁止实例化
    }

    // ==================== 获胜条件 ====================

    /** 获胜所需积分上限（积分制） */
    public static final int WIN_SCORE = 100;

    /** 限时赛时长（秒），仅在 WinCondition.TIMER 模式下有效 */
    public static final int MATCH_DURATION_SECONDS = 1800; // 30 分钟

    /**
     * 获胜条件模式。
     * <ul>
     *   <li>{@code SCORE}：积分制，任意玩家累计达到 {@link #WIN_SCORE} 即获胜。</li>
     *   <li>{@code TIMER}：限时制，比赛进行 {@link #MATCH_DURATION_SECONDS} 秒后结束，
     *       总分最高者获胜。</li>
     * </ul>
     */
    public enum WinCondition {
        SCORE,
        TIMER
    }

    /** 默认获胜模式（新存档初始值） */
    public static final WinCondition DEFAULT_WIN_CONDITION = WinCondition.SCORE;

    // ==================== 计分板 ====================

    /** 计分板目标（Objective）的内部名称 */
    public static final String SCOREBOARD_OBJECTIVE_NAME = "race_score";

    /** 计分板显示名称（Sidebar 顶部标题） */
    public static final String SCOREBOARD_DISPLAY_NAME = "§6Structure Race V2§r";

    // ==================== 检测参数 ====================

    /** 防刷分 - 两次加分的冷却时间（秒） */
    public static final int SCORE_COOLDOWN_SECONDS = 5;

    /** 结构检测节流间隔（tick）。每 10 tick 检测一次 */
    public static final int CHECK_INTERVAL_TICKS = 10;

    /** 群系检测独立冷却（tick），防止站在群系边界反复刷分 */
    public static final int BIOME_CHECK_COOLDOWN_TICKS = 40; // 2 秒

    /** 计分板玩家名显示最大字符数 */
    public static final int MAX_SCOREBOARD_NAME_LENGTH = 14;

    /** 广播前缀 */
    public static final String BROADCAST_PREFIX = "§6[竞速] §r";

    /** 队伍可选颜色数量（Tab 列表名字颜色按创建顺序循环分配） */
    public static final int TEAM_COLOR_COUNT = 8;

    // ==================== 默认队伍（起床战争式，8 支固定） ====================

    /** 默认队伍 ID（固定 8 支，玩家通过 /race join <颜色> 加入；不自定义队名） */
    public static final List<String> DEFAULT_TEAM_IDS = List.of(
            "red", "blue", "yellow", "orange", "green", "white", "black", "purple");

    /** 队伍 ID → 中文队名 */
    public static final Map<String, String> TEAM_NAMES_ZH = createTeamZhNames();

    /** 队伍 ID → 颜色（计分板条目与 Tab 玩家名颜色） */
    public static final Map<String, Formatting> TEAM_FORMATTING = createTeamFormattings();

    private static Map<String, String> createTeamZhNames() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("red", "红队");
        m.put("blue", "蓝队");
        m.put("yellow", "黄队");
        m.put("orange", "橙队");
        m.put("green", "绿队");
        m.put("white", "白队");
        m.put("black", "黑队");
        m.put("purple", "紫队");
        return Collections.unmodifiableMap(m);
    }

    private static Map<String, Formatting> createTeamFormattings() {
        Map<String, Formatting> m = new LinkedHashMap<>();
        m.put("red", Formatting.RED);
        m.put("blue", Formatting.BLUE);
        m.put("yellow", Formatting.YELLOW);
        m.put("orange", Formatting.GOLD);
        m.put("green", Formatting.GREEN);
        m.put("white", Formatting.WHITE);
        m.put("black", Formatting.BLACK);
        m.put("purple", Formatting.LIGHT_PURPLE);
        return Collections.unmodifiableMap(m);
    }

    // ==================== 结构/群系中文名映射 ====================

    /** 结构注册名 → 中文名（供 /race team info 显示） */
    public static final java.util.Map<String, String> STRUCTURE_NAMES = createStructureNames();

    private static java.util.Map<String, String> createStructureNames() {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("ancient_city", "远古城市");
        m.put("bastion_remnant", "堡垒遗迹");
        m.put("buried_treasure", "埋藏的宝藏");
        m.put("desert_pyramid", "沙漠神殿");
        m.put("end_city", "末地城");
        m.put("fortress", "下界堡垒");
        m.put("igloo", "雪屋");
        m.put("jungle_pyramid", "丛林神庙");
        m.put("mansion", "林地府邸");
        m.put("mineshaft", "废弃矿井");
        m.put("mineshaft_mesa", "废弃矿井(恶地)");
        m.put("monument", "海底神殿");
        m.put("nether_fossil", "下界化石");
        m.put("ocean_ruin_cold", "海底废墟(寒带)");
        m.put("ocean_ruin_warm", "海底废墟(热带)");
        m.put("pillager_outpost", "掠夺者前哨站");
        m.put("ruined_portal", "破损传送门");
        m.put("ruined_portal_desert", "破损传送门(沙漠)");
        m.put("ruined_portal_jungle", "破损传送门(丛林)");
        m.put("ruined_portal_mountain", "破损传送门(山地)");
        m.put("ruined_portal_nether", "破损传送门(下界)");
        m.put("ruined_portal_ocean", "破损传送门(海洋)");
        m.put("ruined_portal_swamp", "破损传送门(沼泽)");
        m.put("shipwreck", "沉船");
        m.put("shipwreck_beached", "沉船(搁浅)");
        m.put("stronghold", "要塞");
        m.put("swamp_hut", "女巫小屋");
        m.put("trail_ruins", "古迹废墟");
        m.put("village_desert", "村庄(沙漠)");
        m.put("village_plains", "村庄(平原)");
        m.put("village_savanna", "村庄(热带草原)");
        m.put("village_snowy", "村庄(雪原)");
        m.put("village_taiga", "村庄(针叶林)");
        return m;
    }

    /** 群系注册名 → 中文名（key 含 minecraft: 前缀，与查询用的 biomeId 格式一致） */
    public static final Map<String, String> BIOME_NAMES = createBiomeNames();

    private static Map<String, String> createBiomeNames() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("minecraft:desert", "沙漠");
        m.put("minecraft:jungle", "丛林");
        m.put("minecraft:bamboo_jungle", "竹林");
        m.put("minecraft:sparse_jungle", "稀疏丛林");
        m.put("minecraft:badlands", "恶地");
        m.put("minecraft:eroded_badlands", "被风蚀的恶地");
        m.put("minecraft:wooded_badlands", "繁茂恶地");
        m.put("minecraft:mushroom_fields", "蘑菇岛");
        m.put("minecraft:ice_spikes", "冰刺之地");
        m.put("minecraft:mangrove_swamp", "红树林沼泽");
        m.put("minecraft:cherry_grove", "樱花树林");
        m.put("minecraft:dark_forest", "黑森林");
        m.put("minecraft:deep_dark", "深暗之域");
        m.put("minecraft:dripstone_caves", "溶洞");
        m.put("minecraft:lush_caves", "繁茂洞穴");
        m.put("minecraft:frozen_peaks", "冰封山峰");
        m.put("minecraft:jagged_peaks", "尖峭山峰");
        m.put("minecraft:stony_peaks", "裸岩山峰");
        m.put("minecraft:snowy_slopes", "雪坡");
        m.put("minecraft:grove", "雪林");
        m.put("minecraft:meadow", "草甸");
        m.put("minecraft:flower_forest", "繁花森林");
        m.put("minecraft:old_growth_birch_forest", "原始桦木森林");
        m.put("minecraft:old_growth_pine_taiga", "原始松木针叶林");
        m.put("minecraft:old_growth_spruce_taiga", "原始云杉针叶林");
        m.put("minecraft:windswept_forest", "风袭森林");
        m.put("minecraft:windswept_gravelly_hills", "风袭砾质丘陵");
        m.put("minecraft:windswept_hills", "风袭丘陵");
        m.put("minecraft:windswept_savanna", "风袭热带草原");
        m.put("minecraft:snowy_plains", "积雪平原");
        m.put("minecraft:snowy_taiga", "积雪针叶林");
        m.put("minecraft:snowy_beach", "积雪沙滩");
        return m;
    }

    // ==================== 结构分值映射 ====================

    /**
     * 结构注册名 → 分值。
     * 使用 {@link LinkedHashMap} 保持插入顺序（便于 GUI 展示）。
     * 分值按找寻难度估算：常见低分、罕见高分。
     */
    public static final Map<RegistryKey<Structure>, Integer> STRUCTURE_SCORES = createStructureScores();

    private static Map<RegistryKey<Structure>, Integer> createStructureScores() {
        Map<RegistryKey<Structure>, Integer> map = new LinkedHashMap<>();
        // === 主世界 - 地表常见 ===
        map.put(structKey("village_plains"), 5);
        map.put(structKey("village_desert"), 5);
        map.put(structKey("village_savanna"), 5);
        map.put(structKey("village_snowy"), 5);
        map.put(structKey("village_taiga"), 5);
        map.put(structKey("desert_pyramid"), 8);
        map.put(structKey("igloo"), 12);
        map.put(structKey("swamp_hut"), 12);
        map.put(structKey("pillager_outpost"), 8);
        map.put(structKey("jungle_pyramid"), 12);

        // === 主世界 - 破损传送门（所有变体） ===
        map.put(structKey("ruined_portal"), 3);
        map.put(structKey("ruined_portal_desert"), 3);
        map.put(structKey("ruined_portal_jungle"), 3);
        map.put(structKey("ruined_portal_mountain"), 3);
        map.put(structKey("ruined_portal_ocean"), 3);
        map.put(structKey("ruined_portal_swamp"), 3);

        // === 主世界 - 地下 ===
        map.put(structKey("mineshaft"), 10);
        map.put(structKey("mineshaft_mesa"), 6);
        map.put(structKey("stronghold"), 30);
        map.put(structKey("ancient_city"), 25);
        map.put(structKey("trail_ruins"), 12);

        // === 主世界 - 水下 ===
        map.put(structKey("shipwreck"), 3);
        map.put(structKey("shipwreck_beached"), 4);
        map.put(structKey("ocean_ruin_cold"), 3);
        map.put(structKey("ocean_ruin_warm"), 3);
        map.put(structKey("buried_treasure"), 6);
        map.put(structKey("monument"), 10);         // 海底神殿

        // === 主世界 - 罕见 ===
        map.put(structKey("mansion"), 50);           // 林地府邸

        // === 下界 ===
        map.put(structKey("fortress"), 15);          // 下界堡垒
        map.put(structKey("bastion_remnant"), 18);
        map.put(structKey("nether_fossil"), 1);
        map.put(structKey("ruined_portal_nether"), 3);

        // === 末地 ===
        map.put(structKey("end_city"), 40);

        return Collections.unmodifiableMap(map);
    }

    private static RegistryKey<Structure> structKey(String id) {
        return RegistryKey.of(RegistryKeys.STRUCTURE, new Identifier(id));
    }

    // ==================== 群系分值映射 ====================

    /**
     * 群系注册名 → 分值。
     * 当玩家首次踏入新群系时给予一次性加分。
     * 分值按群系稀有度/难度估算。
     */
    public static final Map<RegistryKey<Biome>, Integer> BIOME_SCORES = createBiomeScores();

    private static Map<RegistryKey<Biome>, Integer> createBiomeScores() {
        Map<RegistryKey<Biome>, Integer> map = new LinkedHashMap<>();
        map.put(biomeKey("desert"), 5);
        map.put(biomeKey("jungle"), 4);
        map.put(biomeKey("bamboo_jungle"), 5);
        map.put(biomeKey("sparse_jungle"), 3);
        map.put(biomeKey("badlands"), 5);
        map.put(biomeKey("eroded_badlands"), 5);
        map.put(biomeKey("wooded_badlands"), 5);
        map.put(biomeKey("mushroom_fields"), 20);
        map.put(biomeKey("ice_spikes"), 8);
        map.put(biomeKey("mangrove_swamp"), 5);
        map.put(biomeKey("cherry_grove"), 4);
        map.put(biomeKey("dark_forest"), 3);
        map.put(biomeKey("deep_dark"), 15);
        map.put(biomeKey("dripstone_caves"), 5);
        map.put(biomeKey("lush_caves"), 4);
        map.put(biomeKey("frozen_peaks"), 6);
        map.put(biomeKey("jagged_peaks"), 6);
        map.put(biomeKey("stony_peaks"), 5);
        map.put(biomeKey("snowy_slopes"), 4);
        map.put(biomeKey("grove"), 4);
        map.put(biomeKey("meadow"), 3);
        map.put(biomeKey("flower_forest"), 4);
        map.put(biomeKey("old_growth_birch_forest"), 3);
        map.put(biomeKey("old_growth_pine_taiga"), 3);
        map.put(biomeKey("old_growth_spruce_taiga"), 3);
        map.put(biomeKey("windswept_forest"), 4);
        map.put(biomeKey("windswept_gravelly_hills"), 4);
        map.put(biomeKey("windswept_hills"), 4);
        map.put(biomeKey("windswept_savanna"), 4);
        map.put(biomeKey("snowy_plains"), 4);
        map.put(biomeKey("snowy_taiga"), 4);
        map.put(biomeKey("snowy_beach"), 2);
        return Collections.unmodifiableMap(map);
    }

    private static RegistryKey<Biome> biomeKey(String id) {
        return RegistryKey.of(RegistryKeys.BIOME, new Identifier(id));
    }

    // ==================== 待检测结构列表（从 scoreMap 的 key 导出） ====================

    /** 所有目标结构（遍历检测用），每次从 STRUCTURE_SCORES.keySet() 生成 */
    public static Iterable<RegistryKey<Structure>> getTargetStructures() {
        return STRUCTURE_SCORES.keySet();
    }

    // ==================== 队伍选择器 / 规则书 ====================

    /** 队伍选择器（指南针）的 NBT 标记键 */
    public static final String TEAM_SELECTOR_TAG = "structure_race:team_selector";

    /** 迷路指引：最近结构距离小于该值（格）时不做提示 */
    public static final int HINT_MIN_DISTANCE = 100;

    /** 迷路指引：无符合条件的结构时的重试间隔（tick，1 分钟） */
    public static final int HINT_RETRY_TICKS = 1200;

    /** 迷路指引：检索半径（格） */
    public static final int HINT_SEARCH_RADIUS = 2048;

    /** 规则书页面内容（玩法/规则/指令/积分关系）。
     * 书页为浅色底，统一使用深色文字；每页不超过 13 行、每行不超过 14 个全角字符，
     * 避免书页排版截断导致显示不全。 */
    public static java.util.List<String> getRuleBookPages() {
        java.util.List<String> pages = new java.util.ArrayList<>();
        pages.add("§l§n结构竞速·玩法指南§r\n"
                + "§l欢迎来到结构竞速！§r\n"
                + "多人团队探索竞速：\n"
                + "发现结构与稀有群系，\n"
                + "为你的队伍赚取积分。\n"
                + "§l【第一步·组队】§r\n"
                + "手持指南针（队伍选择\n"
                + "器）右键打开选队界面\n"
                + "点击颜色羊毛加入队伍\n"
                + "点击屏障退出成观众\n"
                + "指令§1 /race join <颜色>§r\n"
                + "red红 blue蓝 yellow黄\n"
                + "orange橙 green绿 white");
        pages.add("§l§n游戏目标与流程§r\n"
                + "§l【目标】§r\n"
                + "探索主世界/下界/末地，\n"
                + "发现结构与稀有群系，\n"
                + "总分最高的队伍获胜。\n"
                + "§l【流程】§r\n"
                + "1.在大厅完成组队\n"
                + "2.管理员执行\n"
                + "§1/race start§r\n"
                + "3.全员传送至主世界\n"
                + "出生点开始竞速\n"
                + "4.结束宣布胜负\n"
                + "并回到大厅");
        pages.add("§l§n结构计分规则§r\n"
                + "§l【发现即加分】§r\n"
                + "首次发现竞速结构，\n"
                + "为所在队伍加分。\n"
                + "§l【同队不重复】§r\n"
                + "结构得分全队共享：\n"
                + "同队任何人再次进入\n"
                + "已发现的结构，\n"
                + "不重复加分。\n"
                + "§l【占有制】§r\n"
                + "结构被任意队伍发现\n"
                + "后即被该队占有，\n"
                + "其他队找到同一实例\n"
                + "不得分。先到先得！");
        pages.add("§l§n群系规则与其他§r\n"
                + "§l【群系规则】§r\n"
                + "首次踏入目标群系\n"
                + "为队伍加分；群系\n"
                + "不占有，每队可各自\n"
                + "获得；已探索的\n"
                + "群系不再加分。\n"
                + "§l【里程】§r\n"
                + "步行/飞行每500格+1分\n"
                + "坐船行驶不计里程\n"
                + "§l【击杀】§r\n"
                + "每击杀10只敌对怪物\n"
                + "+1分（含远程击杀）");
        pages.add("§l§n维度与群系分值§r\n"
                + "§l【维度探索】§r\n"
                + "首次下界+10 末地+20\n"
                + "（每队各一次）\n"
                + "§l【林地府邸】§r\n"
                + "携带探险家地图+50\n"
                + "无地图+30\n"
                + "§l【群系分值】§r\n"
                + "蘑菇岛20 深暗之域15\n"
                + "冰刺之地8 溶洞6\n"
                + "冰封山峰6 尖峭山峰6\n"
                + "雪坡4 雪林4 草甸3\n"
                + "繁花森林4 竹林5\n"
                + "恶地5 沙漠5 丛林4");
        pages.add("§l§n群系分值(续)§r\n"
                + "稀疏丛林3 黑森林3\n"
                + "红树林5 樱花4 风袭4\n"
                + "积雪平原4 积雪针叶林4\n"
                + "原始白桦3 蘑菇海域…\n"
                + "其余完整分值见\n"
                + "§1/race point§r\n"
                + "§l§n常用指令§r\n"
                + "§1/race join <颜色>§r\n"
                + "加入/切换队伍\n"
                + "§1/race leave§r 离队\n"
                + "§1/race time§r 剩余\n"
                + "§1/race top§r 排名");
        pages.add("§l§n常用指令与快捷键§r\n"
                + "§1/race status§r 状态\n"
                + "§1/race recall§r 召回\n"
                + "（耗10分，冷却5分钟）\n"
                + "§1/race point§r 积分映射\n"
                + "§1/race progress§r 进度\n"
                + "§l【快捷键】§r\n"
                + "§1K§r规则 §1P§r积分\n"
                + "§1U§r进度（可在按键\n"
                + "设置中修改）\n"
                + "§l【队伍锁定】§r\n"
                + "比赛进行中不能换队\n"
                + "无队伍玩家为观众");
        pages.add("§l§n特殊机制§r\n"
                + "§l【落后补偿】§r\n"
                + "落后第一名20分以上，\n"
                + "全员获得速度提升I\n"
                + "§l【迷路指引】§r\n"
                + "5分钟无得分自动查询\n"
                + "最近结构，提示坐标\n"
                + "与距离（仅本人可见）\n"
                + "100格内不提示，\n"
                + "1分钟后重查\n"
                + "§l【防作弊】§r\n"
                + "单次位移超50格视为\n"
                + "传送，不计入里程");
        pages.add("§l§n获胜与结算§r\n"
                + "§l【积分制】§r\n"
                + "率先达到目标分数\n"
                + "（默认100）获胜\n"
                + "§l【限时制】§r\n"
                + "时间到得分最高获胜\n"
                + "§1/race time§r 查剩余\n"
                + "§l【平局】§r\n"
                + "多队同分并列判平局\n"
                + "§l【结算】§r\n"
                + "公布各队排名，胜利\n"
                + "队显示恭喜获胜；\n"
                + "10秒后回大厅放烟花");
        pages.add("§l§n结构分值一览§r\n"
                + "远古城市25 堡垒遗迹18\n"
                + "林地府邸30/50 末地城40\n"
                + "下界堡垒15 要塞30\n"
                + "沙漠神殿8 雪屋12\n"
                + "女巫小屋12 前哨站8\n"
                + "丛林神庙12 古迹废墟12\n"
                + "废弃矿井10 矿井恶地6\n"
                + "海底神殿10 埋藏宝藏6\n"
                + "沉船3/4 海底废墟3\n"
                + "破损传送门3 村庄5\n"
                + "下界化石1");
        return pages;
    }
}
