package dev.extraction.core;

import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

@EventBusSubscriber(modid = ExtractionCore.ID)
public final class MapInteractions {
    private MapInteractions() {}

    @SubscribeEvent
    public static void interact(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().getBlockState(event.getPos()).is(ExtractionCore.CABINET.get())) {
            event.setUseBlock(TriState.TRUE);
        }
    }

    @SubscribeEvent
    public static void explode(ExplosionEvent.Detonate event) {
        event.getAffectedBlocks().removeIf(pos -> event.getLevel().getBlockState(pos).is(ExtractionCore.CABINET.get()));
    }

    @SubscribeEvent
    public static void enforceSelection(PlayerTickEvent.Pre event) {
        if (event.getEntity() instanceof ServerPlayer player && player.getInventory().selected != 0
            && !GameAccess.enabled(player.getInventory().selected)) {
            player.getInventory().selected = 0;
            player.connection.send(new ClientboundSetCarriedItemPacket(0));
        }
    }
}
