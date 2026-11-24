package dev.extraction.core.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import dev.extraction.core.GameAccess;
import dev.extraction.core.rules.Grid;
import dev.extraction.core.rules.RuleBook;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

final class StoredGrid {
    final RuleBook.Storage layout;
    private final JsonObject definition;
    private final RuleBook itemRules;
    private final byte[] itemSnapshot;
    final boolean bag;
    private final Consumer<CompoundTag> save;
    private final HolderLookup.Provider registries;
    final CompoundTag discoveries;
    List<Grid.Entry> entries;
    private final List<Grid.Entry> pending;
    boolean unlocked;
    boolean generated;
    long revision;

    StoredGrid(RuleBook.Storage definition, boolean bag, CompoundTag previous, HolderLookup.Provider registries,
               Consumer<CompoundTag> save) {
        this.bag = bag;
        this.registries = registries;
        this.save = save;
        this.layout = definition;
        this.definition = definition.definition();
        if (previous.contains("item_rules", Tag.TAG_BYTE_ARRAY)) {
            this.itemSnapshot = previous.getByteArray("item_rules");
            this.itemRules = RuleBook.restore(new String(itemSnapshot, StandardCharsets.UTF_8), registries);
        } else {
            this.itemRules = RuleBook.current();
            this.itemSnapshot = itemRules.publicItems().getBytes(StandardCharsets.UTF_8);
        }
        this.unlocked = previous.getBoolean("unlocked") || routes().isEmpty();
        this.generated = previous.getBoolean("generated");
        this.revision = previous.getLong("revision");
        this.discoveries = previous.getCompound("discoveries").copy();
        this.entries = readEntries(previous.getList("entries", Tag.TAG_COMPOUND));
        this.pending = readEntries(previous.getList("pending", Tag.TAG_COMPOUND));
        if (!previous.contains("layout")) {
            persist();
        }
    }

    JsonArray routes() {
        if (definition.has("access") && definition.getAsJsonObject("access").has("routes")) {
            return definition.getAsJsonObject("access").getAsJsonArray("routes");
        }
        return new JsonArray();
    }

    boolean unlock(ServerPlayer player, BlockPos position, AccessChallenge challenge) {
        if (unlocked || !challenge.complete() || !challenge.allowed(player, position, true)) {
            return false;
        }
        player.containerMenu.broadcastChanges();
        unlocked = true;
        revision++;
        if (generated) {
            persist();
        } else {
            generate(player, position);
        }
        return true;
    }

