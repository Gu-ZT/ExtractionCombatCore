package dev.extraction.core.client.mixin;

import dev.extraction.core.client.ExtractionClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiGraphicsExtractor.class)
abstract class VanillaIconMixin {
    // 槽位事件不能替换原版物品图标；仅在绘制物品栏或快捷栏期间替换模型。
    @Inject(method = "item(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;III)V", at = @At("HEAD"), cancellable = true)
    private void extractioncombatcore$slotIcon(LivingEntity entity, Level level, ItemStack item, int x, int y,
                                               int seed, CallbackInfo ci) {
        if (ExtractionClient.drawVanillaIcon((GuiGraphicsExtractor) (Object) this, item, x, y)) {
            ci.cancel();
        }
    }
}
