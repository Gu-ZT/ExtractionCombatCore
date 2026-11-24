package dev.extraction.core.mixin;

import dev.extraction.core.GameAccess;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Slot.class)
abstract class SlotPolicyMixin {
    @Shadow @Final public Container container;
    @Shadow public abstract int getContainerSlot();

    @Inject(method = "mayPlace", at = @At("HEAD"), cancellable = true)
    private void extractionRejectInput(ItemStack stack, CallbackInfoReturnable<Boolean> ci) {
        if (container instanceof Inventory inventory && !GameAccess.policy(inventory.player).enabled(getContainerSlot())) {
            ci.setReturnValue(false);
        }
    }

    // 原版快捷转移在合并已有物品栈时只读取容量。
    @Inject(method = "getMaxStackSize(Lnet/minecraft/world/item/ItemStack;)I", at = @At("HEAD"), cancellable = true)
    private void extractionRejectMerge(ItemStack stack, CallbackInfoReturnable<Integer> ci) {
        if (container instanceof Inventory inventory && !GameAccess.policy(inventory.player).enabled(getContainerSlot())) {
            ci.setReturnValue(0);
        }
    }
}
