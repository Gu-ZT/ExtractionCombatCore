package dev.extraction.core.mixin;

import dev.extraction.core.GameAccess;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
abstract class PlayerPacketsMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handleSetCarriedItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/game/ServerboundSetCarriedItemPacket;getSlot()I", ordinal = 0), cancellable = true)
    private void extractionSelect(ServerboundSetCarriedItemPacket packet, CallbackInfo ci) {
        if (!GameAccess.enabled(packet.getSlot())) {
            if (!GameAccess.enabled(player.getInventory().selected)) {
                player.getInventory().selected = 0;
            }
            player.connection.send(new ClientboundSetHeldSlotPacket(player.getInventory().selected));
            ci.cancel();
        }
    }

    @Inject(method = "handlePlayerAction", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/game/ServerboundPlayerActionPacket;getAction()Lnet/minecraft/network/protocol/game/ServerboundPlayerActionPacket$Action;", ordinal = 0), cancellable = true)
    private void extractionSwap(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        if (packet.getAction() == ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND && !GameAccess.enabled(player.getInventory().selected)) {
            ci.cancel();
        }
    }

    @Inject(method = "handleSetCreativeModeSlot", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayerGameMode;isCreative()Z"), cancellable = true)
    private void extractionCreativeInput(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo ci) {
        int slot = packet.slotNum();
        if (slot >= 9 && slot < 45 && !GameAccess.enabled(slot >= 36 ? slot - 36 : slot)) {
            ci.cancel();
        }
    }
}
