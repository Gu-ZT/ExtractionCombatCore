package dev.extraction.core;

import com.mojang.authlib.GameProfile;
import dev.extraction.core.server.LootSessions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;
import java.util.List;

@GameTestHolder("extractioncombatcore")
@PrefixGameTestTemplate(false)
public final class InventoryGameTests {
    public static void setInventoryPolicy(boolean main, int hotbar) {
        ExtractionCore.MAIN.set(main);
        ExtractionCore.HOTBAR.set(hotbar);
    }

    @GameTest(template = "test_room")
    public static void disabledSlotsAndEquipment(GameTestHelper test) {
        boolean main = GameAccess.mainEnabled();
        int hotbar = GameAccess.hotbar();
        try {
            ExtractionCore.MAIN.set(false);
            ExtractionCore.HOTBAR.set(3);
            ServerPlayer player = player(test, "SlotTest");
            var inventory = player.getInventory();
            var menu = player.inventoryMenu;
            inventory.setItem(0, new ItemStack(Items.COPPER_INGOT));
            inventory.setItem(1, new ItemStack(Items.COAL));
            inventory.setItem(2, new ItemStack(Items.GOLD_INGOT));
            inventory.setItem(8, new ItemStack(Items.CLOCK));
            inventory.setItem(9, new ItemStack(Items.DIAMOND, 4));
            inventory.selected = 2;
            inventory.setSelectedHotbarSlot(GameAccess.scrolledSlot(-1, inventory.selected, GameAccess.policy(player).hotbar()));
            test.assertTrue(inventory.selected == 0, "滚轮向右应从第三格回到第一格");
            inventory.setSelectedHotbarSlot(GameAccess.scrolledSlot(1, inventory.selected, GameAccess.policy(player).hotbar()));
            test.assertTrue(inventory.selected == 2, "滚轮向左应回到第三格");
            player.connection.handleSetCarriedItem(new ServerboundSetCarriedItemPacket(8));
            test.assertTrue(inventory.selected == 2, "数字键不能选中禁用的快捷栏");
            menu.clicked(36, 8, ClickType.SWAP, player);
            test.assertTrue(inventory.getItem(0).is(Items.COPPER_INGOT) && inventory.getItem(8).is(Items.CLOCK), "数字键交换写入禁用槽");
            menu.clicked(36, 2, ClickType.SWAP, player);
            test.assertTrue(inventory.getItem(0).is(Items.GOLD_INGOT), "开放的数字键应能交换物品");
            test.assertTrue(inventory.getSuitableHotbarSlot() < 3, "选取方块指向了禁用槽");
            inventory.addAndPickItem(new ItemStack(Items.CLOCK));
            test.assertTrue(inventory.selected < 3 && inventory.getItem(8).is(Items.CLOCK), "选取方块取用了禁用格中的旧物");
            menu.clicked(9, 0, ClickType.QUICK_MOVE, player);
            test.assertTrue(inventory.getItem(9).getCount() == 4, "禁用槽参与了快捷转移");
            inventory.setItem(1, ItemStack.EMPTY);
            inventory.setItem(2, new ItemStack(Items.DIAMOND, 2));
            menu.setCarried(new ItemStack(Items.DIAMOND));
            menu.clicked(37, 0, ClickType.PICKUP_ALL, player);
            test.assertTrue(menu.getCarried().getCount() == 3 && inventory.getItem(9).getCount() == 4, "双击收集应只合并开放槽中的物品");
            menu.setCarried(ItemStack.EMPTY);
            menu.clicked(9, 0, ClickType.PICKUP, player);
            test.assertTrue(menu.getCarried().getCount() == 4 && inventory.getItem(9).isEmpty(), "禁用槽中的旧物应能取回");
            menu.clicked(9, 0, ClickType.PICKUP, player);
            test.assertTrue(inventory.getItem(9).isEmpty() && menu.getCarried().getCount() == 4, "取回后重新放入禁用槽");
            inventory.setItem(1, ItemStack.EMPTY);
            menu.clicked(37, 0, ClickType.PICKUP, player);
            test.assertTrue(inventory.getItem(1).getCount() == 4 && menu.getCarried().isEmpty(), "取回物品应能放入开放槽");
            ExtractionCore.HOTBAR.set(0);
            inventory.selected = 0;
            ItemStack old = inventory.getItem(0).copy();
            inventory.addAndPickItem(new ItemStack(Items.EMERALD));
            inventory.setSelectedHotbarSlot(GameAccess.scrolledSlot(-1, inventory.selected, GameAccess.policy(player).hotbar()));
            test.assertTrue(inventory.getSelected().isEmpty() && inventory.removeFromSelected(true).isEmpty()
                && ItemStack.matches(old, inventory.getItem(0)), "零快捷栏仍从禁用主手取物");
            inventory.setItem(9, bag());
            menu.clicked(9, 0, ClickType.PICKUP, player);
            int slot = menu.slots.stream().filter(PackSlot.class::isInstance).findFirst().orElseThrow().index;
            menu.clicked(slot, 0, ClickType.PICKUP, player);
            test.assertTrue(GameAccess.selectedBag(player) == GameAccess.EQUIPMENT_SLOT
                && GameAccess.isBag(GameAccess.itemAt(player, GameAccess.EQUIPMENT_SLOT)), "零快捷栏时独立装备位无法装备背包");
            test.succeed();
        } finally {
            ExtractionCore.MAIN.set(main);
            ExtractionCore.HOTBAR.set(hotbar);
        }
    }

