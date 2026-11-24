package dev.extraction.core;

import dev.extraction.core.server.LootSessions;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

final class FieldPack extends Item {
    FieldPack(Properties properties) {
        super(properties);
    }

    @Override
    public void inventoryTick(ItemStack stack, ServerLevel level, Entity entity, EquipmentSlot slot) {
        if (entity instanceof ServerPlayer player && !stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).contains("extraction_instance")) {
            LootSessions.initializeBag(player, stack);
        }
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer) {
            LootSessions.openPack(serverPlayer, hand == InteractionHand.OFF_HAND ? 40 : player.getInventory().getSelectedSlot());
        }
        return InteractionResult.SUCCESS;
    }
}
