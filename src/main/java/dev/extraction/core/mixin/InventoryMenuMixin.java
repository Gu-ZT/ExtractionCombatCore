package dev.extraction.core.mixin;

import dev.extraction.core.GameAccess;
import dev.extraction.core.PackSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(InventoryMenu.class)
abstract class InventoryMenuMixin extends AbstractContainerMenu {
    protected InventoryMenuMixin(MenuType<?> type, int id) { super(type, id); }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void extractionAddPack(Inventory inventory, boolean active, Player owner, CallbackInfo ci) {
        addSlot(new PackSlot(owner));
    }

    @Inject(method = "quickMoveStack", at = @At("HEAD"), cancellable = true)
    private void extractionEquip(Player player, int index, CallbackInfoReturnable<ItemStack> ci) {
        if (index < 9 || index > 45) {
            return;
        }
        Slot source = getSlot(index);
        Slot target = slots.stream().filter(PackSlot.class::isInstance).findFirst().orElse(null);
        if (target != null && !target.hasItem() && GameAccess.isBag(source.getItem())) {
            ItemStack moved = source.remove(1);
            target.setByPlayer(moved);
            source.onTake(player, moved);
            ci.setReturnValue(moved.copy());
        }
    }
}