    @GameTest(template = "test_room")
    public static void recipeAndAmmoRespectInventoryPolicy(GameTestHelper test) {
        boolean main = GameAccess.mainEnabled();
        int hotbar = GameAccess.hotbar();
        try {
            ExtractionCore.MAIN.set(false);
            ExtractionCore.HOTBAR.set(3);
            ServerPlayer player = player(test, "IngredientTest");
            var inventory = player.getInventory();
            var menu = player.inventoryMenu;
            ItemStack bow = new ItemStack(Items.BOW);
            inventory.setItem(0, bow);
            inventory.setItem(1, new ItemStack(Items.IRON_INGOT));
            inventory.setItem(2, new ItemStack(Items.COAL));
            inventory.setItem(9, new ItemStack(Items.OAK_LOG));
            inventory.setItem(10, new ItemStack(Items.ARROW, 5));
            test.assertTrue(player.getProjectile(bow).isEmpty(), "禁用的原版背包仍提供弹药");
            inventory.setItem(40, new ItemStack(Items.ARROW, 2));
            test.assertTrue(player.getProjectile(bow).getCount() == 2, "副手原有的弹药用途应保持可用");
            var recipe = test.getLevel().recipeAccess().byKey(ResourceKey.create(Registries.RECIPE, ResourceLocation.parse("minecraft:oak_planks"))).orElseThrow();
            player.awardRecipes(List.of(recipe));
            menu.handlePlacement(false, false, recipe, test.getLevel(), inventory);
            test.assertTrue(menu.getCraftSlots().isEmpty() && inventory.getItem(9).is(Items.OAK_LOG), "配方书使用了禁用槽里的原木");
            menu.getSlot(1).set(new ItemStack(Items.CARROT));
            menu.handlePlacement(false, false, recipe, test.getLevel(), inventory);
            test.assertTrue(menu.getSlot(1).getItem().is(Items.CARROT), "配方书把禁用格当作清空合成栏的可用空间");
            menu.getSlot(1).set(ItemStack.EMPTY);
            inventory.setItem(2, new ItemStack(Items.OAK_LOG));
            menu.handlePlacement(false, false, recipe, test.getLevel(), inventory);
            test.assertTrue(!menu.getCraftSlots().isEmpty() && inventory.getItem(2).isEmpty()
                && inventory.getItem(9).getCount() == 1, "配方书应只使用开放槽中的原木");
            test.succeed();
        } finally {
            ExtractionCore.MAIN.set(main);
            ExtractionCore.HOTBAR.set(hotbar);
        }
    }

