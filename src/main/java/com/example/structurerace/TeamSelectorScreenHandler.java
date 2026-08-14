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
 * 队伍选择器 GUI（类似箱子界面的单行容器，客户端用原版 GENERIC_9X1 渲染，无需额外客户端代码）。
 *
 * <p>槽位布局：
 * <ul>
 *   <li>槽 0~7：8 种颜色的羊毛，代表 8 支固定队伍，点击即加入。</li>
 *   <li>槽 8：屏障方块，点击退出队伍成为观众。</li>
 * </ul>
 * 物品仅作展示，点击槽位由服务端 {@link #onSlotClick} 拦截处理，无法拿走。
 */
public final class TeamSelectorScreenHandler extends GenericContainerScreenHandler {

    /** 队伍数量（固定 8 支） */
    public static final int TEAM_COUNT = 8;

    /** 展示用容器（所有玩家共享，只读） */
    private static final Inventory SELECTOR_INVENTORY = createSelectorInventory();

    private TeamSelectorScreenHandler(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
        super(ScreenHandlerType.GENERIC_9X1, syncId, playerInventory, SELECTOR_INVENTORY, 1);
    }

    /** 为玩家打开队伍选择界面 */
    public static void open(ServerPlayerEntity player) {
        NamedScreenHandlerFactory factory = new SimpleNamedScreenHandlerFactory(
                TeamSelectorScreenHandler::new, Text.literal("§6选择队伍 / 退出队伍"));
        player.openHandledScreen(factory);
    }

    private static Inventory createSelectorInventory() {
        SimpleInventory inv = new SimpleInventory(9);
        // 8 种羊毛，颜色与固定队伍一一对应
        ItemStack[] wools = {
                new ItemStack(Items.RED_WOOL),
                new ItemStack(Items.BLUE_WOOL),
                new ItemStack(Items.YELLOW_WOOL),
                new ItemStack(Items.ORANGE_WOOL),
                new ItemStack(Items.GREEN_WOOL),
                new ItemStack(Items.WHITE_WOOL),
                new ItemStack(Items.BLACK_WOOL),
                new ItemStack(Items.PURPLE_WOOL)
        };
        for (int i = 0; i < TEAM_COUNT; i++) {
            ItemStack stack = wools[i];
            String teamId = StructureRaceConfig.DEFAULT_TEAM_IDS.get(i);
            stack.setCustomName(Text.literal(StructureRaceConfig.TEAM_FORMATTING.get(teamId)
                    + StructureRaceConfig.TEAM_NAMES_ZH.getOrDefault(teamId, teamId)
                    + "（点击加入）"));
            inv.setStack(i, stack);
        }
        ItemStack leave = new ItemStack(Items.BARRIER);
        leave.setCustomName(Text.literal("§c退出队伍 / 成为观众"));
        inv.setStack(8, leave);
        return inv;
    }

    @Override
    public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
        // 槽 0~8 是选择区，任何点击都不允许拿走物品
        if (slotIndex >= 0 && slotIndex < 9) {
            if (actionType == SlotActionType.PICKUP && player instanceof ServerPlayerEntity sp) {
                handleSelection(slotIndex, sp);
                sp.closeHandledScreen();
            }
            return; // 展示槽位不可被操作
        }
        super.onSlotClick(slotIndex, button, actionType, player);
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int slot) {
        return ItemStack.EMPTY; // 禁止 Shift 快捷移动
    }

    private static void handleSelection(int slotIndex, ServerPlayerEntity player) {
        if (slotIndex < TEAM_COUNT) {
            String teamId = StructureRaceConfig.DEFAULT_TEAM_IDS.get(slotIndex);
            int result = StructureRaceEvents.joinTeam(player.server, player, teamId);
            if (result == 0) {
                player.sendMessage(Text.literal(StructureRaceConfig.BROADCAST_PREFIX
                        + "你已加入 " + StructureRaceConfig.TEAM_NAMES_ZH.getOrDefault(teamId, teamId)
                        + "。"), false);
            } else if (result == 1) {
                player.sendMessage(Text.literal(StructureRaceConfig.BROADCAST_PREFIX
                        + "§c比赛进行中不能换队，请等待比赛结束或由管理员调整。§r"), false);
            }
        } else {
            int result = StructureRaceEvents.leaveTeam(player.server, player);
            if (result == 0) {
                player.sendMessage(Text.literal(StructureRaceConfig.BROADCAST_PREFIX
                        + "你已离开队伍，成为观众。"), false);
            } else if (result == 1) {
                player.sendMessage(Text.literal(StructureRaceConfig.BROADCAST_PREFIX
                        + "§c比赛进行中不能离队，请等待比赛结束或由管理员调整。§r"), false);
            } else if (result == 2) {
                player.sendMessage(Text.literal(StructureRaceConfig.BROADCAST_PREFIX
                        + "你本来就不在任何队伍中。"), false);
            }
        }
    }
}
