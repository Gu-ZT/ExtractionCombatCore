package dev.extraction.core;

import dev.extraction.core.server.LootSessions;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public final class PackSlot extends Slot {
    public PackSlot(Player player) {
        super(new EquippedContainer(player), 0, 77, 8);
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return GameAccess.isBag(stack);
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    private record EquippedContainer(Player player) implements Container {
        public int getContainerSize() { return 1; }
        public boolean stillValid(Player viewer) { return player == viewer; }
        public boolean isEmpty() { return getItem(0).isEmpty(); }
        public ItemStack getItem(int slot) { return GameAccess.itemAt(player, GameAccess.EQUIPMENT_SLOT); }
        public void setChanged() { player.getInventory().setChanged(); }
        public void clearContent() { setItem(0, ItemStack.EMPTY); }

        public void setItem(int slot, ItemStack stack) {
            GameAccess.setItemAt(player, GameAccess.EQUIPMENT_SLOT, stack);
            if (player instanceof ServerPlayer serverPlayer) {
                GameAccess.selectBag(serverPlayer, -1);
                if (GameAccess.isBag(stack)) {
                    LootSessions.initializeBag(serverPlayer, stack);
                }
            }
        }

        public ItemStack removeItem(int slot, int count) {
            return count > 0 ? removeItemNoUpdate(slot) : ItemStack.EMPTY;
        }

        public ItemStack removeItemNoUpdate(int slot) {
            ItemStack old = getItem(0);
            clearContent();
            return old;
        }
    }
}