    @GameTest(template = "test_room")
    public static void pickupRetainsComponentsAndRemainder(GameTestHelper test) {
        boolean main = GameAccess.mainEnabled();
        int hotbar = GameAccess.hotbar();
        try {
            ExtractionCore.MAIN.set(false);
            ExtractionCore.HOTBAR.set(3);
            ServerPlayer player = player(test, "PickupTest");
            ItemStack supplies = new ItemStack(Items.PAPER, 2);
            supplies.set(ExtractionCore.BACKPACK.get(), ExtractionCore.id("field_pack"));
            LootSessions.initializeBag(player, supplies);
            player.getInventory().setItem(0, supplies);
            test.assertTrue(GameAccess.instanceId(supplies).isEmpty() && !GameAccess.equip(player, 0),
                "成叠背包应在拆开后分别分配库存");
            ItemStack pack = supplies.split(1);
            player.getInventory().setItem(0, pack);
            test.assertTrue(GameAccess.equip(player, 0) && GameAccess.selectedBag(player) == GameAccess.EQUIPMENT_SLOT,
                "带有背包组件的普通物品应可装备");
            LootSessions.initializeBag(player, pack);
            LootSessions.initializeBag(player, supplies);
            test.assertTrue(pack.getMaxStackSize() == 1 && GameAccess.instanceId(pack).isPresent()
                && !GameAccess.instanceId(pack).equals(GameAccess.instanceId(supplies)), "拆开的两个背包应使用独立库存");
            CompoundTag saved = new CompoundTag();
            saved.putString("layout", "{\"outer\":{\"shape\":[\"X\"]},\"sections\":[{\"id\":\"main\",\"shape\":[\"X\"]}]}");
            GameAccess.saveBag(player, pack, saved);
            GameAccess.setItemAt(player, GameAccess.EQUIPMENT_SLOT, pack);
            test.assertTrue(LootSessions.pickup(player, iron(63)) == 63, "背包应能收下初始的 63 个铁粒");
            player.getInventory().setItem(0, iron(63));
            player.getInventory().setItem(1, new ItemStack(Items.COAL, 64));
            player.getInventory().setItem(2, new ItemStack(Items.COAL, 64));
            ItemEntity ground = ground(player, iron(7));
            ground.playerTouch(player);
            test.assertTrue(player.getInventory().getItem(0).getCount() == 64, "应先补满开放的原版槽");
            var entry = GameAccess.bagData(player, pack).getList("entries", Tag.TAG_COMPOUND).getCompound(0);
            ItemStack stored = ItemStack.parse(player.registryAccess(), entry.getCompound("stack")).orElseThrow();
            test.assertTrue(stored.getCount() == 64 && ItemStack.isSameItemSameComponents(stored, iron(1)), "背包未接收第二个铁粒或组件发生变化");
            test.assertTrue(ground.isAlive() && ground.getItem().getCount() == 5
                && ItemStack.isSameItemSameComponents(ground.getItem(), iron(1)), "地上的余量或数据组件发生变化");
            ground.playerTouch(player);
            test.assertTrue(ground.getItem().getCount() == 5 && player.getInventory().getItem(9).isEmpty(), "无处可放的铁粒进入了禁用槽");
            player.getInventory().setItem(0, iron(63));
            var pickedUp = Stats.ITEM_PICKED_UP.get(Items.IRON_NUGGET);
            int before = player.getStats().getValue(pickedUp);
            ground.playerTouch(player);
            test.assertTrue(ground.getItem().getCount() == 4 && player.getStats().getValue(pickedUp) == before + 1,
                "部分拾取应按原版槽实际接收的数量结算统计");
            ExtractionCore.HOTBAR.set(0);
            player.getInventory().setItem(0, ItemStack.EMPTY);
            ground.playerTouch(player);
            test.assertTrue(ground.getItem().getCount() == 4 && player.getInventory().getItem(0).isEmpty(), "零快捷栏仍接受拾取");
            ground.discard();
            test.succeed();
        } finally {
            ExtractionCore.MAIN.set(main);
            ExtractionCore.HOTBAR.set(hotbar);
        }
    }

