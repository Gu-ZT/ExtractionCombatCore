package dev.extraction.core.client;

import com.mojang.logging.LogUtils;
import dev.extraction.core.ExtractionCore;
import dev.extraction.core.network.ExtractionNetwork;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** 使用两份开发客户端验证实际登录握手、界面操作和玩家专属视图。 */
@EventBusSubscriber(modid = "extractioncombatcore", value = Dist.CLIENT)
public final class ClientPlaythrough {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ROLE = System.getProperty("extraction.playtest", "");
    private static final boolean HOST = ROLE.equals("host");
    private static final Path DIR = Path.of(System.getProperty("extraction.playtest.dir", ".")).toAbsolutePath();
    private static final BlockPos CABINET = new BlockPos(8, 80, 8);
    private static final int PORT = 25483;
    private static Stage stage = Stage.START;
    private static long entered = System.nanoTime();
    private static CompletableFuture<Void> setup;
    private static boolean listening;
    private static boolean finished;
    private static String capture;
    private static UUID contested;
    private static CompoundTag stale;
    private static String worldName;

    private enum Stage {
        START, JOIN, EQUIP, EQUIPPED, CABINET, ROUTE, HOLD, UNLOCKED, REOPEN, FIRST, VISIBILITY, CLAIM, TALLY, FINISH, VISUAL
    }

    private ClientPlaythrough() {
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if ((!HOST && !ROLE.equals("guest")) || finished) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        try {
            if (Duration.ofNanos(System.nanoTime() - entered).toMinutes() >= 6) {
                throw new IllegalStateException("等待阶段超时 " + stage + "，屏幕 " + mc.gui.screen());
            }
            if (mc.gui.screen() instanceof DisconnectedScreen) {
                throw new IllegalStateException("测试连接已经断开 " + mc.gui.screen().getTitle().getString());
            }
            if (exists((HOST ? "guest" : "host") + ".failed")) {
                throw new IllegalStateException("另一客户端报告失败");
            }
            advance(mc);
        } catch (Exception | AssertionError failure) {
            finished = true;
            LOGGER.error("双客户端验收失败 {} {}", ROLE, stage, failure);
            try {
                mark(ROLE + ".failed", stage + ": " + failure);
            } catch (IOException writeFailure) {
                LOGGER.error("未能记录验收错误", writeFailure);
            }
            mc.stop();
        }
    }

