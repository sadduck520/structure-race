package com.example.structurerace;

import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 结构竞速 (Structure Race) - 模组主类
 *
 * <p>实现 {@link ModInitializer}，在游戏（服务端）启动时初始化。
 * 初始化流程：
 * <ol>
 *   <li>注册 {@link StructureRaceEvents} 中的所有事件监听器。</li>
 * </ol>
 *
 * <p>计分板目标（Objective）不在此处创建，而是在事件监听器首次接入玩家时按需创建。
 */
public class StructureRaceMod implements ModInitializer {

    /** 模组 ID，须与 fabric.mod.json 中一致 */
    public static final String MOD_ID = "structure_race";

    /** 日志记录器，用于输出模组运行日志 */
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /**
     * Fabric 加载器调用此方法以初始化模组。
     */
    @Override
    public void onInitialize() {
        LOGGER.info("[StructureRace] 结构竞速模组加载中...");

        // 注册事件监听器（服务端 tick 检测 / 玩家加入 / 玩家离开）
        StructureRaceEvents.register();

        // 注册管理员指令（/race start | stop | reset | mode | status | time | top）
        StructureRaceCommand.register();

        // 注册网络接收器（客户端按键请求打开规则/积分/进度书）
        StructureRaceNetworking.registerServer();

        LOGGER.info("[StructureRace] 结构竞速模组初始化完成！");
    }
}