    @GameTest(template = "test_room")
    public static void equippedBackpackFollowsDeathRule(GameTestHelper test) {
        var keepInventory = test.getLevel().getGameRules().getRule(GameRules.RULE_KEEPINVENTORY);
        boolean previous = keepInventory.get();
        try {
            for (boolean keep : new boolean[]{false, true}) {
                keepInventory.set(keep, test.getLevel().getServer());
                ServerPlayer player = player(test, keep ? "KeepPack" : "DropPack");
                ItemStack pack = bag();
                CompoundTag saved = new CompoundTag();
                saved.putString("marker", "同一背包实例");
                GameAccess.saveBag(player, pack, saved);
                GameAccess.setItemAt(player, GameAccess.EQUIPMENT_SLOT, pack);
                UUID id = GameAccess.instanceId(pack).orElseThrow();
                var legacyLayout = ExtractionCore.id("legacy_pack");
                CustomData.update(DataComponents.CUSTOM_DATA, pack, value -> value.putString("extraction_definition", legacyLayout.toString()));
                test.assertTrue(GameAccess.bagType(pack).equals(legacyLayout), "默认组件覆盖了存档中的背包定义");
                GameAccess.saveBag(player, pack, GameAccess.bagData(player, pack));
                test.assertTrue(legacyLayout.equals(pack.get(ExtractionCore.BACKPACK.get()))
                    && GameAccess.instanceId(pack).orElseThrow().equals(id), "迁移背包定义时改变了原实例");
                player.die(player.damageSources().genericKill());
                var drops = test.getLevel().getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(4),
                    entity -> GameAccess.instanceId(entity.getItem()).filter(id::equals).isPresent());
                ServerPlayer respawned = player(test, player.getGameProfile());
                respawned.restoreFrom(player, false);
                ItemStack equipped = GameAccess.itemAt(respawned, GameAccess.EQUIPMENT_SLOT);
                if (keep) {
                    test.assertTrue(drops.isEmpty() && GameAccess.instanceId(equipped).filter(id::equals).isPresent()
                        && GameAccess.bagData(respawned, equipped).getString("marker").equals("同一背包实例"), "开启 keepInventory 后未保留背包实例");
                } else {
                    test.assertTrue(drops.size() == 1 && equipped.isEmpty()
                        && GameAccess.bagData(respawned, drops.getFirst().getItem()).getString("marker").equals("同一背包实例"), "关闭 keepInventory 后应只掉落一只原背包");
                    drops.getFirst().discard();
                }
            }
            test.succeed();
        } finally {
            keepInventory.set(previous, test.getLevel().getServer());
        }
    }

    private static ItemStack iron(int count) {
        ItemStack stack = new ItemStack(Items.IRON_NUGGET, count);
        stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(7f), List.of(), List.of(), List.of()));
        return stack;
    }

    private static ItemStack bag() {
        return GameAccess.bound(ExtractionCore.PACK.get(), ExtractionCore.id("field_pack"));
    }

    private static ServerPlayer player(GameTestHelper test, String name) {
        return player(test, new GameProfile(UUID.randomUUID(), name));
    }

    private static ServerPlayer player(GameTestHelper test, GameProfile profile) {
        var level = test.getLevel();
        var cookie = CommonListenerCookie.createInitial(profile, false);
        var player = new ServerPlayer(level.getServer(), level, profile, cookie.clientInformation());
        new ServerGamePacketListenerImpl(level.getServer(), new Connection(PacketFlow.SERVERBOUND), player, cookie);
        player.setPos(Vec3.atCenterOf(test.absolutePos(new BlockPos(1, 1, 1))));
        return player;
    }

    private static ItemEntity ground(ServerPlayer player, ItemStack stack) {
        ItemEntity drop = new ItemEntity(player.serverLevel(), player.getX(), player.getY(), player.getZ(), stack);
        drop.setNoPickUpDelay();
        player.serverLevel().addFreshEntity(drop);
        return drop;
    }
}
