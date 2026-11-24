package dev.extraction.core.mixin;

import dev.extraction.core.server.LootSessions;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ItemEntity.class)
abstract class PickupMixin {
    // 在所有者与拾取延迟通过原版检查后，再尝试收纳剩余数量。
    @Redirect(method = "playerTouch", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Inventory;add(Lnet/minecraft/world/item/ItemStack;)Z"))
    private boolean extractionCollect(Inventory inventory, ItemStack incoming) {
        int original = incoming.getCount();
        if (inventory.player.hasInfiniteMaterials()) {
            while (!incoming.isEmpty()) {
                int slot = inventory.getSlotWithRemainingSpace(incoming);
                if (slot < 0) {
                    slot = inventory.getFreeSlot();
                }
                if (slot < 0) {
                    break;
                }
                int amount = Math.min(incoming.getCount(), incoming.getMaxStackSize() - inventory.getItem(slot).getCount());
                ItemStack portion = incoming.copyWithCount(amount);
                if (amount < 1 || !inventory.add(slot, portion)) {
                    break;
                }
                incoming.shrink(amount - portion.getCount());
            }
        } else {
            inventory.add(incoming);
        }
        if (!incoming.isEmpty() && inventory.player instanceof ServerPlayer player) {
            int stored = LootSessions.pickup(player, incoming);
            incoming.shrink(stored);
        }
        return incoming.getCount() < original;
    }
}
