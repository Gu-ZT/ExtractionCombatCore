package dev.extraction.core.rules;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntPredicate;

/** 在副本上排布物品。调用方验证库存版本后再提交返回的完整布局。 */
public final class Grid {
    private Grid() {}

    public record Quality(String name, int color, boolean disabled) {}

    public record Style(Shape shape, boolean rotatable, int stackLimit, Optional<Identifier> gridIcon,
                        Optional<Identifier> slotIcon, Optional<Quality> quality) {
        public static final Style DEFAULT = new Style(Shape.SINGLE, false, Integer.MAX_VALUE,
            Optional.empty(), Optional.empty(), Optional.empty());

        public Style {
            if (stackLimit < 1) {
                throw new IllegalArgumentException("grid_stack_limit 必须为正整数");
            }
        }

        public JsonObject toJson() {
            JsonObject out = new JsonObject();
            out.add("shape", rows(shape));
            out.addProperty("rotatable", rotatable);
            out.addProperty("grid_stack_limit", stackLimit);
            gridIcon.ifPresent(id -> out.addProperty("grid", id.toString()));
            slotIcon.ifPresent(id -> out.addProperty("slot", id.toString()));
            quality.ifPresent(value -> {
                JsonObject q = new JsonObject();
                q.addProperty("name", value.name);
                q.addProperty("color", value.color);
                q.addProperty("disabled", value.disabled);
                out.add("quality", q);
            });
            return out;
        }

        public static Style fromJson(JsonObject json) {
            Optional<Quality> quality = Optional.ofNullable(json.getAsJsonObject("quality")).map(q ->
                new Quality(q.get("name").getAsString(), q.get("color").getAsInt(), q.get("disabled").getAsBoolean()));
            return new Style(readShape(json.getAsJsonArray("shape")), json.get("rotatable").getAsBoolean(),
                json.get("grid_stack_limit").getAsInt(), id(json, "grid"), id(json, "slot"), quality);
        }
    }

    public record Section(String id, Shape shape, Optional<Identifier> acceptsTag, Optional<Set<Identifier>> acceptedItems) {
        public Section(String id, Shape shape, Optional<Identifier> acceptsTag) {
            this(id, shape, acceptsTag, Optional.empty());
        }

        public boolean accepts(ItemStack stack) {
            if (acceptedItems.isPresent()) {
                return acceptedItems.get().contains(BuiltInRegistries.ITEM.getKey(stack.getItem()));
            }
            return acceptsTag.isEmpty() || stack.is(TagKey.create(Registries.ITEM, acceptsTag.get()));
        }

        public JsonObject toJson() {
            JsonObject out = new JsonObject();
            out.addProperty("id", id);
            out.add("shape", rows(shape));
            acceptsTag.ifPresent(tag -> out.addProperty("accepts_tag", tag.toString()));
            acceptedItems.ifPresent(ids -> {
                JsonArray values = new JsonArray();
                ids.stream().sorted().forEach(item -> values.add(item.toString()));
                out.add("_resolved_items", values);
            });
            return out;
        }

        public static Section fromJson(JsonObject json) {
            Optional<Set<Identifier>> resolved = Optional.ofNullable(json.getAsJsonArray("_resolved_items")).map(array -> {
                Set<Identifier> ids = new LinkedHashSet<>();
                for (JsonElement element : array) {
                    ids.add(Identifier.parse(element.getAsString()));
                }
                return Set.copyOf(ids);
            });
            return new Section(json.get("id").getAsString(), readShape(json.getAsJsonArray("shape")), Grid.id(json, "accepts_tag"), resolved);
        }
    }

    public record Entry(UUID id, ItemStack stack, Style style, int section, int x, int y, int rotation) {
        public Entry at(int section, int x, int y, int rotation) {
            return new Entry(id, stack, style, section, x, y, rotation);
        }

        public Shape shape() {
            return style.shape.rotate(rotation);
        }

        private int limit() {
            return Math.min(style.stackLimit(), stack.getMaxStackSize());
        }

        private Entry withCount(int count, UUID stackId) {
            return new Entry(stackId, stack.copyWithCount(count), style, section, x, y, rotation);
        }
    }

    private static JsonArray rows(Shape shape) {
        JsonArray rows = new JsonArray();
        shape.rows().forEach(rows::add);
        return rows;
    }

    static Shape readShape(JsonArray array) {
        List<String> rows = new ArrayList<>();
        for (JsonElement row : array) {
            if (!row.isJsonPrimitive() || !row.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("shape 的每行必须是字符串");
            }
            rows.add(row.getAsString());
        }
        return Shape.of(rows);
    }

