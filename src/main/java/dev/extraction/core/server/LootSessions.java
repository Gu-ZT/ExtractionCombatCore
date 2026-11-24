package dev.extraction.core.server;

import dev.extraction.core.GameAccess;
import dev.extraction.core.network.ExtractionNetwork;
import dev.extraction.core.rules.Grid;
import dev.extraction.core.rules.RuleBook;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 在服务器主线程上处理玩家可见的库存与交互。 */
@EventBusSubscriber(modid = "extractioncombatcore")
public final class LootSessions {
    private static final Map<UUID, LootSession> OPEN = new HashMap<>();
    private static GameAccess.InventoryPolicy sentPolicy = GameAccess.DEFAULT_POLICY;

    private LootSessions() {
    }

    private static Optional<RuleBook.Storage> layout(CompoundTag data, boolean bag, ResourceLocation id) {
        if (data.contains("layout")) {
            return Optional.of(RuleBook.restoreStorage(StoredGrid.savedLayout(data), bag));
        }
        return bag ? RuleBook.current().backpack(id) : RuleBook.current().container(id);
    }

    public static void initializeCabinet(ServerLevel level, CabinetStorage block) {
        CompoundTag data = block.cabinetData();
        if (!data.contains("layout")) {
            layout(data, false, block.definitionId())
                .ifPresent(rule -> new StoredGrid(rule, false, data, level.registryAccess(), block::saveCabinet));
        }
    }

    public static void initializeBag(ServerPlayer player, ItemStack stack) {
        if (GameAccess.isSingleBag(stack) && !GameAccess.bagData(player, stack).contains("layout")) {
            bag(player, stack);
        }
    }

    static StoredGrid bag(ServerPlayer player, ItemStack stack) {
        Optional<UUID> instance = GameAccess.instanceId(stack);
        for (LootSession other : OPEN.values()) {
            if (other.bag != null && (other.bagStack == stack || instance.isPresent() && instance.equals(GameAccess.instanceId(other.bagStack)))) {
                return other.bag;
            }
        }
        CompoundTag data = GameAccess.bagData(player, stack);
        return layout(data, true, GameAccess.bagType(stack))
            .map(rule -> new StoredGrid(rule, true, data, player.registryAccess(), saved -> GameAccess.saveBag(player, stack, saved)))
            .orElse(null);
    }

    public static void openCabinet(ServerPlayer player, BlockPos pos) {
        if (player.isSpectator() || !player.isAlive() || !(player.serverLevel().getBlockEntity(pos) instanceof CabinetStorage block)) {
            return;
        }
        close(player, false);
        StoredGrid cabinet = null;
        for (LootSession other : OPEN.values()) {
            if (other.block == block) {
                cabinet = other.cabinet;
                break;
            }
        }
        if (cabinet == null) {
            CompoundTag data = block.cabinetData();
            var rules = layout(data, false, block.definitionId());
            if (rules.isEmpty()) {
                player.displayClientMessage(Component.literal("找不到容器定义 " + block.definitionId()), true);
                return;
            }
            cabinet = new StoredGrid(rules.get(), false, data, player.registryAccess(), block::saveCabinet);
        }
        if (cabinet.unlocked) {
            cabinet.generate(player, pos);
        }
        player.closeContainer();
        LootSession session = new LootSession(player, pos.immutable(), block, cabinet);
        OPEN.put(player.getUUID(), session);
        session.show();
    }

    /** 在服务端主线程打开指定物品栏槽中的单件背包。传入 -1 打开当前收纳界面。 */
    public static void openPack(ServerPlayer player, int slot) {
        if (player.isSpectator() || !player.isAlive()) {
            return;
        }
        if (slot >= 0) {
            GameAccess.selectBag(player, slot);
            if (GameAccess.selectedBag(player) != slot) {
                return;
            }
        } else if (slot != -1) {
            return;
        }
        close(player, false);
        player.closeContainer();
        LootSession session = new LootSession(player, null, null, null);
        OPEN.put(player.getUUID(), session);
        session.show();
    }

