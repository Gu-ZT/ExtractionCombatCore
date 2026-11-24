package dev.extraction.core.mixin;

import dev.extraction.core.GameAccess;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Player.class)
abstract class PlayerInventoryUseMixin {
    @Redirect(method = "getProjectile", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Inventory;getItem(I)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack extractionAccessibleAmmo(Inventory inventory, int slot) {
        return GameAccess.policy(inventory.player).enabled(slot) ? inventory.getItem(slot) : ItemStack.EMPTY;
    }
}