    static Optional<Identifier> id(JsonObject obj, String key) {
        return Optional.ofNullable(obj.get(key)).map(value -> Identifier.parse(value.getAsString()));
    }

    /** 检查目标位置；调用方应从占用表中移除正在拖动的原件。 */
    public static boolean fits(List<Section> sections, List<Entry> occupied, Entry item, IntPredicate allowed) {
        if (item.section < 0 || item.section >= sections.size() || !allowed.test(item.section)
            || item.rotation < 0 || item.rotation > 3 || (!item.style.rotatable && item.rotation != 0)) {
            return false;
        }
        Section section = sections.get(item.section);
        if (!section.accepts(item.stack)) {
            return false;
        }
        Shape shape = item.shape();
        for (int dy = 0; dy < shape.height(); dy++) {
            for (int dx = 0; dx < shape.width(); dx++) {
                if (shape.contains(dx, dy) && !section.shape.contains(item.x + dx, item.y + dy)) {
                    return false;
                }
            }
        }
        for (Entry other : occupied) {
            if (item.section == other.section && overlaps(item.x, item.y, shape, other.x, other.y, other.shape())) {
                return false;
            }
        }
        return true;
    }

    static boolean overlaps(int x, int y, Shape shape, int ox, int oy, Shape other) {
        for (int row = Math.max(y, oy); row < Math.min(y + shape.height(), oy + other.height()); row++) {
            for (int col = Math.max(x, ox); col < Math.min(x + shape.width(), ox + other.width()); col++) {
                if (shape.contains(col - x, row - y) && other.contains(col - ox, row - oy)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 按分区、朝向及行列顺序尝试直接放入；失败时原始物品和布局保持完整。 */
    public static Optional<List<Entry>> direct(List<Section> sections, List<Entry> existing, Entry incoming, IntPredicate allowed) {
        List<Entry> placed = new ArrayList<>(existing);
        List<Entry> pieces = mergeAndSplit(sections, placed, incoming, allowed);
        return placePieces(sections, placed, pieces, allowed);
    }

    /** 新生成的战利品保持独立发现记录，不能并入玩家已经揭示的物品栈。 */
    public static Optional<List<Entry>> directNew(List<Section> sections, List<Entry> existing, Entry incoming, IntPredicate allowed) {
        List<Entry> placed = new ArrayList<>(existing);
        return placePieces(sections, placed, split(incoming, incoming.stack.getCount()), allowed);
    }

    private static Optional<List<Entry>> placePieces(List<Section> sections, List<Entry> placed, List<Entry> pieces, IntPredicate allowed) {
        for (Entry piece : pieces) {
            Positions positions = new Positions(sections, piece, false, allowed);
            Entry position;
            do {
                position = positions.next();
                if (position == null) {
                    return Optional.empty();
                }
            } while (!fits(sections, placed, position, allowed));
            placed.add(position);
        }
        return Optional.of(List.copyOf(placed));
    }

    private static List<Entry> mergeAndSplit(List<Section> sections, List<Entry> placed, Entry incoming, IntPredicate allowed) {
        int count = incoming.stack.getCount();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < placed.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingInt((Integer i) -> placed.get(i).section)
            .thenComparingInt(i -> placed.get(i).y).thenComparingInt(i -> placed.get(i).x));
        for (int index : order) {
            if (count <= 0) {
                break;
            }
            Entry other = placed.get(index);
            if (!allowed.test(other.section) || !sections.get(other.section).accepts(incoming.stack)
                || !ItemStack.isSameItemSameComponents(other.stack, incoming.stack)) {
                continue;
            }
            int moved = Math.min(Math.max(0, other.limit() - other.stack.getCount()), count);
            if (moved > 0) {
                placed.set(index, other.withCount(other.stack.getCount() + moved, other.id));
                count -= moved;
            }
        }
        return split(incoming, count);
    }

    private static List<Entry> split(Entry incoming, int count) {
        List<Entry> pieces = new ArrayList<>();
        while (count > 0) {
            int moved = Math.min(count, incoming.limit());
            pieces.add(incoming.withCount(moved, pieces.isEmpty() ? incoming.id : UUID.randomUUID()));
            count -= moved;
        }
        return pieces;
    }

    /** 建立可取消的排布搜索；丢弃搜索对象即可取消。 */
    public static Search repack(List<Section> sections, List<Entry> existing, Entry incoming, IntPredicate allowed) {
        return new Search(sections, existing, incoming, allowed);
    }

    public enum Status { RUNNING, FOUND, IMPOSSIBLE }

    public static final class Search {
        private final List<Section> sections;
        private final IntPredicate allowed;
        private final List<Entry> placed = new ArrayList<>();
        private final List<Entry> pieces = new ArrayList<>();
        private final List<Positions> levels = new ArrayList<>();
        private final Set<UUID> existingIds;
        private int depth;
        private Status status;

        private Search(List<Section> sections, List<Entry> existing, Entry incoming, IntPredicate allowed) {
            this.sections = List.copyOf(sections);
            this.allowed = allowed;
            List<Entry> merged = new ArrayList<>();
            for (Entry entry : existing) {
                if (allowed.test(entry.section)) {
                    merged.addAll(mergeAndSplit(sections, merged, entry, allowed));
                } else {
                    merged.add(entry);
                }
            }
            List<Entry> additions = mergeAndSplit(sections, merged, incoming, allowed);
            existingIds = new LinkedHashSet<>();
            for (Entry entry : merged) {
                if (allowed.test(entry.section)) {
                    pieces.add(entry);
                    existingIds.add(entry.id);
                } else {
                    placed.add(entry);
                }
            }
            pieces.addAll(additions);
            pieces.sort(Comparator.comparingInt((Entry entry) -> entry.style.shape.area()).reversed());
            long available = 0;
            for (int i = 0; i < sections.size(); i++) {
                if (allowed.test(i)) {
                    available += sections.get(i).shape.area();
                }
            }
            long required = pieces.stream().mapToLong(entry -> entry.style.shape.area()).sum();
            status = required > available ? Status.IMPOSSIBLE : (pieces.isEmpty() ? Status.FOUND : Status.RUNNING);
        }

        public Status step(int budget) {
            for (int i = 0; i < budget && status == Status.RUNNING; i++) {
                if (levels.size() == depth) {
                    Entry piece = pieces.get(depth);
                    levels.add(new Positions(sections, piece, existingIds.contains(piece.id), allowed));
                }
                Entry candidate = levels.get(depth).next();
                if (candidate == null) {
                    levels.removeLast();
                    if (depth == 0) {
                        status = Status.IMPOSSIBLE;
                    } else {
                        placed.removeLast();
                        depth--;
                    }
                } else if (fits(sections, placed, candidate, allowed)) {
                    placed.add(candidate);
                    depth++;
                    if (depth == pieces.size()) {
                        status = Status.FOUND;
                    }
                }
            }
            return status;
        }

        public List<Entry> result() {
            if (status != Status.FOUND) {
                throw new IllegalStateException("排布尚未找到可用结果");
            }
            return List.copyOf(placed);
        }
    }

    private static final class Positions {
        private final Entry item;
        private final List<Section> sections;
        private final List<Integer> order = new ArrayList<>();
        private final List<Integer> rotations = new ArrayList<>();
        private final List<Shape> shapes = new ArrayList<>();
        private boolean tryOriginal;
        private boolean firstInRotation = true;
        private int sectionIndex;
        private int rotationIndex;
        private int x;
        private int y;

        private Positions(List<Section> sections, Entry item, boolean keepPosition, IntPredicate allowed) {
            this.item = item;
            this.sections = sections;
            tryOriginal = keepPosition;
            if (keepPosition) {
                order.add(item.section);
            }
            for (int i = 0; i < sections.size(); i++) {
                if ((!keepPosition || i != item.section) && allowed.test(i) && sections.get(i).accepts(item.stack)) {
                    order.add(i);
                }
            }
            int turns = item.style.rotatable ? 4 : 1;
            for (int i = 0; i < turns; i++) {
                int rotation = item.style.rotatable ? Math.floorMod(item.rotation + i, 4) : 0;
                Shape rotated = item.style.shape.rotate(rotation);
                if (!shapes.contains(rotated)) {
                    rotations.add(rotation);
                    shapes.add(rotated);
                }
            }
        }

        private Entry next() {
            if (tryOriginal) {
                tryOriginal = false;
                return item;
            }
            while (sectionIndex < order.size()) {
                int section = order.get(sectionIndex);
                Shape board = sections.get(section).shape;
                Shape shape = shapes.get(rotationIndex);
                if (firstInRotation) {
                    x = -shape.left();
                    y = -shape.top();
                    firstInRotation = false;
                }
                if (y >= board.height() - shape.bottom() || x >= board.width() - shape.right()) {
                    firstInRotation = true;
                    rotationIndex++;
                    if (rotationIndex == rotations.size()) {
                        rotationIndex = 0;
                        sectionIndex++;
                    }
                    continue;
                }
                Entry candidate = item.at(section, x, y, rotations.get(rotationIndex));
                x++;
                if (x >= board.width() - shape.right()) {
                    x = -shape.left();
                    y++;
                }
                return candidate;
            }
            return null;
        }
    }
}
