package dev.extraction.core.server;

import dev.extraction.core.GameAccess;
import dev.extraction.core.network.ExtractionNetwork;
import dev.extraction.core.rules.Grid;
import dev.extraction.core.rules.RuleBook;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntPredicate;

final class LootSession {
    record Source(String area, int slot, StoredGrid grid, Grid.Entry entry) {}
    record Sorting(Source source, StoredGrid target, Grid.Search search, long revision) {}

    final ServerPlayer player;
    final UUID id = UUID.randomUUID();
    final BlockPos position;
    final CabinetStorage block;
    final StoredGrid cabinet;
    StoredGrid bag;
    ItemStack bagStack = ItemStack.EMPTY;
    int bagSlot = -1;
    private final Map<Integer, ItemStack> vanillaSnapshot = new HashMap<>();
    private GameAccess.InventoryPolicy inventoryPolicy = GameAccess.serverPolicy();
    private long bagRevision = -1;
    private long cabinetRevision = -1;
    private long revision;
    private AccessChallenge challenge;
    private int elapsed;
    private int searchSection = -1;
    private Sorting sorting;
    private String message = "";

    LootSession(ServerPlayer player, BlockPos position, CabinetStorage block, StoredGrid cabinet) {
        this.player = player;
        this.position = position;
        this.block = block;
        this.cabinet = cabinet;
        track();
    }

    static boolean vanillaSlot(int slot) {
        return slot >= 0 && slot < 36 || slot == 40 || slot == GameAccess.EQUIPMENT_SLOT;
    }

    boolean valid() {
        if (!player.isAlive() || player.isSpectator() || player.containerMenu != player.inventoryMenu) {
            return false;
        }
        if (block == null) {
            return true;
        }
        double reach = player.blockInteractionRange() + 1;
        return player.serverLevel().getBlockEntity(position) == block
            && player.getEyePosition().distanceToSqr(Vec3.atCenterOf(position)) <= reach * reach;
    }

    private boolean track() {
        var currentPolicy = GameAccess.serverPolicy();
        boolean changed = !currentPolicy.equals(inventoryPolicy);
        inventoryPolicy = currentPolicy;
        int selected = GameAccess.selectedBag(player);
        ItemStack current = selected >= 0 ? GameAccess.itemAt(player, selected) : ItemStack.EMPTY;
        if (selected != bagSlot || current != bagStack) {
            StoredGrid selectedGrid = current.isEmpty() ? null : LootSessions.bag(player, current);
            bagSlot = selected;
            bagStack = current;
            bag = selectedGrid;
            changed = true;
        }
        for (int i = 0; i <= GameAccess.EQUIPMENT_SLOT; i++) {
            if (!vanillaSlot(i)) {
                continue;
            }
            ItemStack stack = GameAccess.itemAt(player, i);
            if (!ItemStack.matches(vanillaSnapshot.getOrDefault(i, ItemStack.EMPTY), stack)) {
                vanillaSnapshot.put(i, stack.copy());
                changed = true;
            }
        }
        long nextBag = bag == null ? -1 : bag.revision;
        long nextCabinet = cabinet == null ? -1 : cabinet.revision;
        if (changed || bagRevision != nextBag || cabinetRevision != nextCabinet) {
            bagRevision = nextBag;
            cabinetRevision = nextCabinet;
            revision++;
            return true;
        }
        return false;
    }

    void show() {
        ExtractionNetwork.show(player, view());
    }

    CompoundTag view() {
        track();
        CompoundTag out = new CompoundTag();
        out.putString("kind", "view");
        out.putUUID("session", id);
        out.putLong("revision", revision);
        boolean locked = cabinet != null && !cabinet.unlocked;
        out.putString("mode", locked ? "lock" : "storage");
        out.putString("message", message);
        out.putBoolean("organizing", sorting != null);
        if (locked) {
            out.put("routes", AccessChallenge.routeList(cabinet, player, position));
            if (challenge != null) {
                out.put("challenge", challenge.view());
            }
        } else {
            if (bag != null) {
                out.put("bag", bag.view(player.getUUID(), 0));
            }
            if (cabinet != null) {
                out.put("cabinet", cabinet.view(player.getUUID(), elapsed));
            }
            ListTag packs = new ListTag();
            ListTag vanilla = new ListTag();
            for (int i = 0; i <= GameAccess.EQUIPMENT_SLOT; i++) {
                if (!vanillaSlot(i)) {
                    continue;
                }
                ItemStack stack = GameAccess.itemAt(player, i);
                boolean enabled = GameAccess.isEnabled(player, i);
                if (enabled && GameAccess.isBag(stack)) {
                    CompoundTag pack = new CompoundTag();
                    pack.putInt("slot", i);
                    pack.putString("name", stack.getHoverName().getString());
                    pack.putBoolean("selected", i == bagSlot);
                    packs.add(pack);
                }
                CompoundTag slot = new CompoundTag();
                slot.putInt("slot", i);
                slot.put("stack", stack.saveOptional(player.registryAccess()));
                slot.putBoolean("recovery", !enabled);
                if (!stack.isEmpty()) {
                    var style = bag != null ? bag.itemStyle(player, stack) : StoredGrid.style(player, stack, RuleBook.current());
                    StoredGrid.describe(slot, stack, style);
                }
                vanilla.add(slot);
            }
            out.put("packs", packs);
            out.put("vanilla", vanilla);
        }
        return out;
    }

