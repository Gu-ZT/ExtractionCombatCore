package dev.extraction.core;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.UUID;
import java.util.Optional;

public final class GameAccess {
    public static final int EQUIPMENT_SLOT = 41;
    private static final String TYPE = "extraction_definition";
    private static final String INSTANCE = "extraction_instance";
    private static final String SELECTED = "extraction_selected";
    public static final InventoryPolicy DEFAULT_POLICY = new InventoryPolicy(true, 9);
    private static volatile InventoryPolicy clientPolicy = DEFAULT_POLICY;
    private static volatile InventoryPolicy serverPolicy = DEFAULT_POLICY;

    private GameAccess() {}

    public static ItemStack bound(Item item, ResourceLocation type) {
        ItemStack stack = new ItemStack(item);
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putString(TYPE, type.toString()));
        stack.set(DataComponents.ITEM_NAME, Component.translatableWithFallback(
            "definition." + type.getNamespace() + "." + type.getPath().replace('/', '.'), type.toString()));
        return stack;
    }

    static ResourceLocation definition(ItemStack stack, String fallback) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return tag.contains(TYPE) ? ResourceLocation.parse(tag.getString(TYPE)) : ExtractionCore.id(fallback);
    }

    public static ResourceLocation bagType(ItemStack stack) {
        return definition(stack, "field_pack");
    }

    public static boolean isBag(ItemStack stack) {
        return stack.is(ExtractionCore.PACK.get());
    }

    public static Optional<UUID> instanceId(ItemStack stack) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return tag.hasUUID(INSTANCE) ? Optional.of(tag.getUUID(INSTANCE)) : Optional.empty();
    }

    public static CompoundTag bagData(ServerPlayer player, ItemStack stack) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return tag.hasUUID(INSTANCE) ? WorldContents.get(player.serverLevel()).pack(tag.getUUID(INSTANCE)) : new CompoundTag();
    }

    public static void saveBag(ServerPlayer player, ItemStack stack, CompoundTag data) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        UUID id = tag.hasUUID(INSTANCE) ? tag.getUUID(INSTANCE) : UUID.randomUUID();
        if (!tag.hasUUID(INSTANCE)) {
            CustomData.update(DataComponents.CUSTOM_DATA, stack, value -> value.putUUID(INSTANCE, id));
        }
        WorldContents.get(player.serverLevel()).savePack(id, data);
    }

    public static boolean flag(ServerLevel level, ResourceLocation id) {
        return WorldContents.get(level).flag(id);
    }

    public record InventoryPolicy(boolean mainEnabled, int hotbar) {
        public boolean enabled(int index) {
            if (index >= 0 && index < 9) {
                return index < hotbar;
            }
            if (index >= 9 && index < 36) {
                return mainEnabled;
            }
            return index >= 36 && index <= EQUIPMENT_SLOT;
        }

        public CompoundTag encode() {
            CompoundTag tag = new CompoundTag();
            tag.putBoolean("main_enabled", mainEnabled);
            tag.putInt("hotbar", hotbar);
            return tag;
        }
    }

    public static InventoryPolicy serverPolicy() {
        boolean main = mainEnabled();
        int count = hotbar();
        InventoryPolicy current = serverPolicy;
        if (current.mainEnabled() != main || current.hotbar() != count) {
            current = new InventoryPolicy(main, count);
            serverPolicy = current;
        }
        return current;
    }

    public static InventoryPolicy policy(Player player) {
        return player.level().isClientSide ? clientPolicy : serverPolicy();
    }

    public static int scrolledSlot(double direction, int selected, int hotbar) {
        return hotbar == 0 ? 0 : Math.floorMod(selected - (int) Math.signum(direction), hotbar);
    }

    public static InventoryPolicy clientPolicy() {
        return clientPolicy;
    }

    public static void setClientPolicy(InventoryPolicy policy) {
        clientPolicy = policy;
    }

    public static boolean enabled(int index) {
        return serverPolicy().enabled(index);
    }

    public static boolean mainEnabled() {
        return !ExtractionCore.CONFIG.isLoaded() || ExtractionCore.MAIN.get();
    }

    public static int hotbar() {
        return ExtractionCore.CONFIG.isLoaded() ? ExtractionCore.HOTBAR.get() : 9;
    }

    public static boolean isEnabled(ServerPlayer player, int index) {
        return enabled(index);
    }

    public static ItemStack itemAt(Player player, int index) {
        return index == EQUIPMENT_SLOT ? player.getData(ExtractionCore.EQUIPPED) : player.getInventory().getItem(index);
    }

    public static void setItemAt(Player player, int index, ItemStack item) {
        if (index == EQUIPMENT_SLOT) {
            player.setData(ExtractionCore.EQUIPPED, item);
        } else {
            player.getInventory().setItem(index, item);
        }
        player.getInventory().setChanged();
    }

    public static int selectedBag(ServerPlayer player) {
        int index = player.getPersistentData().getInt(SELECTED);
        if (player.getPersistentData().contains(SELECTED) && enabled(index) && isBag(itemAt(player, index))) {
            return index;
        }
        return isBag(itemAt(player, EQUIPMENT_SLOT)) ? EQUIPMENT_SLOT : -1;
    }

    public static void selectBag(ServerPlayer player, int index) {
        if (index == -1) {
            player.getPersistentData().remove(SELECTED);
        } else if ((index >= 0 && index < 36 || index == 40 || index == EQUIPMENT_SLOT)
            && enabled(index) && isBag(itemAt(player, index))) {
            player.getPersistentData().putInt(SELECTED, index);
        }
    }

    public static boolean equip(ServerPlayer player, int source) {
        if ((source < 0 || source >= 36) && source != 40) {
            return false;
        }
        ItemStack bag = itemAt(player, source);
        ItemStack prior = itemAt(player, EQUIPMENT_SLOT);
        if ((!bag.isEmpty() && !isBag(bag)) || (!enabled(source) && !prior.isEmpty())) {
            return false;
        }
        setItemAt(player, source, prior);
        setItemAt(player, EQUIPMENT_SLOT, bag);
        selectBag(player, -1);
        return true;
    }
}
