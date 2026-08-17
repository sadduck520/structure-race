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
 * 管理员比赛设置 GUI（原版箱子界面 GENERIC_9X1，标题黑色）。
 * 通过 {@code /race settings}（OP）或大厅中的「设置修改器（红石粉）」（OP）打开，无需记忆指令即可调整：
 * <ul>
 *   <li>槽 0：切换获胜模式（积分制 / 限时制）</li>
 *   <li>槽 1/2：获胜分数 +25 / -25</li>
 *   <li>槽 3/4：限时时长 +10 分钟 / -10 分钟</li>
 *   <li>槽 5：当前配置说明（只读）</li>
 *   <li>槽 6：关闭</li>
 * </ul>
 */
public final class SettingsScreenHandler extends GenericContainerScreenHandler {

    private final ServerPlayerEntity owner;

    private SettingsScreenHandler(int syncId, PlayerInventory playerInventory,
                                  PlayerEntity player, ServerPlayerEntity owner) {
        super(ScreenHandlerType.GENERIC_9X1, syncId, playerInventory, createSettingsInventory(owner), 1);
        this.owner = owner;
    }

    /** 为管理员打开比赛设置界面 */
    public static void open(ServerPlayerEntity player) {
        NamedScreenHandlerFactory factory = new SimpleNamedScreenHandlerFactory(
                (syncId, inv, p) -> new SettingsScreenHandler(syncId, inv, p, player),
                Text.literal(Lang.get(player, "§0比赛设置 / Match Settings", "§0Match Settings / 比赛设置")));
        player.openHandledScreen(factory);
    }

    private static Inventory createSettingsInventory(ServerPlayerEntity owner) {
        SimpleInventory inv = new SimpleInventory(9);
        boolean en = Lang.isEn(owner);
        StructureRaceState state = StructureRaceState.get(owner.getServer().getOverworld());
        boolean timer = "timer".equals(state.winCondition);
        int winScore = state.winScore;
        long durationMin = state.matchDurationTicks / 20 / 60;

        ItemStack mode = new ItemStack(timer ? Items.CLOCK : Items.WRITTEN_BOOK);
        mode.setCustomName(Text.literal(en
                ? (timer ? "§6Timer Mode\n§7Click: switch to Score mode"
                        : "§6Score Mode\n§7Click: switch to Timer mode")
                : (timer ? "§6限时制\n§7点击切换为积分制"
                        : "§6积分制\n§7点击切换为限时制")));
        inv.setStack(0, mode);

        ItemStack plusScore = new ItemStack(Items.GREEN_DYE);
        plusScore.setCustomName(Text.literal(en ? "§aWin Score +25" : "§a获胜分数 +25"));
        inv.setStack(1, plusScore);

        ItemStack minusScore = new ItemStack(Items.RED_DYE);
        minusScore.setCustomName(Text.literal(en ? "§cWin Score -25" : "§c获胜分数 -25"));
        inv.setStack(2, minusScore);

        ItemStack plusTime = new ItemStack(Items.LIME_DYE);
        plusTime.setCustomName(Text.literal(en ? "§aTimer +10 min" : "§a限时时长 +10 分钟"));
        inv.setStack(3, plusTime);

        ItemStack minusTime = new ItemStack(Items.ORANGE_DYE);
        minusTime.setCustomName(Text.literal(en ? "§cTimer -10 min" : "§c限时时长 -10 分钟"));
        inv.setStack(4, minusTime);

        ItemStack info = new ItemStack(Items.PAPER);
        info.setCustomName(Text.literal(en
                ? "§eMode: " + (timer ? "Timer" : "Score")
                        + "\n§eWin score: " + winScore
                        + "\n§eTimer: " + durationMin + " min"
                : "§e模式：" + (timer ? "限时制" : "积分制")
                        + "\n§e获胜分数：" + winScore
                        + "\n§e限时时长：" + durationMin + " 分钟"));
        inv.setStack(5, info);

        ItemStack close = new ItemStack(Items.BARRIER);
        close.setCustomName(Text.literal(en ? "§cClose" : "§c关闭"));
        inv.setStack(6, close);
        return inv;
    }

    @Override
    public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
        if (slotIndex >= 0 && slotIndex <= 6 && actionType == SlotActionType.PICKUP
                && player instanceof ServerPlayerEntity sp) {
            if (slotIndex == 6) { // 关闭
                sp.closeHandledScreen();
                return;
            }
            if (slotIndex == 5) { // 只读说明
                return;
            }
            StructureRaceState state = StructureRaceState.get(sp.getServer().getOverworld());
            boolean timer = "timer".equals(state.winCondition);
            if (slotIndex == 0) {
                StructureRaceEvents.setWinCondition(sp.getServer(), timer ? "score" : "timer");
            } else if (slotIndex == 1) {
                StructureRaceEvents.setWinScore(sp.getServer(), state.winScore + 25);
            } else if (slotIndex == 2) {
                StructureRaceEvents.setWinScore(sp.getServer(), state.winScore - 25);
            } else if (slotIndex == 3) {
                StructureRaceEvents.setMatchDuration(sp.getServer(),
                        (int) (state.matchDurationTicks / 20) + 600);
            } else if (slotIndex == 4) {
                int sec = Math.max(60, (int) (state.matchDurationTicks / 20) - 600);
                StructureRaceEvents.setMatchDuration(sp.getServer(), sec);
            }
            // 刷新界面以显示最新配置
            sp.closeHandledScreen();
            open(sp);
            return;
        }
        super.onSlotClick(slotIndex, button, actionType, player);
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int slot) {
        return ItemStack.EMPTY; // 禁止 Shift 快捷移动
    }
}