    private static void advance(Minecraft mc) throws IOException {
        if (stage == Stage.START) {
            mc.options.pauseOnLostFocus = false;
            if (mc.gui.overlay() != null || !(mc.gui.screen() instanceof TitleScreen || mc.gui.screen() instanceof AccessibilityOnboardingScreen)) {
                return;
            }
            Files.createDirectories(DIR);
            if (HOST) {
                mc.getTutorial().setStep(TutorialSteps.NONE);
                next(Stage.JOIN);
                LevelSettings settings = new LevelSettings("双人搜刮验收", GameType.SURVIVAL,
                new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
                worldName = "extraction-playtest-" + System.currentTimeMillis();
                mc.createWorldOpenFlows().createFreshLevel(worldName, settings,
                    new WorldOptions(341, false, false), registries -> registries.lookupOrThrow(Registries.WORLD_PRESET)
                        .getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), new TitleScreen());
            } else if (exists("listener.ready")) {
                mc.getTutorial().setStep(TutorialSteps.NONE);
                next(Stage.JOIN);
                String address = "127.0.0.1:" + PORT;
                ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(address),
                    new ServerData("搜刮验收", address, ServerData.Type.OTHER), false, null);
            }
            return;
        }
        if (stage == Stage.VISUAL) {
            if (ClientVisualChecks.tick(mc, DIR, worldName)) {
                mark("host.success", "钥匙只扣一次，铁粒只转移一次，四向预览、凹口、贴图与重新进档检查通过。");
                finished = true;
                mc.stop();
            }
            return;
        }
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (stage == Stage.JOIN) {
            if (HOST) {
                arrangeHost(mc);
            }
            if (exists("world.ready") && mc.gui.screen() == null && mc.level.getBlockState(CABINET).is(ExtractionCore.CABINET.get())
                && mc.player.getInventory().getItem(2).is(ExtractionCore.PACK.get())) {
                if (!ClientInventoryChecks.tick(mc, HOST, DIR)) {
                    return;
                }
                if (Boolean.getBoolean("extraction.policyOnly")) {
                    if (!HOST || exists("guest.success")) {
                        mark(ROLE + ".success", "远端在线修改三格、零格、九格后，主背包遮罩、主手与滚轮均已同步。");
                        finished = true;
                        mc.stop();
                    }
                    return;
                }
                KeyMapping.click(ExtractionClient.OPEN.getKey());
                next(Stage.EQUIP);
            }
            return;
        }
        if (stage == Stage.REOPEN) {
            if (mc.gui.screen() == null && exists("host.hook")) {
                useCabinet(mc);
                next(Stage.FIRST);
            }
            return;
        }
        if (stage == Stage.CABINET) {
            if (mc.gui.screen() == null && exists(HOST ? "guest.equipped" : "host.equipped")) {
                useCabinet(mc);
                next(Stage.ROUTE);
            }
            return;
        }
        if (!(mc.gui.screen() instanceof LootScreen screen)) {
            return;
        }
        CompoundTag view = screen.view;
        switch (stage) {
            case EQUIP -> {
                if (screen.storage != null) {
                    int[] point = find(screen, "vanilla", null, 2);
                    if (point == null) {
                        return;
                    }
                    screen.mouseClicked(new MouseButtonEvent(point[0], point[1], new MouseButtonInfo(1, 0)), false);
                    next(Stage.EQUIPPED);
                }
            }
            case EQUIPPED -> {
                if (!StorageView.compounds(view, "packs").stream().anyMatch(p -> p.getIntOr("slot", 0) == 41 && p.getBooleanOr("selected", false))) {
                    return;
                }
                require(!StorageView.compounds(view.getCompoundOrEmpty("bag"), "sections").isEmpty(), "装备后的背包没有分区");
                mark(ROLE + ".equipped", mc.player.getGameProfile().name());
                screen.onClose();
                next(Stage.CABINET);
            }
            case ROUTE -> {
                require(view.getStringOr("mode", "").equals("lock"), "维修柜应先展示开启路线");
                require(!view.contains("cabinet"), "上锁时收到了柜中内容");
                require(!StorageView.compounds(view, "routes").isEmpty(), "缺少开启路线");
                screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
                next(Stage.HOLD);
            }
            case HOLD -> {
                if (view.getCompoundOrEmpty("challenge").getStringOr("type", "").equals("hold")) {
                    if (!exists(ROLE + ".ready-to-hold")) {
                        mark(ROLE + ".ready-to-hold", "钥匙路线已开始");
                    }
                    if (!exists(HOST ? "guest.ready-to-hold" : "host.ready-to-hold")) {
                        return;
                    }
                    screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_SPACE, 0, 0));
                    next(Stage.UNLOCKED);
                }
            }
            case UNLOCKED -> {
                if (!view.contains("cabinet")) {
                    return;
                }
                ItemStack hand = mc.player.getMainHandItem();
                require(hand.isEmpty() || hand.is(ExtractionCore.KEY.get()) && hand.getCount() == 1, "竞争开锁后主手物品不符");
                mark(ROLE + (hand.isEmpty() ? ".paid" : ".retained"), "开门时客户端持钥匙数量 " + hand.getCount());
                if (HOST) {
                    next(Stage.VISIBILITY);
                } else {
                    require(items(view, "cabinet").isEmpty(), "开门时乙已经看到了未经搜索的物品");
                    screen.onClose();
                    next(Stage.REOPEN);
                }
            }
            case FIRST -> {
                if (!view.contains("cabinet")) {
                    return;
                }
                require(items(view, "cabinet").isEmpty(), "甲已揭示挂钩，乙重开柜子时应该仍然看不到它");
                mark("guest.masked", "甲看见挂钩后，乙再次打开柜子的首帧仍是全遮罩");
                capture = "guest-mask";
                next(Stage.VISIBILITY);
            }
            case VISIBILITY -> {
                CompoundTag nuggets = nugget(mc, view, "cabinet");
                if (nuggets == null) {
                    return;
                }
                contested = nuggets.read("id", UUIDUtil.CODEC).orElseThrow();
                if (HOST) {
                    mark("host.found", contested.toString());
                    capture = "host-discovery";
                } else {
                    require(contested.toString().equals(Files.readString(DIR.resolve("host.found"))), "两人揭示了不同的铁粒条目");
                    stale = screen.action("transfer");
                    stale.putString("from", "cabinet");
                    stale.store("id", UUIDUtil.CODEC, contested);
                    mark("guest.found", contested.toString());
                }
                next(Stage.CLAIM);
            }
            case CLAIM -> {
                if (HOST && !exists("host.hook") && items(view, "cabinet").size() == 2) {
                    mark("host.hook", "主机已揭示铁粒和挂钩");
                }

                if (HOST && exists("guest.found")) {
                    require(exists("host.paid") && exists("guest.retained") || exists("guest.paid") && exists("host.retained"),
                        "两人同时持钥匙开柜必须恰好消耗一把钥匙");

                    int[] point = find(screen, "cabinet", contested, -1);
                    if (point == null) {
                        return;
                    }
                    for (int n = 0; n < 2; n++) {
                        screen.mouseClicked(new MouseButtonEvent(point[0], point[1], new MouseButtonInfo(0, 0)), false);
                        screen.mouseReleased(new MouseButtonEvent(point[0], point[1], new MouseButtonInfo(0, 0)));
                    }
                    next(Stage.TALLY);
                } else if (!HOST && exists("host.claimed")) {
                    ExtractionNetwork.send(stale);
                    next(Stage.TALLY);
                }
            }
            case TALLY -> {
                if (nugget(mc, view, "cabinet") != null) {
                    return;
                }
                CompoundTag found = nugget(mc, view, "bag");
                if (HOST) {
                    if (found == null) {
                        return;
                    }
                    int count = found.read("stack", ItemStack.OPTIONAL_CODEC, mc.player.registryAccess()
                        .createSerializationContext(NbtOps.INSTANCE)).orElse(ItemStack.EMPTY).getCount();
                    require(count == 4, "甲的背包应正好收到四个铁粒");
                    mark("host.claimed", "4");
                } else {
                    if (view.getStringOr("message", "").isEmpty()) {
                        return;
                    }
                    require(found == null, "乙使用过时界面重复获得了铁粒");
                    capture = "guest-conflict";
                    mark("guest.verified", view.getStringOr("message", ""));
                }
                next(Stage.FINISH);
            }
            case FINISH -> {
                if (HOST && exists("guest.verified")) {
                    if (!exists("host.verified")) {
                        mark("host.verified", "持钥匙开启，独立搜刮，四个铁粒只转移一次。");
                    }
                } else if (!HOST && exists("host.verified") && capture == null) {
                    mark("guest.success", "锁已打开，首次零揭示，过时操作没有转移物品。");
                    finished = true;
                    mc.stop();
                }
                if (HOST && exists("guest.success") && capture == null) {
                    next(Stage.VISUAL);
                }
            }
            default -> {
            }
        }
    }

    static void useCabinet(Minecraft mc) {
        Vec3 point = Vec3.atCenterOf(CABINET).add(0, 0, .5);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(point, Direction.SOUTH, CABINET, false));
    }

    private static void arrangeHost(Minecraft mc) {
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            return;
        }
        if (setup != null) {
            if (setup.isDone()) {
                setup.join();
                setup = null;
            }
            return;
        }
        if (!listening) {
            listening = true;
            setup = server.submit(() -> {
                server.setUsesAuthentication(false);
                try {
                    server.getConnection().startTcpServerListener(InetAddress.getByName("127.0.0.1"), PORT);
                    mark("listener.ready", Integer.toString(PORT));
                } catch (IOException failure) {
                    throw new IllegalStateException("无法启动本地测试监听", failure);
                }
            });
        } else if (!exists("world.ready")) {
            setup = server.submit(() -> {
                List<ServerPlayer> players = server.getPlayerList().getPlayers();
                if (players.size() < 2) {
                    return;
                }
                ServerLevel level = server.overworld();
                for (BlockPos p : BlockPos.betweenClosed(CABINET.offset(-3, -1, -3), CABINET.offset(3, -1, 3))) {
                    level.setBlockAndUpdate(p, Blocks.STONE.defaultBlockState());
                }
                Block cabinet = ExtractionCore.CABINET.get();
                level.setBlockAndUpdate(CABINET, cabinet.defaultBlockState());
                level.setBlockAndUpdate(CABINET.west(), Blocks.REDSTONE_BLOCK.defaultBlockState());
                for (int i = 0; i < players.size(); i++) {
                    ServerPlayer player = players.get(i);
                    boolean owner = server.isSingleplayerOwner(player.nameAndId());
                    player.connection.teleport(CABINET.getX() + (owner ? .3 : 1.3), CABINET.getY(), CABINET.getZ() + 2.4, 180, 20);
                    player.getInventory().setSelectedSlot(0);
                    player.getInventory().setItem(0, new ItemStack(ExtractionCore.KEY.get()));
                    if (owner) {
                        player.getInventory().setItem(1, new ItemStack(ExtractionCore.HOOK.get()));
                    }
                    player.getInventory().setItem(2, new ItemStack(ExtractionCore.PACK.get()));
                    player.inventoryMenu.broadcastChanges();
                }
                try {
                    mark("world.ready", players.get(0).getGameProfile().name() + " / " + players.get(1).getGameProfile().name());
                } catch (IOException failure) {
                    throw new IllegalStateException("无法记录世界准备状态", failure);
                }
            });
        }
    }

    private static int[] find(LootScreen screen, String panel, UUID id, int slot) {
        for (int y = 45; y < screen.height - 22; y += 5) {
            for (int x = 6; x < screen.width - 4; x += 5) {
                StorageView.Hit hit = screen.storage.hit(x, y);
                if (hit != null && hit.entry() != null && hit.panel().equals(panel)
                    && (id == null ? hit.slot() == slot : id.equals(hit.entry().id()))) {
                    return new int[]{x, y};
                }
            }
        }
        return null;
    }

    private static List<CompoundTag> items(CompoundTag view, String panel) {
        return StorageView.compounds(view.getCompoundOrEmpty(panel), "items");
    }

    private static CompoundTag nugget(Minecraft mc, CompoundTag view, String panel) {
        return items(view, panel).stream().filter(entry -> entry.read("stack", ItemStack.OPTIONAL_CODEC, mc.player.registryAccess()
            .createSerializationContext(NbtOps.INSTANCE)).orElse(ItemStack.EMPTY).is(Items.IRON_NUGGET)).findFirst().orElse(null);
    }

    private static void require(boolean condition, String why) {
        if (!condition) {
            throw new AssertionError(why);
        }
    }

    private static void next(Stage to) {
        stage = to;
        entered = System.nanoTime();
        LOGGER.info("双客户端验收 {} {}", ROLE, stage);
    }

    private static boolean exists(String name) {
        return Files.exists(DIR.resolve(name));
    }

    private static void mark(String name, String text) throws IOException {
        Files.writeString(DIR.resolve(name), text);
        LOGGER.info("双客户端验收 {} {} {}", ROLE, name, text);
    }

    @SubscribeEvent
    public static void captureFrame(RenderFrameEvent.Post event) {
        if (capture != null) {
            Minecraft mc = Minecraft.getInstance();
            Screenshot.grab(DIR.toFile(), capture + ".png", mc.gameRenderer.mainRenderTarget(), 1, ClientPlaythrough::captured);
            capture = null;
        }
    }

    private static void captured(Component result) {
        LOGGER.info("双客户端画面 {}", result.getString());
    }
}
