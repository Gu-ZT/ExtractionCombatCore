package dev.extraction.core;

import dev.extraction.core.server.LootSessions;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

final class FieldPack extends Item {
    FieldPack(Properties properties) {
        super(properties.component(ExtractionCore.BACKPACK.get(), ExtractionCore.id("field_pack")));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer serverPlayer) {
            LootSessions.openPack(serverPlayer, hand == InteractionHand.OFF_HAND ? 40 : player.getInventory().selected);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
}
