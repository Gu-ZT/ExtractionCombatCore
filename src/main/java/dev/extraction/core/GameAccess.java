package dev.extraction.core;

import net.minecraft.core.UUIDUtil;
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
        if (isBag(stack)) {
            stack.set(ExtractionCore.BACKPACK.get(), type);
        } else {
            CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putString(TYPE, type.toString()));
        }
        stack.set(DataComponents.ITEM_NAME, Component.translatableWithFallback(
            "definition." + type.getNamespace() + "." + type.getPath().replace('/', '.'), type.toString()));
        return stack;
    }

    static ResourceLocation definition(ItemStack stack, String fallback) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return tag.contains(TYPE) ? ResourceLocation.parse(tag.getStringOr(TYPE, "")) : ExtractionCore.id(fallback);
    }

    public static ResourceLocation bagType(ItemStack stack) {
        ResourceLocation layout = stack.get(ExtractionCore.BACKPACK.get());
        if (layout == null || (stack.is(ExtractionCore.PACK.get()) && layout.equals(ExtractionCore.id("field_pack")))) {
            return definition(stack, "field_pack");
        }
        return layout;
    }

    public static boolean isBag(ItemStack stack) {
        return !stack.isEmpty() && stack.has(ExtractionCore.BACKPACK.get());
    }

    /** 多件未分配实例的背包可以拆开，只有单件物品栈可以持有库存。 */
    public static boolean isSingleBag(ItemStack stack) {
        return stack.getCount() == 1 && isBag(stack);
    }

    /** 背包拆为单件后才建立库存，已有实例可跳过物品栏逐刻的初始化。 */
    public static boolean needsBagInstance(ItemStack stack) {
        return isSingleBag(stack) && !stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).contains(INSTANCE);
    }

    public static Optional<UUID> instanceId(ItemStack stack) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return tag.read(INSTANCE, UUIDUtil.CODEC);
    }

    public static CompoundTag bagData(ServerPlayer player, ItemStack stack) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return tag.read(INSTANCE, UUIDUtil.CODEC).map(id -> WorldContents.get(player.level()).pack(id)).orElseGet(CompoundTag::new);
    }

    public static void saveBag(ServerPlayer player, ItemStack stack, CompoundTag data) {
        if (!isSingleBag(stack)) {
            throw new IllegalArgumentException("保存背包需要单件带有背包组件的物品");
        }
        if (stack.getMaxStackSize() != 1) {
            stack.set(DataComponents.MAX_STACK_SIZE, 1);
        }
        if (stack.is(ExtractionCore.PACK.get()) && stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).contains(TYPE)) {
            stack.set(ExtractionCore.BACKPACK.get(), bagType(stack));
            CustomData.update(DataComponents.CUSTOM_DATA, stack, value -> value.remove(TYPE));
        }
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        Optional<UUID> previous = tag.read(INSTANCE, UUIDUtil.CODEC);
        UUID id = previous.orElseGet(UUID::randomUUID);
        if (previous.isEmpty()) {
            CustomData.update(DataComponents.CUSTOM_DATA, stack, value -> value.store(INSTANCE, UUIDUtil.CODEC, id));
        }
        WorldContents.get(player.level()).savePack(id, data);
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
        return player.level().isClientSide() ? clientPolicy : serverPolicy();
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
        int index = player.getPersistentData().getIntOr(SELECTED, 0);
        if (player.getPersistentData().contains(SELECTED) && enabled(index) && isSingleBag(itemAt(player, index))) {
            return index;
        }
        return isSingleBag(itemAt(player, EQUIPMENT_SLOT)) ? EQUIPMENT_SLOT : -1;
    }

    public static void selectBag(ServerPlayer player, int index) {
        if (index == -1) {
            player.getPersistentData().remove(SELECTED);
        } else if ((index >= 0 && index < 36 || index == 40 || index == EQUIPMENT_SLOT)
            && enabled(index) && isSingleBag(itemAt(player, index))) {
            player.getPersistentData().putInt(SELECTED, index);
        }
    }

    public static boolean equip(ServerPlayer player, int source) {
        if ((source < 0 || source >= 36) && source != 40) {
            return false;
        }
        ItemStack bag = itemAt(player, source);
        ItemStack prior = itemAt(player, EQUIPMENT_SLOT);
        if ((!bag.isEmpty() && !isSingleBag(bag)) || (!enabled(source) && !prior.isEmpty())) {
            return false;
        }
        setItemAt(player, source, prior);
        setItemAt(player, EQUIPMENT_SLOT, bag);
        selectBag(player, -1);
        return true;
    }
}
