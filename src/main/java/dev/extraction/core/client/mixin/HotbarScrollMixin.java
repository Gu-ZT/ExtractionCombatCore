package dev.extraction.core.client.mixin;

import dev.extraction.core.GameAccess;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(MouseHandler.class)
abstract class HotbarScrollMixin {
    @Redirect(method = "onScroll", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/ScrollWheelHandler;getNextScrollWheelSelection(DII)I"))
    private int extractionScroll(double direction, int selected, int vanillaSize) {
        return GameAccess.scrolledSlot(direction, selected, GameAccess.clientPolicy().hotbar());
    }
}
