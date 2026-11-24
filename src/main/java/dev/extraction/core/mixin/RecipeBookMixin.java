package dev.extraction.core.mixin;

import dev.extraction.core.GameAccess;
import net.minecraft.recipebook.ServerPlaceRecipe;
import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlaceRecipe.class)
abstract class RecipeBookMixin {
    @Shadow @Final private Inventory inventory;

    @Inject(method = "getAmountOfFreeSlotsInInventory", at = @At("HEAD"), cancellable = true)
    private void extractionRecipeSpace(CallbackInfoReturnable<Integer> ci) {
        if (!GameAccess.mainEnabled() || GameAccess.hotbar() < 9) {
            int free = 0;
            for (int slot = 0; slot < 36; slot++) {
                if (GameAccess.enabled(slot) && inventory.getItem(slot).isEmpty()) {
                    free++;
                }
            }
            ci.setReturnValue(free);
        }
    }
}