    /** 试放入已装备的背包，返回成功收纳的数量，由拾取路径扣除地上物品。 */
    public static int pickup(ServerPlayer player, ItemStack incoming) {
        ItemStack equipped = GameAccess.itemAt(player, GameAccess.EQUIPMENT_SLOT);
        if (!GameAccess.isSingleBag(equipped) || incoming.isEmpty() || GameAccess.isBag(incoming)) {
            return 0;
        }
        StoredGrid pack = bag(player, equipped);
        if (pack == null) {
            return 0;
        }
        Grid.Entry original = pack.incoming(player, incoming);
        int low = 1;
        int high = incoming.getCount();
        int accepted = 0;
        List<Grid.Entry> layout = null;
        while (low <= high) {
            int test = accepted == 0 && low == 1 && high == incoming.getCount() ? high : low + (high - low) / 2;
            var candidate = new Grid.Entry(original.id(), incoming.copyWithCount(test), original.style(), 0, 0, 0, 0);
            var result = Grid.direct(pack.layout.sections(), pack.entries, candidate, section -> true);
            if (result.isPresent()) {
                accepted = test;
                layout = result.get();
                low = test + 1;
            } else {
                high = test - 1;
            }
        }
        if (layout != null) {
            pack.entries = new ArrayList<>(layout);
            pack.revision++;
            pack.persist();
        }
        return accepted;
    }

    public static void act(ServerPlayer player, CompoundTag input) {
        if (input.getString("action").equals("open")) {
            if (player.containerMenu == player.inventoryMenu) {
                openPack(player, -1);
            }
            return;
        }
        LootSession session = active(player);
        if (session == null || !input.hasUUID("session") || !session.id.equals(input.getUUID("session"))) {
            return;
        }
        if (!session.valid() || input.getString("action").equals("close")) {
            close(player, true);
            return;
        }
        session.action(input);
    }

    static LootSession active(ServerPlayer player) {
        return OPEN.get(player.getUUID());
    }

    private static void close(ServerPlayer player, boolean notify) {
        LootSession session = OPEN.remove(player.getUUID());
        if (notify && session != null) {
            session.closed();
        }
    }

    @SubscribeEvent
    public static void tick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            for (int slot = 0; slot <= GameAccess.EQUIPMENT_SLOT; slot++) {
                ItemStack stack = GameAccess.itemAt(player, slot);
                if (GameAccess.needsBagInstance(stack)) {
                    bag(player, stack);
                }
            }
            LootSession session = active(player);
            if (session != null) {
                if (session.valid()) {
                    session.tick();
                } else {
                    close(player, true);
                }
            }
        }
    }

    @SubscribeEvent
    public static void hurt(LivingDamageEvent.Post event) {
        if (event.getNewDamage() > 0 && event.getEntity() instanceof ServerPlayer player) {
            close(player, true);
        }
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            close(player, false);
        }
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        OPEN.clear();
        sentPolicy = GameAccess.DEFAULT_POLICY;
    }

    @SubscribeEvent
    public static void syncInventoryPolicy(ServerTickEvent.Post event) {
        var current = GameAccess.serverPolicy();
        if (!current.equals(sentPolicy)) {
            sentPolicy = current;
            CompoundTag update = current.encode();
            update.putString("kind", "inventory_policy");
            event.getServer().getPlayerList().getPlayers().forEach(player -> ExtractionNetwork.show(player, update));
        }
    }

    @SubscribeEvent
    public static void sync(OnDatapackSyncEvent event) {
        byte[] rules = RuleBook.current().publicItems().getBytes(StandardCharsets.UTF_8);
        int size = 32768;
        int parts = Math.max(1, (rules.length + size - 1) / size);
        event.getRelevantPlayers().forEach(player -> {
            for (int index = 0; index < parts; index++) {
                CompoundTag policy = GameAccess.serverPolicy().encode();
                policy.putString("kind", "policy");
                policy.putInt("part", index);
                policy.putInt("parts", parts);
                policy.putByteArray("rules_utf8", Arrays.copyOfRange(rules, index * size, Math.min(rules.length, (index + 1) * size)));
                ExtractionNetwork.show(player, policy);
            }
        });
    }
}
