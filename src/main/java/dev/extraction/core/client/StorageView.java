package dev.extraction.core.client;

import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.nbt.NbtOps;
import dev.extraction.core.rules.Shape;
import net.minecraft.core.UUIDUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

final class StorageView {
    static final int CELL = 18;
    private static final int TOP = 43;
    private static final Identifier LENS = Identifier.fromNamespaceAndPath("extractioncombatcore", "textures/gui/search_lens.png");
    private final List<Section> sections = new ArrayList<>();
    private final List<CompoundTag> packs;
    private final boolean cabinetOpen;
    private int contentWidth;
    private int contentHeight;
    private int offsetX;
    private int offsetY;
    private int height;
    private int width;
    private int packX;
    private int packWidth;
    private int selectedPack = -1;

    record Entry(UUID id, ItemStack stack, Shape shape, Shape placed, boolean rotatable, String gridIcon, String slotIcon,
                 String quality, int color, int x, int y, int rotation, int slot, boolean recovery) {
        boolean occupies(int ix, int iy) {
            return placed.contains(ix - x, iy - y);
        }
    }

    private static class Section {
        final String panel;
        final String label;
        final int n;
        final Shape shape;
        final boolean complete;
        final boolean searching;
        final String accepts;
        Set<String> acceptedItems;
        final List<Entry> entries = new ArrayList<>();
        int x;
        int y;

        Section(String panel, String label, int n, Shape shape, boolean complete, boolean searching, String accepts) {
            this.panel = panel;
            this.label = label;
            this.n = n;
            this.shape = shape;
            this.complete = complete;
            this.searching = searching;
            this.accepts = accepts;
        }

        Entry at(int ix, int iy) {
            for (Entry entry : entries) {
                if (entry.occupies(ix, iy)) {
                    return entry;
                }
            }
            return null;
        }
    }

    record Hit(Section section, Entry entry, int cellX, int cellY, int slot) {
        String panel() {
            return section.panel;
        }

        int centerX() {
            return section.x + cellX * CELL + CELL / 2;
        }

        int centerY() {
            return section.y + cellY * CELL + CELL / 2;
        }

        int sectionIndex() {
            return section.n;
        }

        boolean vanilla() {
            return section.panel.equals("vanilla");
        }
    }

    static final class Drag {
        final Hit source;
        final ItemIcons.Cell grip;
        int rotation;

        Drag(Hit hit) {
            source = hit;
            Entry e = hit.entry();
            rotation = e.rotation();
            if (hit.vanilla()) {
                int n = 0;
                while (!e.shape().contains(n % e.shape().width(), n / e.shape().width())) {
                    n++;
                }
                grip = new ItemIcons.Cell(n % e.shape().width(), n / e.shape().width());
            } else {
                grip = ItemIcons.Cell.turn(hit.cellX() - e.x(), hit.cellY() - e.y(), e.placed(), -rotation);
            }
        }
    }

    private record Target(Section section, int x, int y, int slot) {
    }

    StorageView(CompoundTag view, StorageView old) {
        packs = compounds(view, "packs");
        cabinetOpen = view.contains("cabinet") && !compounds(view.getCompoundOrEmpty("cabinet"), "sections").isEmpty();
        for (int i = 0; i < packs.size(); i++) {
            if (packs.get(i).getBooleanOr("selected", false)) {
                selectedPack = i;
                break;
            }
        }
        addGrid("bag", view.getCompoundOrEmpty("bag"));
        if (cabinetOpen) {
            addGrid("cabinet", view.getCompoundOrEmpty("cabinet"));
        }
        addVanilla(compounds(view, "vanilla"));
        if (old != null) {
            offsetX = old.offsetX;
            offsetY = old.offsetY;
            if (old.width > 0 && old.height > 0) {
                layout(old.width, old.height);
            }
        }
    }

