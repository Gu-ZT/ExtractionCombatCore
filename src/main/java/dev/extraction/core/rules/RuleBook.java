package dev.extraction.core.rules;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import net.minecraft.advancements.predicates.ItemPredicate;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static dev.extraction.core.rules.DefinitionFields.access;
import static dev.extraction.core.rules.DefinitionFields.array;
import static dev.extraction.core.rules.DefinitionFields.bool;
import static dev.extraction.core.rules.DefinitionFields.integer;
import static dev.extraction.core.rules.DefinitionFields.invalid;
import static dev.extraction.core.rules.DefinitionFields.object;
import static dev.extraction.core.rules.DefinitionFields.resource;
import static dev.extraction.core.rules.DefinitionFields.string;

/** 整批生效的搜刮定义。服务端保存完整规则，客户端只保存公开的物品外观与匹配条件。 */
public final class RuleBook {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<String> DIRECTORIES = List.of("qualities", "items", "backpacks", "containers");
    private static final RuleBook EMPTY = new RuleBook(new JsonObject(), Map.of(), Map.of(), List.of());
    private static volatile RuleBook server = EMPTY;
    private static volatile RuleBook client = EMPTY;
    private final JsonObject definitions;
    private final Map<Identifier, Storage> backpacks;
    private final Map<Identifier, Storage> containers;
    private final List<ItemRule> items;
    private final Set<List<Identifier>> reported = ConcurrentHashMap.newKeySet();

    private RuleBook(JsonObject definitions, Map<Identifier, Storage> backpacks,
                     Map<Identifier, Storage> containers, List<ItemRule> items) {
        this.definitions = definitions;
        this.backpacks = Map.copyOf(backpacks);
        this.containers = Map.copyOf(containers);
        this.items = List.copyOf(items);
    }

    public static RuleBook current() {
        return server;
    }

    public static RuleBook client() {
        return client;
    }

    public Optional<Storage> backpack(Identifier id) {
        return Optional.ofNullable(backpacks.get(id));
    }

    public Optional<Storage> container(Identifier id) {
        return Optional.ofNullable(containers.get(id));
    }

    /** 对当前栈匹配完整的原版物品谓词，并以匹配范围和定义 ID 决定唯一规则。 */
    public Grid.Style item(ItemStack stack) {
        List<ItemRule> matches = items.stream().filter(rule -> rule.predicate.test(stack)).toList();
        if (matches.isEmpty()) {
            return Grid.Style.DEFAULT;
        }
        if (matches.size() == 1) {
            return matches.getFirst().style;
        }
        List<ItemRule> winners = matches.stream().filter(candidate -> matches.stream().noneMatch(other ->
            other != candidate && other.subsetOf(candidate) && !candidate.subsetOf(other))).toList();
        if (winners.size() > 1) {
            List<Identifier> ids = winners.stream().map(ItemRule::id).toList();
            if (reported.add(ids)) {
                LOGGER.warn("物品定义的匹配范围等价或无法比较，采用 {}，候选为 {}", ids.getFirst(), ids);
            }
        }
        return winners.getFirst().style;
    }

    public String snapshot() {
        return definitions.toString();
    }

    public String publicItems() {
        JsonObject out = new JsonObject();
        for (String group : List.of("items", "qualities")) {
            if (definitions.has(group)) {
                out.add(group, definitions.get(group));
            }
        }
        return out.toString();
    }

    public static RuleBook restore(String snapshot, HolderLookup.Provider registries) {
        return parse(object(JsonParser.parseString(snapshot), "快照"), registries);
    }

    public static void installPublicItems(String snapshot, HolderLookup.Provider registries) {
        client = restore(snapshot, registries);
    }

    public record Storage(List<Grid.Section> sections, Grid.Style outer, JsonObject definition) {
        public Storage {
            sections = List.copyOf(sections);
            definition = definition.deepCopy();
        }

