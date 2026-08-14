package com.example.structurerace;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.util.Identifier;

/**
 * 结构竞速 - 网络协议（C2S）
 *
 * <p>客户端按键（K=规则书 / P=积分映射 / U=进度书）通过自定义通道发送请求，
 * 服务端收到后在主线程打开对应的书本（WrittenBook）。
 *
 * <p>通道为「旧式」Fabric 网络 API（Identifier + PacketByteBuf），
 * 在 1.20.1 / Fabric API 0.92.x 下可用，且 yarn 映射完整。
 */
public final class StructureRaceNetworking {

    /** 请求类型：打开规则书（玩法指南） */
    public static final String TYPE_RULES = "rules";

    /** 请求类型：打开积分映射书（/race point 内容） */
    public static final String TYPE_POINT = "point";

    /** 请求类型：打开进度书（/race progress 内容） */
    public static final String TYPE_PROGRESS = "progress";

    /** C2S 通道 ID：structure_race:open_book */
    public static final Identifier OPEN_BOOK = new Identifier(StructureRaceMod.MOD_ID, "open_book");

    private StructureRaceNetworking() {
    }

    /**
     * 服务端注册接收器，在 {@link StructureRaceMod#onInitialize()} 调用。
     * 收到客户端请求后切到服务端主线程执行（旧式 handler 在网络线程触发，必须回主线程操作玩家）。
     */
    public static void registerServer() {
        ServerPlayNetworking.registerGlobalReceiver(OPEN_BOOK, (server, player, handler, buf, responseSender) -> {
            String type = buf.readString(64);
            server.execute(() -> StructureRaceEvents.handleOpenBookRequest(player, type));
        });
    }
}
