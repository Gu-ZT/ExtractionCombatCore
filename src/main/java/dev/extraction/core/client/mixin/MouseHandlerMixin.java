package dev.extraction.core.client.mixin;

import dev.extraction.core.GameAccess;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
    @ModifyArg(method = "onScroll", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/ScrollWheelHandler;getNextScrollWheelSelection(DII)I"), index = 2)
    private int extractioncombatcore$openHotbarSlots(int vanillaSize) {
        return Math.max(1, GameAccess.clientPolicy().hotbar());
    }
}
