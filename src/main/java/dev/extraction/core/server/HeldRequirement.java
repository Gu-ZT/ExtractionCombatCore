package dev.extraction.core.server;

import com.google.gson.JsonObject;
import dev.extraction.core.GameAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** 将同一路线的手持条件共同分配到两只手，只在成功结算时扣除物品和耐久。 */
record HeldRequirement(String type, JsonObject definition) {
    static boolean check(ServerPlayer player, List<HeldRequirement> held, boolean pay) {
        int[] consume = new int[2];
        int[] damage = new int[2];
        if (!assignHands(player, held, 0, consume, damage)) {
            return false;
        }
        if (pay) {
            for (InteractionHand hand : InteractionHand.values()) {
                int i = hand.ordinal();
                ItemStack stack = player.getItemInHand(hand);
                if (damage[i] > 0) {
                    stack.hurtAndBreak(damage[i], player, hand == InteractionHand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);
                }
                stack.shrink(consume[i]);
            }
        }
        return true;
    }

    private static boolean assignHands(ServerPlayer player, List<HeldRequirement> held, int next, int[] consume, int[] damage) {
        if (next == held.size()) {
            return true;
        }
        HeldRequirement entry = held.get(next);
        JsonObject requirement = entry.definition;
        String type = entry.type;
        String key = type.equals("held_item") ? "item" : "tag";
        ResourceLocation id = ResourceLocation.tryParse(requirement.get(key).getAsString().replaceFirst("^#", ""));
        if (id == null) {
            return false;
        }
        int quantity = requirement.has("consume") && requirement.get("consume").getAsBoolean() ? 1 : 0;
        int cost = requirement.has("damage") ? requirement.get("damage").getAsInt() : 0;
        for (InteractionHand hand : InteractionHand.values()) {
            int i = hand.ordinal();
            ItemStack stack = player.getItemInHand(hand);
            if (hand == InteractionHand.MAIN_HAND && !GameAccess.isEnabled(player, player.getInventory().selected)) {
                continue;
            }
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
            boolean matches;
            if (type.equals("held_item")) {
                matches = itemId.equals(id);
            } else if (requirement.has("_resolved_items")) {
                matches = requirement.getAsJsonArray("_resolved_items").asList().stream()
                    .anyMatch(item -> item.getAsString().equals(itemId.toString()));
            } else {
                matches = stack.is(TagKey.create(Registries.ITEM, id));
            }
            if (!matches || stack.isEmpty() || quantity > stack.getCount() - consume[i]
                || (cost > 0 && (!stack.isDamageableItem() || cost > stack.getMaxDamage() - stack.getDamageValue() - damage[i]))) {
                continue;
            }
            consume[i] += quantity;
            damage[i] += cost;
            if (assignHands(player, held, next + 1, consume, damage)) {
                return true;
            }
            consume[i] -= quantity;
            damage[i] -= cost;
        }
        return false;
    }
}