        @Override
        public JsonObject definition() {
            return definition.deepCopy();
        }
    }

    public static Storage restoreStorage(JsonObject json, boolean backpack) {
        return storage(json, backpack, "layout");
    }

    public static void register(AddServerReloadListenersEvent event) {
        event.addListener(dev.extraction.core.ExtractionCore.id("rules"), new SimplePreparableReloadListener<JsonObject>() {
            @Override
            protected JsonObject prepare(ResourceManager manager, ProfilerFiller profiler) {
                return read(manager);
            }

            @Override
            protected void apply(JsonObject files, ResourceManager manager, ProfilerFiller profiler) {
                HolderLookup.Provider lookup = event.getServerResources().getRegistryLookup();
                // 本次重载的命名标签在所有监听器返回后才绑定。按原版规则读取本次资源包的标签内容。
                var itemLookup = TagLoader.ElementLookup.fromFrozenRegistry(net.minecraft.core.registries.BuiltInRegistries.ITEM);
                var loader = new TagLoader<Holder<Item>>(itemLookup::get, Registries.tagsDirPath(Registries.ITEM));
                server = resolve(files, new HashMap<>(loader.build(loader.load(manager))), lookup);
            }
        });
    }

    static JsonObject read(ResourceManager manager) {
        JsonObject files = new JsonObject();
        for (String group : DIRECTORIES) {
            JsonObject entries = new JsonObject();
            FileToIdConverter converter = FileToIdConverter.json("extraction/" + group);
            converter.listMatchingResources(manager).forEach((file, resource) -> {
                try (Reader reader = resource.openAsReader()) {
                    entries.add(converter.fileToId(file).toString(), object(JsonParser.parseReader(reader), file.toString()));
                } catch (IOException ex) {
                    throw new UncheckedIOException(file.toString(), ex);
                } catch (RuntimeException ex) {
                    throw invalid(file.toString(), ex.getMessage());
                }
            });
            files.add(group, entries);
        }
        return files;
    }

    static RuleBook resolve(JsonObject files, Map<Identifier, Collection<Holder<Item>>> tags, HolderLookup.Provider registries) {
        freezeReferences(files, tags, registries);
        return parse(files, registries);
    }

    private static JsonObject group(JsonObject input, String key) {
        return input.has(key) ? object(input.get(key), key) : new JsonObject();
    }

