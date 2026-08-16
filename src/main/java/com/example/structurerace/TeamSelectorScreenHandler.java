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
 * 队伍选择器 GUI（原版箱子界面 GENERIC_9X1，标题文字为黑色）。
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

    private TeamSelectorScreenHandler(int syncId, PlayerInventory playerInventory,
                                      PlayerEntity player, ServerPlayerEntity owner) {
        super(ScreenHandlerType.GENERIC_9X1, syncId, playerInventory, createSelectorInventory(owner), 1);
    }

    /** 为玩家打开队伍选择界面（物品名按玩家语言显示，标题为黑色） */
    public static void open(ServerPlayerEntity player) {
        NamedScreenHandlerFactory factory = new SimpleNamedScreenHandlerFactory(
                (syncId, inv, p) -> new TeamSelectorScreenHandler(syncId, inv, p, player),
                Text.literal(Lang.get(player, "§0选择队伍 / 退出队伍", "§0Select Team / Leave Team")));
        player.openHandledScreen(factory);
    }

    private static Inventory createSelectorInventory(ServerPlayerEntity owner) {
        SimpleInventory inv = new SimpleInventory(9);
        boolean en = Lang.isEn(owner);
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
            String name = StructureRaceConfig.TEAM_FORMATTING.get(teamId)
                    + (en ? StructureRaceConfig.TEAM_NAMES_EN.getOrDefault(teamId, teamId)
                          : StructureRaceConfig.TEAM_NAMES_ZH.getOrDefault(teamId, teamId))
                    + (en ? " (click to join)" : "（点击加入）");
            stack.setCustomName(Text.literal(name));
            inv.setStack(i, stack);
        }
        ItemStack leave = new ItemStack(Items.BARRIER);
        leave.setCustomName(Text.literal(en ? "§cLeave team / Spectator" : "§c退出队伍 / 成为观众"));
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
                player.sendMessage(Text.literal(StructureRaceConfig.BROADCAST_PREFIX + Lang.get(player,
                        "你已加入 " + StructureRaceConfig.TEAM_NAMES_ZH.getOrDefault(teamId, teamId) + "。",
                        "You joined "
                                + StructureRaceConfig.TEAM_NAMES_EN.getOrDefault(teamId, teamId) + ".")), false);
            } else if (result == 1) {
                player.sendMessage(Text.literal(StructureRaceConfig.BROADCAST_PREFIX + Lang.get(player,
                        "§c比赛进行中不能换队，请等待比赛结束或由管理员调整。§r",
                        "§cYou cannot switch teams during a match. Wait for it to end.§r")), false);
            }
        } else {
            int result = StructureRaceEvents.leaveTeam(player.server, player);
            if (result == 0) {
                player.sendMessage(Text.literal(StructureRaceConfig.BROADCAST_PREFIX + Lang.get(player,
                        "你已离开队伍，成为观众。", "You left your team and became a spectator.")), false);
            } else if (result == 1) {
                player.sendMessage(Text.literal(StructureRaceConfig.BROADCAST_PREFIX + Lang.get(player,
                        "§c比赛进行中不能离队，请等待比赛结束或由管理员调整。§r",
                        "§cYou cannot leave your team during a match. Wait for it to end.§r")), false);
            } else if (result == 2) {
                player.sendMessage(Text.literal(StructureRaceConfig.BROADCAST_PREFIX + Lang.get(player,
                        "你本来就不在任何队伍中。", "You are not on any team.")), false);
            }
        }
    }
}
