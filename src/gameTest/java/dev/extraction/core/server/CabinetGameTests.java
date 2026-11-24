package dev.extraction.core.server;

import net.minecraft.network.chat.Component;
import net.minecraft.core.UUIDUtil;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import dev.extraction.core.CabinetEntity;
import dev.extraction.core.ExtractionCore;
import dev.extraction.core.rules.Grid;
import dev.extraction.core.rules.RuleBook;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.common.util.FakePlayer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class CabinetGameTests {
    private static final String CABINET = """
        {"sections":[{"id":"main","shape":["XXX"]}],
         "search":{"mode":"per_item","ticks_per_item":2},
         "access":{"routes":[{"requires":[{"type":"held_item","item":"minecraft:tripwire_hook","consume":true}],
                 "steps":[{"type":"hold","ticks":1}]}]}}
        """;

    public static void concurrentCabinet(GameTestHelper test) throws IOException {
        BlockPos local = new BlockPos(1, 1, 1);
        BlockPos pos = test.absolutePos(local);
        test.setBlock(local, ExtractionCore.CABINET.get());
        CabinetEntity block = test.getBlockEntity(local, CabinetEntity.class);
        var level = test.getLevel();
        var def = RuleBook.restoreStorage(JsonParser.parseString(CABINET).getAsJsonObject(), false);
        StoredGrid grid = new StoredGrid(def, false, new CompoundTag(), level.registryAccess(), block::saveCabinet);
        Grid.Entry first = new Grid.Entry(UUID.randomUUID(), new ItemStack(Items.IRON_NUGGET, 4), Grid.Style.DEFAULT, 0, 0, 0, 0);
        Grid.Entry second = new Grid.Entry(UUID.randomUUID(), new ItemStack(Items.IRON_INGOT), Grid.Style.DEFAULT, 0, 2, 0, 0);
        grid.entries.add(first);
        grid.entries.add(second);
        grid.persist();
        ServerPlayer one = player(test, pos, "First");
        ServerPlayer two = player(test, pos, "Second");
        one.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.TRIPWIRE_HOOK));
        two.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.TRIPWIRE_HOOK));
        LootSessions.openCabinet(one, pos);
        LootSessions.openCabinet(two, pos);
        LootSession a = LootSessions.active(one);
        LootSession b = LootSessions.active(two);
        test.assertTrue(!a.view().contains("cabinet"), Component.literal("上锁时发送了容器库存"));
        startHold(a);
        startHold(b);
        a.tick();
        b.tick();
        test.assertTrue(one.getMainHandItem().isEmpty() && two.getMainHandItem().getCount() == 1, Component.literal("同刻开锁应只扣先完成者的钥匙"));
        a.tick();
        a.tick();
        test.assertTrue(itemCount(a) == 1 && itemCount(b) == 0, Component.literal("玩家的发现记录必须独立"));
        CompoundTag stale = action(b, "move");
        stale.putString("from", "cabinet");
        stale.store("id", UUIDUtil.CODEC, first.id());
        stale.putString("to", "vanilla");
        stale.putInt("dest_slot", 1);
        LootSessions.act(two, stale);
        test.assertTrue(two.getInventory().getItem(1).isEmpty(), Component.literal("未发现的物品不能转移"));
        b.tick();
        test.assertTrue(itemCount(b) == 1, Component.literal("第二名玩家自行搜索后应看到第一个栈"));
        CompoundTag take = action(a, "move");
        take.putString("from", "cabinet");
        take.store("id", UUIDUtil.CODEC, first.id());
        take.putString("to", "vanilla");
        take.putInt("dest_slot", 1);
        LootSessions.act(one, take);
        LootSessions.act(two, stale);
        test.assertTrue(one.getInventory().getItem(1).getCount() == 4 && two.getInventory().getItem(1).isEmpty(), Component.literal("相同物品只可取走一次"));
        a.tick();
        a.tick();
        test.assertTrue(itemCount(a) == 1 && itemCount(b) == 0, Component.literal("取物后另一名玩家不应看见未知物品"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtIo.writeCompressed(block.saveWithFullMetadata(level.registryAccess()), bytes);
        CompoundTag nbt = NbtIo.readCompressed(new ByteArrayInputStream(bytes.toByteArray()), NbtAccounter.unlimitedHeap());
        CabinetEntity restored = (CabinetEntity)BlockEntity.loadStatic(pos, block.getBlockState(), nbt, level.registryAccess());
        test.assertTrue(restored != null, Component.literal("方块实体从 NBT 恢复失败"));
        StoredGrid saved = new StoredGrid(def, false, restored.cabinetData(), level.registryAccess(), restored::saveCabinet);
        test.assertTrue(saved.unlocked && saved.generated && saved.entries.size() == 1, Component.literal("开锁状态、战利品生成状态或库存数量丢失"));
        test.assertTrue(saved.entries.getFirst().id().equals(second.id()) && saved.entries.getFirst().x() == 2
            && saved.visible(one.getUUID(), second) && !saved.visible(two.getUUID(), second), Component.literal("存档未保留占格与个人发现记录"));
        LootSessions.act(one, action(a, "close"));
        LootSessions.act(two, action(b, "close"));
        test.succeed();
    }

    public static void challengeInputs(GameTestHelper test) {
        ServerPlayer player = player(test, test.absolutePos(new BlockPos(1, 1, 1)), "Solver");
        AccessChallenge password = challenge(player, "{\"type\":\"password\",\"answer\":\"007\",\"error_pause_ticks\":2}");
        input(player, password, "answer", "7");
        test.assertTrue(!password.complete() && password.view().getIntOr("pause", 0) == 2, Component.literal("密码应逐字符比较"));
        password.tick(player);
        password.tick(player);
        input(player, password, "answer", "007");
        test.assertTrue(password.complete(), Component.literal("正确密码应完成挑战"));
        AccessChallenge morse = challenge(player, "{\"type\":\"audio_morse\",\"answer\":\"A3\"}");
        String clues = morse.view().getStringOr("signals", "");
        input(player, morse, "replay", "");
        test.assertTrue(clues.equals(".- ...--") && morse.view().getStringOr("signals", "").equals(clues)
            && !morse.view().toString().contains("A3"), Component.literal("摩斯重播应保留信号且不发送明文"));
        input(player, morse, "answer", "a3");
        test.assertTrue(morse.complete(), Component.literal("摩斯输入应解出字母数字"));
        AccessChallenge timing = challenge(player, "{\"type\":\"accelerated_decode\",\"passive_ticks\":100,\"hit_bonus\":0.15}");
        for (int t = 0; t < 10; t++) {
            timing.tick(player);
        }
        input(player, timing, "press", "");
        float first = timing.view().getFloatOr("progress", 0f);
        input(player, timing, "press", "");
        test.assertTrue(Math.abs(first - 0.25f) < 0.001 && first == timing.view().getFloatOr("progress", 0f), Component.literal("同次经过只应加速一次"));
        for (int t = 0; t < 75; t++) {
            timing.tick(player);
        }
        test.assertTrue(timing.complete(), Component.literal("剩余进度应自动完成"));
        for (int positions : new int[]{3, 9}) {
            AccessChallenge rolling = challenge(player, "{\"type\":\"rolling_lock\",\"positions\":" + positions + "}");
            boolean paused = false;
            for (int ticks = 0; ticks < 2000 && !rolling.complete(); ticks++) {
                CompoundTag state = rolling.view();
                int active = state.getIntOr("locked", 0);
                int elapsed = state.getIntOr("elapsed", 0);
                double phase = elapsed * (double)state.getIntArray("directions").orElseGet(() -> new int[0])[active] / state.getIntArray("periods").orElseGet(() -> new int[0])[active];
                int current = (int)Math.floor(phase + 0.5);
                boolean exact = Math.abs(phase - current) <= 0.30 && state.getStringOr("alphabet", "")
                    .charAt(Math.floorMod(current, state.getStringOr("alphabet", "").length())) == state.getStringOr("target", "").charAt(active);
                if (!exact && active == 1 && !paused) {
                    input(player, rolling, "press", "");
                    input(player, rolling, "press", "");
                    test.assertTrue(rolling.view().getIntOr("pause", 0) == 40 && rolling.view().getIntOr("locked", 0) == 1, Component.literal("误按应保留已锁位"));
                    for (int i = 0; i < 40; i++) {
                        rolling.tick(player);
                        input(player, rolling, "replay", "");
                    }
                    test.assertTrue(rolling.view().getIntOr("pause", 0) == 0 && rolling.view().getIntOr("elapsed", 0) == elapsed, Component.literal("误按暂停后应从原位置恢复且重复输入不会延长暂停"));
                    paused = true;
                } else if (exact && (active != 1 || paused)) {
                    input(player, rolling, "press", "");
                }
                rolling.tick(player);
            }
            test.assertTrue(rolling.complete() && paused, Component.literal("3 位和 9 位锁必须按顺序锁完全部位置"));
        }
        AccessChallenge hold = challenge(player, "{\"type\":\"hold\",\"ticks\":4}");
        input(player, hold, "press", "");
        hold.tick(player);
        input(player, hold, "release", "");
        test.assertTrue(!hold.complete() && hold.view().getIntOr("elapsed", 0) == 0, Component.literal("松手应清除本次长按进度"));
        test.succeed();
    }

    private static ServerPlayer player(GameTestHelper test, BlockPos pos, String name) {
        ServerPlayer player = new FakePlayer(test.getLevel(), new GameProfile(UUID.randomUUID(), name));
        player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        return player;
    }

    private static AccessChallenge challenge(ServerPlayer player, String step) {
        JsonObject route = JsonParser.parseString("{\"steps\":[" + step + "]}").getAsJsonObject();
        return new AccessChallenge(route, player);
    }

    private static void input(ServerPlayer player, AccessChallenge challenge, String action, String answer) {
        CompoundTag message = new CompoundTag();
        message.putString("action", action);
        message.putInt("round", challenge.view().getIntOr("round", 0));
        message.putByteArray("text_utf8", answer.getBytes(StandardCharsets.UTF_8));
        challenge.input(player, message);
    }

    private static void startHold(LootSession session) {
        CompoundTag route = action(session, "route");
        route.putInt("index", 0);
        LootSessions.act(session.player, route);
        CompoundTag press = action(session, "press");
        press.putInt("round", session.view().getCompoundOrEmpty("challenge").getIntOr("round", 0));
        LootSessions.act(session.player, press);
    }

    private static CompoundTag action(LootSession session, String action) {
        CompoundTag input = new CompoundTag();
        input.putString("action", action);
        input.store("session", UUIDUtil.CODEC, session.id);
        input.putLong("revision", session.view().getLongOr("revision", 0L));
        return input;
    }

    private static int itemCount(LootSession session) {
        return session.view().getCompoundOrEmpty("cabinet").getListOrEmpty("items").size();
    }
}
