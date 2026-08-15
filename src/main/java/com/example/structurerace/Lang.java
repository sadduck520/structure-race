package com.example.structurerace;

import net.minecraft.server.network.ServerPlayerEntity;

/**
 * 结构竞速 - 语言工具类（简体中文 / English）
 *
 * <p>玩家语言选择持久化在 {@link StructureRaceState.PlayerPersistentData#language} 中，
 * 通过大厅语言选择 GUI、指令 {@code /language} 或快捷键 <b>L</b> 切换。
 *
 * <p>所有用户可见文本通过 {@link #get(ServerPlayerEntity, String, String)} 等按玩家语言返回。
 */
public final class Lang {

    /** 简体中文 */
    public static final String ZH = "zh_cn";
    /** 英文 */
    public static final String EN = "en_us";

    private Lang() {
    }

    /** 读取玩家语言；null/异常一律回退中文 */
    public static String langOf(ServerPlayerEntity player) {
        if (player == null || player.getServer() == null) return ZH;
        try {
            StructureRaceState state = StructureRaceState.get(player.getServer().getOverworld());
            String l = state.getExistingPlayerData(player.getUuid()) != null
                    ? state.getExistingPlayerData(player.getUuid()).language : null;
            return EN.equals(l) ? EN : ZH;
        } catch (Exception e) {
            return ZH;
        }
    }

    /** 是否英文 */
    public static boolean isEn(ServerPlayerEntity player) {
        return EN.equals(langOf(player));
    }

    /** 是否英文（按语言代码） */
    public static boolean isEnLang(String lang) {
        return EN.equals(lang);
    }

    /** 设置玩家语言并持久化；非法值忽略 */
    public static void setLanguage(ServerPlayerEntity player, String lang) {
        if (player == null || player.getServer() == null) return;
        if (!ZH.equals(lang) && !EN.equals(lang)) return;
        StructureRaceState state = StructureRaceState.get(player.getServer().getOverworld());
        state.getPlayerData(player.getUuid()).language = lang;
        state.markDirty();
    }

    /** 按玩家语言返回双语文本 */
    public static String get(ServerPlayerEntity player, String zh, String en) {
        return EN.equals(langOf(player)) ? en : zh;
    }

    /** 按语言代码返回双语文本 */
    public static String get(String lang, String zh, String en) {
        return EN.equals(lang) ? en : zh;
    }

    /** 按玩家语言返回格式化文本（String.format，占位符 %s / %d） */
    public static String fmt(ServerPlayerEntity player, String zh, String en, Object... args) {
        String t = EN.equals(langOf(player)) ? en : zh;
        try {
            return args.length == 0 ? t : String.format(t, args);
        } catch (Exception e) {
            return t;
        }
    }

    // ==================== 名称翻译 ====================

    /** 队伍名（带颜色）：中文「红队」/ 英文「Red」 */
    public static String teamName(ServerPlayerEntity player, String teamId) {
        String zh = StructureRaceConfig.TEAM_NAMES_ZH.getOrDefault(teamId, teamId);
        String en = StructureRaceConfig.TEAM_NAMES_EN.getOrDefault(teamId, teamId);
        return isEn(player) ? en : zh;
    }

    /** 结构名：中文「远古城市」/ 英文「Ancient City」 */
    public static String structName(ServerPlayerEntity player, String registryPath) {
        String zh = StructureRaceConfig.STRUCTURE_NAMES.getOrDefault(registryPath, registryPath);
        String en = StructureRaceConfig.STRUCTURE_NAMES_EN.getOrDefault(registryPath, registryPath);
        return isEn(player) ? en : zh;
    }

    /** 群系名：中文「沙漠」/ 英文「Desert」（id 含 minecraft: 前缀） */
    public static String biomeName(ServerPlayerEntity player, String biomeId) {
        String zh = StructureRaceConfig.BIOME_NAMES.getOrDefault(biomeId, biomeId);
        String en = StructureRaceConfig.BIOME_NAMES_EN.getOrDefault(biomeId, biomeId);
        return isEn(player) ? en : zh;
    }
}
