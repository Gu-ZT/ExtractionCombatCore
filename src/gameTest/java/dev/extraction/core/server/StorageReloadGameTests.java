package dev.extraction.core.server;

import net.minecraft.core.UUIDUtil;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import dev.extraction.core.CabinetEntity;
import dev.extraction.core.ExtractionCore;
import dev.extraction.core.GameAccess;
import dev.extraction.core.rules.Grid;
import dev.extraction.core.rules.RuleBook;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.util.FakePlayer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class StorageReloadGameTests {
    private static final Identifier RACK = ExtractionCore.id("tool_rack");
    private static final Identifier PACK = ExtractionCore.id("field_pack");

    public static void smallerDataPack(GameTestHelper test) throws IOException {
        try {
            verifyReload(test);
        } catch (RuntimeException | IOException ex) {
            LogUtils.getLogger().error("验证世界数据包重载失败", ex);
            throw ex;
        }
    }

    private static void verifyReload(GameTestHelper test) throws IOException {
        var level = test.getLevel();
        var server = level.getServer();
        var repository = server.getPackRepository();
        List<String> original = List.copyOf(repository.getSelectedIds());
        BlockPos oldPos = test.absolutePos(new BlockPos(1, 1, 1));
        var player = player(test, oldPos, "PackTester");
        place(test, new BlockPos(1, 1, 1), RACK);
        LootSessions.openCabinet(player, oldPos);
        var previous = LootSessions.active(player);
        test.assertTrue(previous.cabinet.entries.size() == 2 && previous.cabinet.layout.sections().size() == 2,
            "原工具架应容纳两种演示战利品");
        List<UUID> lootIds = previous.cabinet.entries.stream().map(Grid.Entry::id).toList();
        close(previous);
        ItemStack oldPack = GameAccess.bound(ExtractionCore.PACK.get(), PACK);
        LootSessions.initializeBag(player, oldPack);
        var bag = LootSessions.bag(player, oldPack);
        var stored = bag.incoming(player, new ItemStack(Items.EMERALD, 7)).at(0, 3, 4, 0);
        test.assertTrue(Grid.fits(bag.layout.sections(), bag.entries, stored, i -> true), "旧背包应有远端格位");
        bag.entries.add(stored);
        bag.persist();
        Path archive = installCompactPack(server.getWorldPath(LevelResource.DATAPACK_DIR));
        String testPack = "file/" + archive.getFileName();
        ItemStack fresh = GameAccess.bound(ExtractionCore.PACK.get(), PACK);
        List<String> selected = new ArrayList<>(original);
        selected.add(testPack);
        try {
            repository.reload();
            test.assertTrue(repository.getAvailableIds().contains(testPack), "未发现测试数据包 " + testPack);
            server.reloadResources(selected).join();
            test.assertTrue(RuleBook.current().container(RACK).orElseThrow().sections().size() == 1,
                "启用数据包后注册的容器定义应已缩小");
            LootSessions.openCabinet(player, oldPos);
            LootSession reopened = LootSessions.active(player);
            test.assertTrue(reopened.cabinet.entries.stream().map(Grid.Entry::id).toList().equals(lootIds)
                && reopened.cabinet.layout.sections().size() == 2, "旧柜子应保留布局及原战利品");
            close(reopened);
            StoredGrid saved = LootSessions.bag(player, oldPack);
            test.assertTrue(saved.entries.getFirst().stack().getCount() == 7 && saved.entries.getFirst().x() == 3
                && saved.layout.sections().size() == 2, "数据包缩容影响了旧背包的边缘物品");
            LootSessions.initializeBag(player, fresh);
            var compactSection = LootSessions.bag(player, fresh).layout.sections().getFirst();
            test.assertTrue(compactSection.accepts(new ItemStack(Items.EMERALD)) && !compactSection.accepts(new ItemStack(Items.DIAMOND)),
                "新背包应使用本次载入的数据包标签");
            test.assertTrue(compactSection.shape().area() == 1,
                "新背包应使用缩小后的容量");
            BlockPos rel = new BlockPos(2, 1, 1);
            CabinetEntity compact = place(test, rel, RACK);
            BlockPos pos = test.absolutePos(rel);
            LootSessions.openCabinet(player, pos);
            LootSession first = LootSessions.active(player);
            test.assertTrue(first.cabinet.layout.sections().getFirst().shape().area() == 6
                && compact.cabinetData().getListOrEmpty("pending").size() == 1,
                "新柜子应保留放不下的六格挂钩");
            test.assertTrue(first.cabinet.entries.size() == 1 && first.cabinet.entries.getFirst().stack().is(Items.IRON_NUGGET)
                && first.cabinet.entries.getFirst().stack().getCount() == 4,
                "工具架应先放入四个铁粒，等待中的挂钩不占格");
            first.tick();
            test.assertTrue(first.cabinet.done(player.getUUID(), 0), "原分区应已搜完");
            close(first);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            NbtIo.writeCompressed(compact.saveWithFullMetadata(level.registryAccess()), bytes);
            var nbt = NbtIo.readCompressed(new ByteArrayInputStream(bytes.toByteArray()), NbtAccounter.unlimitedHeap());
            test.setBlock(rel, Blocks.AIR);
            test.setBlock(rel, ExtractionCore.CABINET.get());
            CabinetEntity restored = test.getBlockEntity(rel, CabinetEntity.class);
            restored.loadWithComponents(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess(), nbt));
            LootSessions.openCabinet(player, pos);
            LootSession search = LootSessions.active(player);
            test.assertTrue(search.cabinet.generated && search.cabinet.done(player.getUUID(), 0)
                && restored.cabinetData().getListOrEmpty("pending").size() == 1, "重进应保留等待列表与既有发现");
            Grid.Entry nugget = search.cabinet.entries.getFirst();
            CompoundTag take = action(search, "move");
            take.putString("from", "cabinet");
            take.store("id", UUIDUtil.CODEC, nugget.id());
            take.putString("to", "vanilla");
            take.putInt("dest_slot", 1);
            LootSessions.act(player, take);
            CompoundTag hidden = search.view().getCompoundOrEmpty("cabinet");
            test.assertTrue(player.getInventory().getItem(1).getCount() == 4
                && search.cabinet.entries.size() == 1 && search.cabinet.entries.getFirst().stack().is(ExtractionCore.HOOK.get()),
                "腾空后应恰好补入一件挂钩，取出槽=" + player.getInventory().getItem(1));
            test.assertTrue(hidden.getListOrEmpty("items").isEmpty()
                && !hidden.getListOrEmpty("sections").getCompoundOrEmpty(0).getBooleanOr("done", false)
                && restored.cabinetData().getListOrEmpty("pending").isEmpty(),
                "新补入物品应被遮盖，分区要重新搜索");
            search.tick();
            test.assertTrue(search.view().getCompoundOrEmpty("cabinet").getListOrEmpty("items").size() == 1,
                "完成下一次搜索后才应揭示挂钩");
            close(search);
        } finally {
            try {
                server.reloadResources(original).join();
            } finally {
                Files.deleteIfExists(archive);
                repository.reload();
            }
        }
        var restoredSection = LootSessions.bag(player, fresh).layout.sections().getFirst();
        test.assertTrue(restoredSection.accepts(new ItemStack(Items.EMERALD)) && !restoredSection.accepts(new ItemStack(Items.DIAMOND)),
            "移除数据包后旧背包应保留标签物品快照");
        test.succeed();
    }

    private static Path installCompactPack(Path folder) throws IOException {
        Files.createDirectories(folder);
        Path archive = Files.createTempFile(folder, "compact-storage-", ".zip");
        try (var output = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (String filename : List.of("pack.mcmeta", "data/extractioncombatcore/extraction/containers/tool_rack.json",
                "data/extractioncombatcore/extraction/backpacks/field_pack.json", "data/extractioncombatcore/tags/item/compact_supplies.json")) {
                output.putNextEntry(new ZipEntry(filename));
                try (var input = StorageReloadGameTests.class.getResourceAsStream("/test_packs/compact_storage/" + filename)) {
                    if (input == null) {
                        throw new IOException("找不到验收数据包资源 " + filename);
                    }
                    input.transferTo(output);
                }
                output.closeEntry();
            }
        } catch (IOException ex) {
            Files.deleteIfExists(archive);
            throw ex;
        }
        return archive;
    }

    public static void decoderAndCancellation(GameTestHelper test) {
        var rel = new BlockPos(1, 1, 1);
        var pos = test.absolutePos(rel);
        var player = player(test, pos, "DecoderTester");
        CabinetEntity block = place(test, rel, ExtractionCore.id("service_cabinet"));
        test.setBlock(rel.above(), Blocks.REDSTONE_BLOCK);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ExtractionCore.KEY.get(), 2));
        LootSessions.openCabinet(player, pos);
        LootSession key = LootSessions.active(player);
        select(key, 0);
        CompoundTag hold = action(key, "press");
        hold.putInt("round", key.view().getCompoundOrEmpty("challenge").getIntOr("round", 0));
        LootSessions.act(player, hold);
        key.tick();
        close(key);
        test.assertTrue(player.getMainHandItem().getCount() == 2 && !block.cabinetData().getBooleanOr("unlocked", false),
            "关闭未完成的消耗钥匙路线不应扣费");
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ExtractionCore.DECODER.get()));
        ItemStack expected = player.getMainHandItem().copy();
        LootSessions.openCabinet(player, pos);
        LootSession decode = LootSessions.active(player);
        select(decode, 1);
        decode.tick();
        test.setBlock(rel.above(), Blocks.AIR);
        decode.tick();
        test.assertTrue(!decode.view().contains("challenge") && ItemStack.matches(player.getMainHandItem(), expected),
            "断电应中断挑战并保留译码器");
        test.setBlock(rel.above(), Blocks.REDSTONE_BLOCK);
        select(decode, 1);
        for (int i = 0; i < 399; i++) {
            decode.tick();
        }
        test.assertTrue(!decode.cabinet.unlocked, "重新启动译码器应重新计算耗时");
        decode.tick();
        test.assertTrue(decode.cabinet.unlocked && ItemStack.matches(player.getMainHandItem(), expected)
            && decode.view().getCompoundOrEmpty("cabinet").getListOrEmpty("items").isEmpty(),
            "开锁应保留整台译码器，并进入未揭示的搜索状态");
        close(decode);
        test.succeed();
    }

    private static CabinetEntity place(GameTestHelper test, BlockPos local, Identifier definition) {
        test.setBlock(local, ExtractionCore.CABINET.get());
        CabinetEntity result = test.getBlockEntity(local, CabinetEntity.class);
        result.bind(definition);
        LootSessions.initializeCabinet(test.getLevel(), result);
        return result;
    }

    private static ServerPlayer player(GameTestHelper test, BlockPos pos, String name) {
        var player = new FakePlayer(test.getLevel(), new GameProfile(UUID.randomUUID(), name));
        player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        return player;
    }

    private static CompoundTag action(LootSession session, String action) {
        CompoundTag message = new CompoundTag();
        message.putString("action", action);
        message.store("session", UUIDUtil.CODEC, session.id);
        message.putLong("revision", session.view().getLongOr("revision", 0L));
        return message;
    }

    private static void select(LootSession session, int index) {
        CompoundTag action = action(session, "route");
        action.putInt("index", index);
        LootSessions.act(session.player, action);
    }

    private static void close(LootSession session) {
        LootSessions.act(session.player, action(session, "close"));
    }
}