    void closed() {
        CompoundTag out = new CompoundTag();
        out.putString("kind", "closed");
        out.putUUID("session", id);
        ExtractionNetwork.show(player, out);
    }

    private void finishChallenge() {
        if (challenge != null && challenge.complete()) {
            cabinet.unlock(player, position, challenge);
            challenge = null;
        }
    }

    void tick() {
        boolean changed = track();
        if (sorting != null) {
            if (sorting.revision != revision) {
                sorting = null;
                message = "库存已变化，请重试";
                changed = true;
            } else {
                Grid.Status status = sorting.search.step(256);
                if (status == Grid.Status.FOUND) {
                    commitGrid(sorting.source, sorting.target, sorting.search.result());
                    sorting = null;
                    changed = true;
                } else if (status == Grid.Status.IMPOSSIBLE) {
                    sorting = null;
                    message = "空间不足";
                    changed = true;
                }
            }
        }
        if (cabinet != null && !cabinet.unlocked) {
            if (challenge != null) {
                if (!challenge.allowed(player, position, false)) {
                    challenge = null;
                    message = "开启条件已经改变";
                    changed = true;
                } else {
                    challenge.tick(player);
                    finishChallenge();
                    changed |= player.tickCount % 3 == 0 || cabinet.unlocked;
                }
            } else {
                changed |= player.tickCount % 10 == 0;
            }
        } else if (cabinet != null) {
            challenge = null;
            int next = cabinet.searching(player.getUUID());
            if (searchSection != next) {
                elapsed = 0;
                searchSection = next;
                changed = true;
            }
            if (next >= 0 && ++elapsed >= cabinet.searchTicks()) {
                cabinet.reveal(player.getUUID(), next);
                elapsed = 0;
                changed = true;
            }
        }
        if (changed) {
            show();
        }
    }

    void action(CompoundTag input) {
        String action = input.getString("action");
        track();
        message = "";
        if (action.equals("cancel")) {
            sorting = null;
            challenge = null;
        } else if (cabinet != null && !cabinet.unlocked) {
            if (action.equals("route")) {
                int index = input.getInt("index");
                if (index >= 0 && index < cabinet.routes().size()) {
                    var route = cabinet.routes().get(index).getAsJsonObject();
                    if (AccessChallenge.conditions(route, player, position, false)) {
                        challenge = new AccessChallenge(route, player);
                    }
                }
            } else if (challenge != null && challenge.allowed(player, position, false)) {
                challenge.input(player, input);
            }
            finishChallenge();
        } else if (sorting != null) {
            message = "正在整理";
        } else if (input.getLong("revision") != revision) {
            message = "库存已变化，请重试";
        } else {
            switch (action) {
                case "select" -> GameAccess.selectBag(player, input.getInt("slot"));
                case "equip" -> GameAccess.equip(player, input.getInt("slot"));
                case "move", "transfer", "recover" -> move(input, action);
                default -> {
                }
            }
        }
        show();
    }

    private Source source(CompoundTag in, String area) {
        if (area.equals("vanilla")) {
            if (!in.contains("slot")) {
                return null;
            }
            int slot = in.getInt("slot");
            if (!vanillaSlot(slot)) {
                return null;
            }
            ItemStack stack = GameAccess.itemAt(player, slot);
            return stack.isEmpty() ? null : new Source(area, slot, null, StoredGrid.incoming(player, stack, RuleBook.current()));
        }
        StoredGrid grid = grid(area);
        if (grid == null || !in.hasUUID("id")) {
            return null;
        }
        UUID requested = in.getUUID("id");
        return grid.entries.stream().filter(e -> e.id().equals(requested) && grid.visible(player.getUUID(), e))
            .map(e -> new Source(area, -1, grid, e)).findFirst().orElse(null);
    }

