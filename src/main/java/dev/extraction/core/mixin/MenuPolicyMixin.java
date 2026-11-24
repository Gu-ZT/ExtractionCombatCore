package dev.extraction.core.mixin;

import dev.extraction.core.GameAccess;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContainerMenu.class)
abstract class MenuPolicyMixin {
    @Shadow public abstract Slot getSlot(int index);
    @Shadow public abstract boolean isValidSlotIndex(int index);

    @Inject(method = "clicked", at = @At("HEAD"), cancellable = true)
    private void extractionLimitClick(int index, int button, ClickType type, Player player, CallbackInfo ci) {
        if (type == ClickType.SWAP && !GameAccess.policy(player).enabled(button)) {
            ci.cancel();
        } else if (index >= 0 && isValidSlotIndex(index) && type != ClickType.PICKUP) {
            Slot slot = getSlot(index);
            if (slot.container instanceof Inventory inventory && !GameAccess.policy(inventory.player).enabled(slot.getContainerSlot())) {
                ci.cancel();
            }
        }
    }

    @Inject(method = "canTakeItemForPickAll", at = @At("HEAD"), cancellable = true)
    private void extractionCollectEnabled(ItemStack carried, Slot slot, CallbackInfoReturnable<Boolean> ci) {
        if (slot.container instanceof Inventory inventory && !GameAccess.policy(inventory.player).enabled(slot.getContainerSlot())) {
            ci.setReturnValue(false);
        }
    }
}
