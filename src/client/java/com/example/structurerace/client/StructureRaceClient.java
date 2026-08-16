package com.example.structurerace.client;

import com.example.structurerace.StructureRaceNetworking;

import org.lwjgl.glfw.GLFW;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;

import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.network.PacketByteBuf;

/**
 * 结构竞速 - 客户端初始化（双端安装时生效）
 *
 * <p>注册四个快捷键（可在「选项 - 控制 - 按键绑定 - 结构竞速」中修改）：
 * <ul>
 *   <li><b>K</b>：打开规则书（玩法指南）</li>
 *   <li><b>P</b>：打开积分映射书（结构/群系分值 + 其他积分规则）</li>
 *   <li><b>U</b>：打开竞速进度书（本队已发现结构/群系、未找到的可加分群系）</li>
 *   <li><b>Y</b>：打开语言选择界面（简体中文 / English）</li>
 * </ul>
 *
 * <p>按键后向服务端发送 C2S 请求，由服务端生成书本并打开（避免客户端伪造进度数据）。
 */
public class StructureRaceClient implements ClientModInitializer {

    private static final String CATEGORY = "key.categories.structure_race";

    private static KeyBinding rulesKey;
    private static KeyBinding pointKey;
    private static KeyBinding progressKey;
    private static KeyBinding languageKey;

    @Override
    public void onInitializeClient() {
        rulesKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.structure_race.rules", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, CATEGORY));
        pointKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.structure_race.point", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_P, CATEGORY));
        progressKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.structure_race.progress", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_U, CATEGORY));
        languageKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.structure_race.language", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_Y, CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // 不在游戏内（或正在打开其他界面）时不发送
            if (client.player == null || client.getNetworkHandler() == null) return;
            if (client.currentScreen != null) return;

            while (rulesKey.wasPressed()) {
                sendOpenBookRequest(StructureRaceNetworking.TYPE_RULES);
            }
            while (pointKey.wasPressed()) {
                sendOpenBookRequest(StructureRaceNetworking.TYPE_POINT);
            }
            while (progressKey.wasPressed()) {
                sendOpenBookRequest(StructureRaceNetworking.TYPE_PROGRESS);
            }
            while (languageKey.wasPressed()) {
                sendOpenBookRequest(StructureRaceNetworking.TYPE_LANGUAGE);
            }
        });
    }

    /** 向服务端发送「打开书本」请求 */
    private static void sendOpenBookRequest(String type) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(type);
        ClientPlayNetworking.send(StructureRaceNetworking.OPEN_BOOK, buf);
    }
}