    private StoredGrid grid(String name) {
        return switch (name) {
            case "bag" -> bag;
            case "cabinet" -> cabinet;
            default -> null;
        };
    }

    private void move(CompoundTag input, String action) {
        boolean recover = action.equals("recover");
        String from = recover ? "vanilla" : input.getString("from");
        Source source = source(input, from);
        if (source == null) {
            return;
        }
        boolean manual = action.equals("move");
        if (recover) {
            if (GameAccess.isEnabled(player, source.slot)) {
                return;
            }
            if (toVanilla(source, -1, true)) {
                return;
            }
        }
        String to;
        if (manual) {
            to = input.getString("to");
        } else if (from.equals("bag")) {
            to = cabinet == null ? "vanilla" : "cabinet";
        } else {
            to = "bag";
        }
        if (to.equals("vanilla")) {
            int slot = manual ? input.getInt("dest_slot") : -1;
            if ((manual && !vanillaSlot(slot)) || !toVanilla(source, slot, false)) {
                message = "目标槽位无法放入";
            }
            return;
        }
        StoredGrid target = grid(to);
        if (target == null || (target.bag && GameAccess.isBag(source.entry.stack()))) {
            message = "该位置无法收纳此物品";
            return;
        }
        if (source.grid == null) {
            source = new Source(source.area, source.slot, null, target.incoming(player, source.entry.stack()));
        }
        List<Grid.Entry> contents = new ArrayList<>(target.entries);
        if (target == source.grid) {
            contents.remove(source.entry);
        }
        IntPredicate allowed = section -> target.done(player.getUUID(), section);
        if (manual) {
            Grid.Entry placed = source.entry.at(input.getInt("section"), input.getInt("x"), input.getInt("y"), input.getInt("rotation"));
            if (placed.stack().getCount() > Math.min(placed.stack().getMaxStackSize(), placed.style().stackLimit())
                || !Grid.fits(target.layout.sections(), contents, placed, allowed)) {
                message = "目标位置无法放入";
                return;
            }
            contents.add(placed);
            commitGrid(source, target, contents);
            return;
        }
        var direct = Grid.direct(target.layout.sections(), contents, source.entry, allowed);
        if (direct.isPresent()) {
            commitGrid(source, target, direct.get());
        } else if (!recover) {
            sorting = new Sorting(source, target, Grid.repack(target.layout.sections(), contents, source.entry, allowed), revision);
        } else {
            message = "没有可取回物品的位置";
        }
    }

    private void commitGrid(Source source, StoredGrid target, List<Grid.Entry> result) {
        List<Grid.Entry> before = target.entries;
        if (target != source.grid) {
            remove(source);
        }
        target.entries = new ArrayList<>(result);
        if (!target.bag) {
            target.discoverAdded(player.getUUID(), before);
            target.refill();
        }
        target.revision++;
        target.persist();
    }

    private void remove(Source source) {
        if (source.grid == null) {
            GameAccess.setItemAt(player, source.slot, ItemStack.EMPTY);
        } else {
            source.grid.entries.remove(source.entry);
            source.grid.refill();
            source.grid.revision++;
            source.grid.persist();
        }
    }

    private boolean toVanilla(Source source, int destination, boolean recover) {
        if (destination == source.slot && destination >= 0) {
            return true;
        }
        ItemStack item = source.entry.stack();
        Map<Integer, ItemStack> changes = new HashMap<>();
        int remainder = item.getCount();
        for (int pass = 0; pass < 2 && remainder > 0; pass++) {
            int last = destination >= 0 ? destination : (recover ? 40 : 35);
            for (int slot = Math.max(0, destination); slot <= last && remainder > 0; slot++) {
                if (!vanillaSlot(slot) || slot == source.slot || !GameAccess.isEnabled(player, slot)
                    || (slot == GameAccess.EQUIPMENT_SLOT && !GameAccess.isBag(item))) {
                    continue;
                }
                ItemStack existing = GameAccess.itemAt(player, slot);
                if ((pass == 0 && (existing.isEmpty() || !ItemStack.isSameItemSameComponents(item, existing)))
                    || (pass == 1 && !existing.isEmpty())) {
                    continue;
                }
                int amount = Math.min(remainder, item.getMaxStackSize() - existing.getCount());
                if (amount > 0) {
                    changes.put(slot, item.copyWithCount(existing.getCount() + amount));
                    remainder -= amount;
                }
            }
        }
        if (remainder > 0) {
            return false;
        }
        remove(source);
        changes.forEach((slot, stack) -> GameAccess.setItemAt(player, slot, stack));
        return true;
    }
}