    void generate(ServerPlayer player, BlockPos position) {
        if (generated) {
            return;
        }
        if (definition.has("loot_table")) {
            ResourceLocation id = ResourceLocation.parse(definition.get("loot_table").getAsString());
            var loot = player.serverLevel().getServer().reloadableRegistries().getLootTable(ResourceKey.create(Registries.LOOT_TABLE, id));
            LootParams params = new LootParams.Builder(player.serverLevel())
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(position))
                .withOptionalParameter(LootContextParams.THIS_ENTITY, player)
                .withLuck(player.getLuck()).create(LootContextParamSets.CHEST);
            for (ItemStack stack : loot.getRandomItems(params)) {
                Grid.Entry entry = incoming(player, stack, itemRules);
                int limit = Math.min(entry.style().stackLimit(), stack.getMaxStackSize());
                for (int remaining = stack.getCount(); remaining > 0; remaining -= Math.min(remaining, limit)) {
                    pending.add(new Grid.Entry(UUID.randomUUID(), stack.copyWithCount(Math.min(remaining, limit)),
                        entry.style(), 0, 0, 0, 0));
                }
            }
        }
        generated = true;
        refill();
        if (!pending.isEmpty()) {
            LogUtils.getLogger().warn("容器 {} 的布局无法容纳 {} 叠战利品，已保存待放入物品", position, pending.size());
        }
        persist();
    }

    void refill() {
        pending.removeIf(entry -> {
            Optional<List<Grid.Entry>> inserted = Grid.directNew(layout.sections(), entries, entry, section -> true);
            inserted.ifPresent(result -> entries = new ArrayList<>(result));
            return inserted.isPresent();
        });
    }

    static Grid.Style style(ServerPlayer player, ItemStack stack, RuleBook rules) {
        if (GameAccess.isBag(stack)) {
            CompoundTag data = GameAccess.bagData(player, stack);
            if (data.contains("layout")) {
                return RuleBook.restoreStorage(savedLayout(data), true).outer();
            }
            var pack = RuleBook.current().backpack(GameAccess.bagType(stack));
            if (pack.isPresent()) {
                return pack.get().outer();
            }
        }
        return rules.item(stack);
    }

    Grid.Style itemStyle(ServerPlayer player, ItemStack stack) {
        return style(player, stack, itemRules);
    }

    Grid.Entry incoming(ServerPlayer player, ItemStack stack) {
        return incoming(player, stack, itemRules);
    }

    static Grid.Entry incoming(ServerPlayer player, ItemStack stack, RuleBook rules) {
        if (GameAccess.isBag(stack)) {
            LootSessions.initializeBag(player, stack);
        }
        return new Grid.Entry(UUID.randomUUID(), stack.copy(), style(player, stack, rules), 0, 0, 0, 0);
    }

    static JsonObject savedLayout(CompoundTag data) {
        String json = data.contains("layout", Tag.TAG_BYTE_ARRAY)
            ? new String(data.getByteArray("layout"), StandardCharsets.UTF_8) : data.getString("layout");
        return JsonParser.parseString(json).getAsJsonObject();
    }

    void persist() {
        if (!discoveries.isEmpty()) {
            Set<String> live = new HashSet<>();
            entries.forEach(item -> live.add(item.id().toString()));
            for (String player : discoveries.getAllKeys()) {
                CompoundTag record = discoveries.getCompound(player);
                record.getAllKeys().removeIf(key -> !key.startsWith("section_") && !live.contains(key));
            }
        }
        CompoundTag data = new CompoundTag();
        data.putByteArray("layout", definition.toString().getBytes(StandardCharsets.UTF_8));
        data.putByteArray("item_rules", itemSnapshot);
        data.put("entries", writeEntries(entries));
        data.put("pending", writeEntries(pending));
        data.put("discoveries", discoveries.copy());
        data.putBoolean("unlocked", unlocked);
        data.putBoolean("generated", generated);
        data.putLong("revision", revision);
        save.accept(data);
    }

    private List<Grid.Entry> readEntries(ListTag records) {
        List<Grid.Entry> result = new ArrayList<>();
        for (var tag : records) {
            CompoundTag data = (CompoundTag)tag;
            ItemStack stack = ItemStack.parse(registries, data.getCompound("stack"))
                .orElseThrow(() -> new IllegalStateException("无效的库存物品 " + data.getUUID("id")));
            Grid.Style itemStyle = Grid.Style.fromJson(JsonParser.parseString(data.getString("style")).getAsJsonObject());
            result.add(new Grid.Entry(data.getUUID("id"), stack, itemStyle, data.getInt("section"),
                data.getInt("x"), data.getInt("y"), data.getInt("rotation")));
        }
        return result;
    }

    private ListTag writeEntries(List<Grid.Entry> items) {
        ListTag list = new ListTag();
        for (Grid.Entry entry : items) {
            CompoundTag data = itemData(entry);
            data.putString("style", entry.style().toJson().toString());
            list.add(data);
        }
        return list;
    }

    private CompoundTag discovery(UUID player) {
        String key = player.toString();
        if (!discoveries.contains(key)) {
            discoveries.put(key, new CompoundTag());
        }
        return discoveries.getCompound(key);
    }

    boolean instant() {
        var spec = definition;
        return bag || (spec.has("search") && spec.getAsJsonObject("search").has("mode")
            && spec.getAsJsonObject("search").get("mode").getAsString().equals("instant"));
    }

    int searchTicks() {
        var spec = definition;
        return spec.has("search") && spec.getAsJsonObject("search").has("ticks_per_item")
            ? spec.getAsJsonObject("search").get("ticks_per_item").getAsInt() : 24;
    }

    void discover(UUID player, UUID entry) {
        discovery(player).putBoolean(entry.toString(), true);
    }

    boolean visible(UUID player, Grid.Entry item) {
        return instant() || discovery(player).getBoolean(item.id().toString());
    }

    boolean done(UUID player, int section) {
        return instant() || (discovery(player).getBoolean("section_" + section)
            && entries.stream().noneMatch(item -> item.section() == section && !visible(player, item)));
    }

    int searching(UUID player) {
        for (int i = 0; i < layout.sections().size(); i++) {
            if (!done(player, i)) {
                return i;
            }
        }
        return -1;
    }

    void reveal(UUID player, int section) {
        Optional<Grid.Entry> next = entries.stream().filter(e -> e.section() == section && !visible(player, e))
            .min(Comparator.comparingInt(Grid.Entry::y).thenComparingInt(Grid.Entry::x));
        next.ifPresent(e -> discover(player, e.id()));
        if (entries.stream().noneMatch(e -> e.section() == section && !visible(player, e))) {
            discovery(player).putBoolean("section_" + section, true);
        }
        persist();
    }

    void discoverAdded(UUID player, List<Grid.Entry> previous) {
        Set<UUID> before = new HashSet<>();
        previous.forEach(entry -> before.add(entry.id()));
        entries.stream().filter(entry -> !before.contains(entry.id())).forEach(entry -> discover(player, entry.id()));
    }

    CompoundTag view(UUID player, int elapsed) {
        CompoundTag panel = new CompoundTag();
        ListTag sections = new ListTag();
        int i = 0;
        for (Grid.Section section : layout.sections()) {
            CompoundTag data = new CompoundTag();
            data.putString("id", section.id());
            data.put("shape", rows(section.shape().rows()));
            section.acceptsTag().ifPresent(tag -> data.putString("accepts_tag", tag.toString()));
            section.acceptedItems().ifPresent(ids -> data.put("accepted_items", rows(ids.stream().sorted().map(ResourceLocation::toString).toList())));
            data.putBoolean("done", done(player, i++));
            sections.add(data);
        }
        panel.put("sections", sections);
        panel.putInt("searching", searching(player));
        panel.putInt("elapsed", elapsed);
        ListTag items = new ListTag();
        entries.stream().filter(entry -> visible(player, entry)).forEach(entry -> items.add(itemView(entry)));
        panel.put("items", items);
        return panel;
    }

    private static ListTag rows(List<String> values) {
        ListTag result = new ListTag();
        values.forEach(row -> result.add(StringTag.valueOf(row)));
        return result;
    }

    private CompoundTag itemView(Grid.Entry entry) {
        CompoundTag tag = itemData(entry);
        describe(tag, entry.stack(), entry.style());
        return tag;
    }

    private CompoundTag itemData(Grid.Entry entry) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", entry.id());
        tag.put("stack", entry.stack().saveOptional(registries));
        tag.putInt("section", entry.section());
        tag.putInt("x", entry.x());
        tag.putInt("y", entry.y());
        tag.putInt("rotation", entry.rotation());
        return tag;
    }

    static void describe(CompoundTag tag, ItemStack stack, Grid.Style style) {
        tag.put("shape", rows(style.shape().rows()));
        tag.putBoolean("rotatable", style.rotatable());
        style.gridIcon().ifPresent(id -> tag.putString("grid_icon", id.toString()));
        style.slotIcon().ifPresent(id -> tag.putString("slot_icon", id.toString()));
        style.quality().ifPresentOrElse(quality -> {
            if (!quality.disabled()) {
                tag.putString("quality", quality.name());
                tag.putInt("color", quality.color());
            }
        }, () -> {
            var rarity = stack.getRarity();
            tag.putString("quality", "extractioncombatcore.rarity." + rarity.name().toLowerCase(Locale.ROOT));
            var color = rarity.getStyleModifier().apply(Style.EMPTY).getColor();
            tag.putInt("color", color == null ? 0xffffff : color.getValue());
        });
    }
}