    static List<CompoundTag> compounds(CompoundTag tag, String key) {
        ListTag list = tag.getListOrEmpty(key);
        List<CompoundTag> values = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            values.add(list.getCompoundOrEmpty(i));
        }
        return values;
    }

    private static Shape shape(CompoundTag tag) {
        ListTag list = tag.getListOrEmpty("shape");
        if (list.isEmpty()) {
            return Shape.SINGLE;
        }
        List<String> rows = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            rows.add(list.getStringOr(i, ""));
        }
        return Shape.of(rows);
    }

    private static Entry entry(CompoundTag e, int x, int y, int slot) {
        ItemStack stack = e.read("stack", ItemStack.OPTIONAL_CODEC, Minecraft.getInstance().player.registryAccess()
            .createSerializationContext(NbtOps.INSTANCE)).orElse(ItemStack.EMPTY);
        Shape shape = shape(e);
        int rotation = Math.floorMod(e.getIntOr("rotation", 0), 4);
        return new Entry(e.read("id", UUIDUtil.CODEC).orElse(null), stack, shape, slot >= 0 ? Shape.SINGLE : shape.rotate(rotation),
            e.getBooleanOr("rotatable", false), e.getStringOr("grid_icon", ""), e.getStringOr("slot_icon", ""), e.getStringOr("quality", ""),
            0xff000000 | e.getIntOr("color", 0), x, y, rotation, slot, e.getBooleanOr("recovery", false));
    }

    private void addGrid(String panel, CompoundTag data) {
        List<Section> here = new ArrayList<>();
        int n = 0;
        for (CompoundTag source : compounds(data, "sections")) {
            Section s = new Section(panel, source.getStringOr("id", ""), n, shape(source), source.getBooleanOr("done", false),
                data.contains("searching") && data.getIntOr("searching", 0) == n, source.getStringOr("accepts_tag", ""));
            if (source.contains("accepted_items")) {
                ListTag ids = source.getListOrEmpty("accepted_items");
                s.acceptedItems = new HashSet<>();
                for (int i = 0; i < ids.size(); i++) {
                    s.acceptedItems.add(ids.getStringOr(i, ""));
                }
            }
            here.add(s);
            sections.add(s);
            n++;
        }
        for (CompoundTag e : compounds(data, "items")) {
            int index = e.getIntOr("section", 0);
            if (index >= 0 && index < here.size()) {
                here.get(index).entries.add(entry(e, e.getIntOr("x", 0), e.getIntOr("y", 0), -1));
            }
        }
    }

    private void addVanilla(List<CompoundTag> slots) {
        if (slots.isEmpty()) {
            return;
        }
        Section bar = new Section("vanilla", "hotbar", 0, Shape.of(List.of("XXXXXXXXX")), true, false, "");
        Section main = new Section("vanilla", "inventory", 1, Shape.of(List.of("XXXXXXXXX", "XXXXXXXXX", "XXXXXXXXX")), true, false, "");
        Section extra = new Section("vanilla", "equipment", 2, Shape.of(List.of("XX")), true, false, "");
        for (CompoundTag e : slots) {
            int slot = e.getIntOr("slot", 0);
            if (slot < 0 || slot > 41 || slot >= 36 && slot < 40) {
                continue;
            }
            Section group = slot < 9 ? bar : slot < 36 ? main : extra;
            int i = slot < 9 ? slot : slot < 36 ? slot - 9 : slot - 40;
            group.entries.add(entry(e, i % group.shape.width(), i / group.shape.width(), slot));
        }
        for (Section group : List.of(bar, main, extra)) {
            if (!group.entries.isEmpty()) {
                sections.add(group);
            }
        }
    }

    private boolean left(Section s) {
        return s.panel.equals("bag") || cabinetOpen && s.panel.equals("vanilla");
    }

    private void layout(int w, int h) {
        width = w;
        height = h;
        int leftW = Math.max(78, (w - 30) / 2);
        int rightW = 72;
        for (Section s : sections) {
            if (left(s)) {
                leftW = Math.max(leftW, s.shape.width() * CELL);
            } else {
                rightW = Math.max(rightW, s.shape.width() * CELL);
            }
        }
        int l = TOP + 4;
        int r = TOP + 4;
        for (Section s : sections) {
            boolean left = left(s);
            s.x = left ? 10 : 21 + leftW;
            s.y = (left ? l : r) + 12;
            int bottom = s.y + s.shape.height() * CELL + 9;
            if (left) {
                l = bottom;
            } else {
                r = bottom;
            }
        }
        contentHeight = Math.max(l, r);
        contentWidth = 30 + leftW + rightW;
        clampScroll();
        packX = 9;
        packWidth = Math.min(leftW, Math.max(62, w / 2 - 18));
        for (Section s : sections) {
            s.x -= offsetX;
            s.y -= offsetY;
        }
    }

    private void clampScroll() {
        offsetY = Mth.clamp(offsetY, 0, Math.max(0, contentHeight - height + 24));
        offsetX = Mth.clamp(offsetX, 0, Math.max(0, contentWidth - width));
    }

    void scroll(double horizontal, double vertical, boolean sideways) {
        offsetX -= (int) Math.round((horizontal + (sideways ? vertical : 0)) * 18);
        if (!sideways) {
            offsetY -= (int) Math.round(vertical * 18);
        }
        clampScroll();
        layout(width, height);
    }

    String equipSelection() {
        return selectedPack < 0 ? "" : packs.get(selectedPack).getStringOr("name", "");
    }

    int nextPack(int mx, int my) {
        if (my >= 22 && my < 40 && mx >= packX && mx < packX + packWidth && !packs.isEmpty()
            && (packs.size() > 1 || selectedPack < 0)) {
            int next = selectedPack < 0 ? 0 : Math.floorMod(selectedPack + (mx < packX + 16 ? -1 : 1), packs.size());
            return packs.get(next).getIntOr("slot", 0);
        }
        return -1;
    }

    void draw(GuiGraphicsExtractor g, Font font, int w, int h, int mx, int my, long ticks, Drag dragged, boolean moving) {
        layout(w, h);
        g.fill(7, 23, 7 + packWidth, 40, 0xff263d39);
        String name = equipSelection();
        String pack = name.isEmpty() ? LootScreen.tr(packs.isEmpty() ? "no_pack" : "select_pack").getString() : name;
        String navigation = packs.size() > 1 ? "‹ " + pack + " ›" : pack;
        g.text(font, font.plainSubstrByWidth(navigation, packWidth - 8), 11, 27, 0xffbcdfc7, false);
        g.text(font, LootScreen.tr(cabinetOpen ? "cabinet" : "inventory"), Math.max(w / 2, packWidth + 13), 27, 0xffd7e3dd, false);
        g.enableScissor(4, TOP, w - 4, h - 21);
        for (Section s : sections) {
            if (s.y + s.shape.height() * CELL <= TOP || s.y - 12 > h - 21) {
                continue;
            }
            String label = s.panel.equals("vanilla") ? LootScreen.tr(s.label).getString()
                : Component.translatableWithFallback("gui.extractioncombatcore.section." + s.label, s.label).getString();
            if (s.panel.equals("cabinet") && s.complete && s.entries.isEmpty()) {
                label = LootScreen.tr("empty_section", label).getString();
            }
            g.text(font, label, s.x, s.y - 11, 0xffa7b7ad, false);
            for (int y = 0; y < s.shape.height(); y++) {
                for (int x = 0; x < s.shape.width(); x++) {
                    if (!s.shape.contains(x, y)) {
                        continue;
                    }
                    int px = s.x + x * CELL;
                    int py = s.y + y * CELL;
                    Entry e = s.at(x, y);
                    if (s.panel.equals("vanilla") && e == null) {
                        continue;
                    }
                    boolean unknown = !s.complete && e == null;
                    boolean locked = s.panel.equals("vanilla") && ExtractionClient.restricted(e.slot());
                    g.fill(px, py, px + CELL, py + CELL, unknown ? 0xff56595b : 0xff43514b);
                    g.fill(px + 1, py + 1, px + CELL, py + CELL,
                        unknown ? 0xff474a4c : locked ? 0xff303031 : ((x + y) % 2 == 0 ? 0xff26332e : 0xff293731));
                    if (locked) {
                        g.fill(px + 13, py + 2, px + 16, py + 4, 0xffce9b66);
                    }
                }
            }
            for (Entry e : s.entries) {
                if (e.stack().isEmpty() || moving && dragged != null && matches(dragged.source, e, s)) {
                    continue;
                }
                int x = s.x + e.x() * CELL;
                int y = s.y + e.y() * CELL;
                if (s.panel.equals("vanilla")) {
                    if (!ItemIcons.slot(g, e.slotIcon(), x + 1, y + 1)) {
                        g.item(e.stack(), x + 1, y + 1);
                    }
                    g.itemDecorations(font, e.stack(), x + 1, y + 1);
                } else {
                    drawGridEntry(g, font, e, x, y, e.rotation());
                }
            }
            if (s.searching) {
                int span = Math.max(1, s.shape.width() * CELL - 16);
                int walk = (int) Math.floorMod(ticks, span * 2L);
                int gx = s.x + Math.min(walk, span * 2 - walk);
                int gy = s.y + (s.shape.height() * CELL - 16) / 2;
                g.blit(RenderPipelines.GUI_TEXTURED, LENS, gx, gy, 0f, 0f, 16, 16, 16, 16);
            }
        }
        if (moving && dragged != null) {
            drawDrag(g, font, dragged, mx, my);
        }
        g.disableScissor();
        if (contentHeight > h - 24) {
            int track = h - TOP - 25;
            int size = Math.max(9, track * track / Math.max(1, contentHeight - TOP));
            int pos = TOP + (track - size) * offsetY / Math.max(1, contentHeight - h + 24);
            g.fill(w - 4, pos, w - 2, pos + size, 0xff769689);
        }
        if (contentWidth > w) {
            int track = w - 12;
            int size = Math.max(9, track * w / contentWidth);
            int pos = 6 + (track - size) * offsetX / (contentWidth - w);
            g.fill(pos, h - 24, pos + size, h - 22, 0xff769689);
        }
    }

    private static boolean matches(Hit hit, Entry e, Section s) {
        return hit.section().panel.equals(s.panel)
            && (e.id() == null ? hit.slot() == e.slot() : e.id().equals(hit.entry().id()));
    }

    private static void drawGridEntry(GuiGraphicsExtractor g, Font font, Entry e, int x, int y, int rotation) {
        ItemIcons.grid(g, e, x, y, rotation);
        Shape footprint = rotation == e.rotation() && e.slot() < 0 ? e.placed() : e.shape().rotate(rotation);
        int finalX = 0;
        int finalY = 0;
        for (int row = 0; row < footprint.height(); row++) {
            for (int col = 0; col < footprint.width(); col++) {
                if (!footprint.contains(col, row)) {
                    continue;
                }
                int a = x + CELL * col;
                int b = y + CELL * row;
                finalX = a;
                finalY = b;
                if (!e.quality().isEmpty()) {
                    if (!footprint.contains(col - 1, row)) {
                        g.fill(a, b, a + 1, b + CELL, e.color());
                    }
                    if (!footprint.contains(col + 1, row)) {
                        g.fill(a + CELL - 1, b, a + CELL, b + CELL, e.color());
                    }
                    if (!footprint.contains(col, row - 1)) {
                        g.fill(a, b, a + CELL, b + 1, e.color());
                    }
                    if (!footprint.contains(col, row + 1)) {
                        g.fill(a, b + CELL - 1, a + CELL, b + CELL, e.color());
                    }
                }
            }
        }
        g.itemDecorations(font, e.stack(), finalX + 1, finalY + 1);
    }

    Hit hit(int mx, int my) {
        if (my < TOP || my >= height - 21 || mx < 4 || mx >= width - 4) {
            return null;
        }
        for (Section s : sections) {
            int col = Math.floorDiv(mx - s.x, CELL);
            int row = Math.floorDiv(my - s.y, CELL);
            if (s.shape.contains(col, row)) {
                Entry e = s.at(col, row);
                if (e == null && s.panel.equals("vanilla")) {
                    continue;
                }
                return new Hit(s, e, col, row, e == null ? -1 : e.slot());
            }
        }
        return null;
    }

    void tooltip(GuiGraphicsExtractor g, Font font, int mx, int my) {
        Hit hit = hit(mx, my);
        if (hit == null || hit.entry() == null) {
            return;
        }
        Entry e = hit.entry();
        if (e.stack().isEmpty()) {
            if (e.slot() == 41) {
                g.setTooltipForNextFrame(font, LootScreen.tr("equipped_pack"), mx, my);
            }
            return;
        }
        List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(Minecraft.getInstance(), e.stack()));
        if (!e.quality().isEmpty()) {
            lines.add(Component.translatableWithFallback(e.quality(), e.quality()).withColor(e.color() & 0xffffff));
        }
        if (e.recovery()) {
            lines.add(LootScreen.tr("recover"));
        } else if (hit.vanilla() && (e.slot() == 41 || packs.stream().anyMatch(p -> p.getIntOr("slot", 0) == e.slot()))) {
            lines.add(LootScreen.tr("equip_hint"));
        }
        g.setComponentTooltipForNextFrame(font, lines, mx, my);
    }

    int equipTarget(Hit hit) {
        if (hit.slot() == 41) {
            for (Section section : sections) {
                if (section.panel.equals("vanilla")) {
                    for (Entry e : section.entries) {
                        if (e.slot() != 41 && !e.recovery() && e.stack().isEmpty()) {
                            return e.slot();
                        }
                    }
                }
            }
            return -1;
        }
        if (packs.stream().anyMatch(p -> p.getIntOr("slot", 0) == hit.slot())
            || hit.entry().stack().isEmpty() && packs.stream().anyMatch(p -> p.getIntOr("slot", 0) == 41)) {
            return hit.slot();
        }
        return -1;
    }

    static void source(CompoundTag action, Hit hit) {
        action.putString("from", hit.section().panel);
        if (hit.vanilla()) {
            action.putInt("slot", hit.slot());
        } else {
            action.store("id", UUIDUtil.CODEC, hit.entry().id());
        }
    }

    boolean destination(CompoundTag action, Drag drag, int mx, int my) {
        Target to = target(drag, mx, my);
        if (to == null || !to.section.complete) {
            return false;
        }
        action.putString("to", to.section.panel);
        action.putInt("dest_slot", to.slot);
        action.putInt("section", to.section.n);
        action.putInt("x", to.x);
        action.putInt("y", to.y);
        action.putInt("rotation", drag.rotation);
        return true;
    }

    private Target target(Drag drag, int mx, int my) {
        Hit hit = hit(mx, my);
        if (hit == null && my >= TOP && my < height - 21 && mx >= 4 && mx < width - 4) {
            for (Section s : sections) {
                if (s.panel.equals("vanilla")) {
                    continue;
                }
                int col = Math.floorDiv(mx - s.x, CELL);
                int row = Math.floorDiv(my - s.y, CELL);
                if (col >= 0 && row >= 0 && col < s.shape.width() && row < s.shape.height()) {
                    hit = new Hit(s, null, col, row, -1);
                    break;
                }
            }
        }
        if (hit == null) {
            return null;
        }
        Section s = hit.section;
        if (hit.vanilla()) {
            return new Target(s, hit.cellX(), hit.cellY(), hit.slot());
        }
        double u = mx - s.x - CELL * hit.cellX();
        double v = my - s.y - CELL * hit.cellY();
        if (drag.source.entry().rotatable()) {
            double[] edges = {v, CELL - u, CELL - v, u};
            double near = Math.min(Math.min(edges[0], edges[1]), Math.min(edges[2], edges[3]));
            if (near <= CELL / 4d) {
                int count = 0;
                int direction = 0;
                for (int i = 0; i < 4; i++) {
                    if (edges[i] == near) {
                        direction = i;
                        count++;
                    }
                }
                if (count == 1) {
                    drag.rotation = direction;
                }
            }
        }
        ItemIcons.Cell anchor = ItemIcons.Cell.turn(drag.grip.x(), drag.grip.y(), drag.source.entry().shape(), drag.rotation);
        return new Target(s, hit.cellX() - anchor.x(), hit.cellY() - anchor.y(), -1);
    }

    private void drawDrag(GuiGraphicsExtractor g, Font font, Drag drag, int mx, int my) {
        Entry e = drag.source.entry();
        Target to = target(drag, mx, my);
        if (to == null) {
            ItemIcons.Cell anchor = ItemIcons.Cell.turn(drag.grip.x(), drag.grip.y(), e.shape(), drag.rotation);
            drawGridEntry(g, font, e, mx - anchor.x() * CELL - CELL / 2, my - anchor.y() * CELL - CELL / 2, drag.rotation);
            return;
        }
        Section s = to.section;
        int px = s.x + to.x * CELL;
        int py = s.y + to.y * CELL;
        if (to.slot >= 0) {
            if (!ItemIcons.slot(g, e.slotIcon(), px + 1, py + 1)) {
                g.item(e.stack(), px + 1, py + 1);
            }
            g.outline(px, py, CELL, CELL, ExtractionClient.restricted(to.slot) ? 0xffd56a5a : 0xff8bd49a);
            return;
        }
        Shape footprint = e.shape().rotate(drag.rotation);
        drawGridEntry(g, font, e, px, py, drag.rotation);
        boolean accepted = s.acceptedItems == null
            ? s.accepts.isEmpty() || e.stack().is(TagKey.create(Registries.ITEM, Identifier.parse(s.accepts)))
            : s.acceptedItems.contains(BuiltInRegistries.ITEM.getKey(e.stack().getItem()).toString());
        for (int row = 0; row < footprint.height(); row++) {
            for (int col = 0; col < footprint.width(); col++) {
                if (!footprint.contains(col, row)) {
                    continue;
                }
                int x = to.x + col;
                int y = to.y + row;
                Entry occupant = s.at(x, y);
                boolean valid = accepted && s.complete && s.shape.contains(x, y)
                    && (occupant == null || matches(drag.source, occupant, s));
                g.fill(px + col * CELL + 1, py + row * CELL + 1, px + (col + 1) * CELL, py + (row + 1) * CELL,
                    valid ? 0x6862b880 : 0xb9bb4c46);
            }
        }
    }
}