    private static RuleBook parse(JsonObject input, HolderLookup.Provider registries) {
        Map<Identifier, Grid.Quality> qualities = new HashMap<>();
        group(input, "qualities").asMap().forEach((key, data) ->
            qualities.put(resource(key, "qualities"), quality(object(data, key), key)));
        Map<Identifier, Storage> bags = new HashMap<>();
        Map<Identifier, Storage> cabinets = new HashMap<>();
        group(input, "backpacks").asMap().forEach((key, data) ->
            bags.put(resource(key, "backpacks"), storage(object(data, key), true, key)));
        group(input, "containers").asMap().forEach((key, data) ->
            cabinets.put(resource(key, "containers"), storage(object(data, key), false, key)));
        List<ItemRule> items = new ArrayList<>();
        group(input, "items").asMap().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String key = entry.getKey();
            JsonObject rule = object(entry.getValue(), key);
            JsonObject matcher = object(rule.get("item"), key + ".item");
            ItemPredicate predicate = ItemPredicate.CODEC.parse(registries.createSerializationContext(JsonOps.INSTANCE), matcher)
                .getOrThrow(reason -> invalid(key + ".item", reason));
            items.add(new ItemRule(resource(key, "items"), predicate, matcher, style(rule, qualities, key)));
        });
        return new RuleBook(input, bags, cabinets, items);
    }

    private static Storage storage(JsonObject data, boolean backpack, String path) {
        List<Grid.Section> sections = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        JsonArray entries = array(data, "sections", path);
        if (entries.isEmpty()) {
            throw invalid(path + ".sections", "分区不能为空");
        }
        for (int i = 0; i < entries.size(); i++) {
            String sectionPath = path + ".sections[" + i + "]";
            JsonObject section = object(entries.get(i), sectionPath);
            String id = string(section, "id", sectionPath);
            if (!ids.add(id)) {
                throw invalid(sectionPath + ".id", "重复分区 " + id);
            }
            try {
                sections.add(Grid.Section.fromJson(section));
            } catch (RuntimeException ex) {
                throw invalid(sectionPath, "shape 或 accepts_tag 无效，" + ex.getMessage());
            }
        }
        if (data.has("loot_table")) {
            resource(string(data, "loot_table", path), path + ".loot_table");
        }
        if (data.has("search")) {
            String field = path + ".search";
            JsonObject search = object(data.get("search"), field);
            if (search.has("mode") && !Set.of("per_item", "instant").contains(string(search, "mode", field))) {
                throw invalid(field + ".mode", "未知翻找模式");
            }
            integer(search, "ticks_per_item", 24, 1, Integer.MAX_VALUE, field);
        }
        if (data.has("access")) {
            access(object(data.get("access"), path + ".access"), path + ".access");
        }
        Grid.Style outer = backpack ? style(object(data.get("outer"), path + ".outer"), Map.of(), path + ".outer") : Grid.Style.DEFAULT;
        return new Storage(sections, outer, data);
    }

    private static Grid.Style style(JsonObject input, Map<Identifier, Grid.Quality> qualities, String path) {
        Shape shape = Shape.SINGLE;
        if (input.has("shape")) {
            try {
                shape = Grid.readShape(array(input, "shape", path));
            } catch (IllegalArgumentException ex) {
                throw invalid(path + ".shape", ex.getMessage());
            }
        }
        Optional<Grid.Quality> quality = Optional.empty();
        if (input.has("quality")) {
            String field = path + ".quality";
            JsonElement value = input.get("quality");
            if (value.isJsonObject()) {
                JsonObject inline = value.getAsJsonObject();
                String id = string(inline, "id", field);
                if (id.equals("disable")) {
                    quality = Optional.of(new Grid.Quality("", 0, true));
                } else {
                    resource(id, field + ".id");
                    quality = Optional.of(quality(inline, field));
                }
            } else {
                String id = string(input, "quality", path);
                if (id.equals("disable")) {
                    quality = Optional.of(new Grid.Quality("", 0, true));
                } else {
                    quality = Optional.ofNullable(qualities.get(resource(id, field)));
                    if (quality.isEmpty()) {
                        throw invalid(field, "找不到品质 " + id);
                    }
                }
            }
        }
        JsonObject icons = group(input, "icons");
        return new Grid.Style(shape, bool(input, "rotatable", false, path),
            integer(input, "grid_stack_limit", Integer.MAX_VALUE, 1, Integer.MAX_VALUE, path),
            icon(icons, "grid", path), icon(icons, "vanilla_slot", path), quality);
    }

    private static Optional<Identifier> icon(JsonObject icons, String key, String path) {
        return icons.has(key) ? Optional.of(resource(string(icons, key, path + ".icons"), path + ".icons." + key)) : Optional.empty();
    }

    private static Grid.Quality quality(JsonObject obj, String path) {
        String color = string(obj, "border_color", path);
        if (!color.matches("#[a-fA-F0-9]{6}")) {
            throw invalid(path + ".border_color", "颜色必须为 #RRGGBB");
        }
        return new Grid.Quality(string(obj, "name", path), Integer.parseInt(color.substring(1), 16), false);
    }

    private record ItemRule(Identifier id, ItemPredicate predicate, JsonObject matcher, Grid.Style style) {
        private boolean subsetOf(ItemRule other) {
            if (other.predicate.items().isPresent() && (predicate.items().isEmpty()
                || !predicate.items().get().stream().allMatch(other.predicate.items().get()::contains))) {
                return false;
            }
            return predicate.count().min().orElse(Integer.MIN_VALUE) >= other.predicate.count().min().orElse(Integer.MIN_VALUE)
                && predicate.count().max().orElse(Integer.MAX_VALUE) <= other.predicate.count().max().orElse(Integer.MAX_VALUE)
                && group(matcher, "components").entrySet().containsAll(group(other.matcher, "components").entrySet())
                && group(matcher, "predicates").entrySet().containsAll(group(other.matcher, "predicates").entrySet());
        }
    }

    private static void freezeReferences(JsonObject input, Map<Identifier, Collection<Holder<Item>>> tags,
                                         HolderLookup.Provider registries) {
        group(input, "items").asMap().forEach((key, element) -> {
            JsonObject matcher = object(object(element, key).get("item"), key + ".item");
            JsonElement items = matcher.get("items");
            if (items != null && items.isJsonPrimitive() && items.getAsString().startsWith("#")) {
                matcher.add("items", resolveTag(tags, items.getAsString().substring(1), key + ".item.items"));
            }
        });
        for (String category : List.of("backpacks", "containers")) {
            group(input, category).asMap().forEach((key, element) -> {
                JsonObject definition = object(element, key);
                JsonArray sections = array(definition, "sections", key);
                for (int i = 0; i < sections.size(); i++) {
                    String sectionPath = key + ".sections[" + i + "]";
                    JsonObject section = object(sections.get(i), sectionPath);
                    section.remove("_resolved_items");
                    if (section.has("accepts_tag")) {
                        String id = string(section, "accepts_tag", sectionPath);
                        if (id.startsWith("#")) {
                            id = id.substring(1);
                            section.addProperty("accepts_tag", id);
                        }
                        section.add("_resolved_items", resolveTag(tags, id, sectionPath + ".accepts_tag"));
                    }
                }
                if (definition.has("loot_table")) {
                    Identifier id = resource(string(definition, "loot_table", key), key + ".loot_table");
                    if (registries.lookupOrThrow(Registries.LOOT_TABLE).get(ResourceKey.create(Registries.LOOT_TABLE, id)).isEmpty()) {
                        throw invalid(key + ".loot_table", "找不到战利品表 " + id);
                    }
                }
                if (definition.has("access")) {
                    JsonObject access = object(definition.get("access"), key + ".access");
                    for (JsonElement route : array(access, "routes", key + ".access")) {
                        JsonObject entry = object(route, key + ".access.routes");
                        if (entry.has("requires")) {
                            for (JsonElement requirement : array(entry, "requires", key + ".access.routes")) {
                                String path = key + ".access.routes.requires";
                                JsonObject req = object(requirement, path);
                                String type = string(req, "type", path);
                                if (type.equals("held_item")) {
                                    Identifier id = resource(string(req, "item", path), path + ".item");
                                    if (registries.lookupOrThrow(Registries.ITEM).get(ResourceKey.create(Registries.ITEM, id)).isEmpty()) {
                                        throw invalid(path + ".item", "找不到物品 " + id);
                                    }
                                } else if (type.equals("held_item_tag")) {
                                    String id = string(req, "tag", path);
                                    req.add("_resolved_items", resolveTag(tags, id.startsWith("#") ? id.substring(1) : id, path + ".tag"));
                                }
                            }
                        }
                    }
                }
            });
        }
    }

    private static JsonArray resolveTag(Map<Identifier, Collection<Holder<Item>>> tags, String id, String path) {
        Collection<Holder<Item>> items = tags.get(resource(id, path));
        if (items == null) {
            throw invalid(path, "找不到物品标签 " + id);
        }
        JsonArray values = new JsonArray();
        items.stream().map(holder -> holder.unwrapKey().orElseThrow().identifier().toString()).sorted().forEach(values::add);
        return values;
    }
}
