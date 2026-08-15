package com.example.structurerace;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * 语言选择 GUI（类似箱子界面的单行容器，客户端用原版 GENERIC_9X1 渲染，无需额外客户端代码）。
 *
 * <p>槽位布局：
 * <ul>
 *   <li>槽 0：简体中文</li>
 *   <li>槽 1：English</li>
 * </ul>
 * 点击即切换语言并持久化；物品名按玩家当前语言显示，便于不认识对方语言也能辨认。
 */
public final class LanguageSelectorScreenHandler extends GenericContainerScreenHandler {

    private LanguageSelectorScreenHandler(int syncId, PlayerInventory playerInventory,
                                          PlayerEntity player, ServerPlayerEntity owner) {
        super(ScreenHandlerType.GENERIC_9X1, syncId, playerInventory, createInventory(owner), 1);
    }

    /** 为玩家打开语言选择界面 */
    public static void open(ServerPlayerEntity player) {
        NamedScreenHandlerFactory factory = new SimpleNamedScreenHandlerFactory(
                (syncId, inv, p) -> new LanguageSelectorScreenHandler(syncId, inv, p, player),
                Text.literal(Lang.get(player, "§6语言设置 / Language", "§6Language Settings / 语言设置")));
        player.openHandledScreen(factory);
    }

    private static Inventory createInventory(ServerPlayerEntity owner) {
        SimpleInventory inv = new SimpleInventory(2);
        boolean en = Lang.isEn(owner);

        ItemStack zh = new ItemStack(Items.WRITABLE_BOOK);
        zh.setCustomName(Text.literal(
                en ? "§6简体中文\n§7Simplified Chinese" : "§6简体中文（当前）\n§7Simplified Chinese"));
        inv.setStack(0, zh);

        ItemStack english = new ItemStack(Items.WRITTEN_BOOK);
        english.setCustomName(Text.literal(
                en ? "§6English（当前）\n§7英文" : "§6English\n§7英文"));
        inv.setStack(1, english);
        return inv;
    }

    @Override
    public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
        // 槽 0/1 是语言选项，点击切换语言后关闭界面
        if (slotIndex >= 0 && slotIndex < 2) {
            if (actionType == SlotActionType.PICKUP && player instanceof ServerPlayerEntity sp) {
                String lang = slotIndex == 0 ? Lang.ZH : Lang.EN;
                Lang.setLanguage(sp, lang);
                // 移除背包中的旧语言规则书/指南针，下次 ensureLobbyGear 按新语言补发
                for (int i = 0; i < sp.getInventory().size(); i++) {
                    ItemStack s = sp.getInventory().getStack(i);
                    if (s.isOf(Items.WRITTEN_BOOK) && s.hasNbt()
                            && "structure_race".equals(s.getNbt().getString("author"))) {
                        sp.getInventory().setStack(i, ItemStack.EMPTY);
                    } else if (s.isOf(Items.COMPASS) && s.hasNbt()
                            && s.getNbt().getBoolean(StructureRaceConfig.TEAM_SELECTOR_TAG)) {
                        sp.getInventory().setStack(i, ItemStack.EMPTY);
                    }
                }
                sp.currentScreenHandler.sendContentUpdates();
                sp.closeHandledScreen();
                sp.sendMessage(Text.literal(Lang.get(sp,
                        "§a语言已切换为：简体中文。", "§aLanguage switched to: English.")), false);
            }
            return; // 展示槽位不可被操作
        }
        super.onSlotClick(slotIndex, button, actionType, player);
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int slot) {
        return ItemStack.EMPTY; // 禁止 Shift 快捷移动
    }
}
