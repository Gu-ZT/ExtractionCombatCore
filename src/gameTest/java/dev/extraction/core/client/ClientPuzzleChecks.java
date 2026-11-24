package dev.extraction.core.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import dev.extraction.core.CabinetEntity;
import dev.extraction.core.ExtractionCore;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
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
import net.neoforged.neoforge.client.event.sound.PlaySoundSourceEvent;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** 在独立开发世界中通过正式界面和服务器连接完成内置锁与附属小游戏。 */
@EventBusSubscriber(modid = "extractioncombatcore", value = Dist.CLIENT)
public final class ClientPuzzleChecks {
    private static final boolean ENABLED = System.getProperty("extraction.playtest", "").equals("puzzles");
    private static final Path DIR = Path.of(System.getProperty("extraction.playtest.dir", ".")).toAbsolutePath();
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<String> TYPES = Boolean.getBoolean("extraction.playtest.addonOnly") ? List.of("example:dial")
        : List.of("password", "audio_morse", "pattern_morse", "rolling_lock", "accelerated_decode", "example:dial");
    private static final List<Integer> DIAL_TURNS = new ArrayList<>();
    private static final String SOS = "...---...";
    private static final BlockPos POS = new BlockPos(8, 80, 8);
    private static final StringBuffer TONES = new StringBuffer();
    private static final StringBuilder RESULTS = new StringBuilder();
    private static Stage stage = Stage.START;
    private static int index;
    private static long since = System.nanoTime();
    private static int step;
    private static int ticks;
    private static int delay;
    private static int lastElapsed = -1;
    private static int frozenTime;
    private static int pause;
    private static CompoundTag lastRollView;
    private static int rollViewTick;
    private static int lastColumn = -1;
    private static int rollPressedAt = -100;
    private static String capture;
    private static boolean stopped;
    private static CompletableFuture<Void> fixture;
    private static InputConstants.Key originalAction;

    private enum Stage { START, PREPARE, OPEN, ROUTE, OPERATE }

    private ClientPuzzleChecks() {
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!ENABLED || stopped) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        try {
            if (Duration.ofNanos(System.nanoTime() - since).toMinutes() >= 5) {
                throw new IllegalStateException("等待 " + TYPES.get(index) + " 的 " + stage + "/" + step + " 超时，音符 " + TONES);
            }
            ticks++;
            drive(mc);
        } catch (Exception | AssertionError failure) {
            LOGGER.error("客户端锁具验收失败", failure);
            finish(mc, "failed", failure.toString());
        }
    }

    private static void drive(Minecraft mc) throws IOException {
        if (stage == Stage.START) {
            mc.options.pauseOnLostFocus = false;
            if (mc.getOverlay() != null || !(mc.screen instanceof TitleScreen || mc.screen instanceof AccessibilityOnboardingScreen)) {
                return;
            }
            Files.createDirectories(DIR);
            registerDial();
            mc.getTutorial().setStep(TutorialSteps.NONE);
            originalAction = ExtractionClient.ACTION.getKey();
            ExtractionClient.ACTION.setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_R));
            KeyMapping.resetMapping();
            stage = Stage.PREPARE;
            LevelSettings settings = new LevelSettings("锁具验收", GameType.SURVIVAL, false, Difficulty.PEACEFUL, true,
                new GameRules(), WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel("extraction-puzzles-" + System.currentTimeMillis(), settings,
                new WorldOptions(165, false, false), registries -> registries.registryOrThrow(Registries.WORLD_PRESET)
                    .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), new TitleScreen());
            return;
        }
        if (mc.level == null || mc.player == null) {
            return;
        }
        String type = TYPES.get(index);
        BlockPos cabinetPos = POS.offset(index * 5, 0, 0);
        Vec3 playerPos = new Vec3(cabinetPos.getX() + .4, cabinetPos.getY(), cabinetPos.getZ() + 2.4);
        if (ticks % 160 == 0) {
            String screenName = mc.screen == null ? "世界" : mc.screen.getClass().getSimpleName();
            String details = mc.screen instanceof LootScreen loot
                ? loot.view.getString("mode") + "/" + loot.view.getCompound("challenge").getString("type")
                    + " " + loot.view.getString("message") : "";
            LOGGER.info("锁具 {} 阶段 {}/{}，屏幕 {} {}，柜体 {}，距测试站位 {}", type, stage, step, screenName,
                details, mc.level.getBlockState(cabinetPos), mc.player.position().distanceTo(playerPos));
        }
        if (stage == Stage.PREPARE && mc.screen == null) {
            IntegratedServer server = mc.getSingleplayerServer();
            fixture = server.submit(() -> {
                ServerLevel level = server.overworld();
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                for (BlockPos p : BlockPos.betweenClosed(cabinetPos.offset(-2, -1, -2), cabinetPos.offset(2, -1, 3))) {
                    level.setBlockAndUpdate(p, Blocks.STONE.defaultBlockState());
                }
                Block cabinet = ExtractionCore.CABINET.get();
                level.setBlockAndUpdate(cabinetPos, cabinet.defaultBlockState());
                ResourceLocation definition = switch (type) {
                    case "accelerated_decode" -> ExtractionCore.id("service_cabinet");
                    case "example:dial" -> ResourceLocation.parse("example:dial_cabinet");
                    default -> ExtractionCore.id(type);
                };
                ((CabinetEntity) level.getBlockEntity(cabinetPos)).bind(definition);
                if (type.equals("example:dial")) {
                    player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.COMPASS));
                }
                level.setBlockAndUpdate(cabinetPos.west(), Blocks.REDSTONE_BLOCK.defaultBlockState());
                player.connection.teleport(playerPos.x, playerPos.y, playerPos.z, 180, 18);
                player.getInventory().selected = 0;
                player.getInventory().setItem(0, type.equals("accelerated_decode") ? new ItemStack(ExtractionCore.DECODER.get()) : ItemStack.EMPTY);
                player.inventoryMenu.broadcastChanges();
            });
            step = 0;
            delay = 0;
            lastElapsed = -1;
            TONES.setLength(0);
            stage = Stage.OPEN;
            since = System.nanoTime();
            LOGGER.info("准备验收锁具 {}", type);
            return;
        }
        // 收到该柜的区块数据与传送后再右键，服务端才已按顺序收到本次传送的确认。
        if (stage == Stage.OPEN && fixture.isDone() && mc.screen == null && mc.player.position().distanceToSqr(playerPos) < .1
            && mc.level.getBlockState(cabinetPos).is(ExtractionCore.CABINET.get())) {
            fixture.join();
            Vec3 point = Vec3.atCenterOf(cabinetPos).add(0, 0, .5);
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(point, Direction.SOUTH, cabinetPos, false));
            LOGGER.info("客户端已到达 {} 并右键 {}", playerPos, cabinetPos);
            stage = Stage.ROUTE;
            return;
        }
        if (!(mc.screen instanceof LootScreen screen)) {
            return;
        }
        CompoundTag challenge = screen.view.getCompound("challenge");
        if (stage == Stage.ROUTE) {
            check(screen.view.getString("mode").equals("lock"), "演示柜初始没有上锁 " + type);
            LOGGER.info("选择 {} 的真实路线 {}", type, screen.view.get("routes"));
            if (type.equals("accelerated_decode")) {
                screen.keyPressed(GLFW.GLFW_KEY_DOWN, 0, 0);
            }
            screen.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
            stage = Stage.OPERATE;
        } else if (stage == Stage.OPERATE && screen.view.getString("mode").equals("storage")) {
            if (type.equals("rolling_lock")) {
                check(step >= 3, "未验证错误暂停和锁定进度");
            } else if (type.equals("accelerated_decode")) {
                check(lastElapsed >= 396 && mc.player.getMainHandItem().is(ExtractionCore.DECODER.get()),
                    "译码器自然等待或工具保留未通过");
            }
            if (type.equals("example:dial")) {
                check(DIAL_TURNS.equals(List.of(0, 1)), "转盘控件没有依次收到两份服务端视图");
            }
            RESULTS.append(type).append(" 通过\n");
            LOGGER.info("客户端锁具完成 {}", type);
            screen.onClose();
            if (++index == TYPES.size()) {
                finish(mc, "success", RESULTS.toString());
            } else {
                stage = Stage.PREPARE;
            }
        } else if (stage == Stage.OPERATE && challenge.getString("type").equals(type)) {
            switch (type) {
                case "example:dial" -> dial(screen, challenge);
                case "password" -> password(screen, challenge);
                case "audio_morse" -> audio(screen, challenge);
                case "pattern_morse" -> pattern(screen, challenge);
                case "rolling_lock" -> rolling(screen, challenge);
                case "accelerated_decode" -> {
                    int elapsed = challenge.getInt("elapsed");
                    check(elapsed >= lastElapsed, "自然破译进度倒退");
                    lastElapsed = elapsed;
                    if (elapsed >= 160 && step == 0) {
                        capture = "puzzle-passive-decoder";
                        step = 1;
                    }
                }
                default -> throw new IllegalStateException(type);
            }
        }
    }

    private static void registerDial() {
        PuzzleWidgets.register(ResourceLocation.parse("example:dial"), (context, x, y, width, height) ->
            Button.builder(Component.literal("转动刻度盘"), button -> {
                CompoundTag snapshot = context.view();
                check(snapshot.contains("turns"), "转盘控件缺少公开的圈数");
                DIAL_TURNS.add(snapshot.getInt("turns"));
                CompoundTag input = new CompoundTag();
                input.putBoolean("turn", true);
                context.send(input);
            }).bounds(x + width / 2 - 50, y + height / 2 - 10, 100, 20).build());
    }

    private static void dial(LootScreen screen, CompoundTag challenge) {
        int turns = challenge.getCompound("view").getInt("turns");
        if (step < 2 && turns == step) {
            check(screen.mouseClicked(screen.width / 2, screen.height / 2, 0), "转盘控件没有接收鼠标点击");
            screen.mouseReleased(screen.width / 2, screen.height / 2, 0);
            step++;
            check(DIAL_TURNS.size() == step, "转盘控件没有提交输入");
        }
    }

    private static void password(LootScreen screen, CompoundTag challenge) {
        if (step == 0) {
            enter(screen, "472");
            step = 1;
        } else if (step == 1 && challenge.getInt("pause") > 0) {
            check(screen.answer().isEmpty(), "错误密码没有清空");
            step = 2;
        } else if (step == 2 && challenge.getInt("pause") == 0) {
            type(screen, "0472");
            capture = "puzzle-password";
            step = 3;
        } else if (step == 3 && capture == null) {
            check(screen.answer().equals("0472"), "输入框丢失前导零");
            screen.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
            step = 4;
        }
    }

    private static void audio(LootScreen screen, CompoundTag challenge) {
        check(challenge.getString("signals").equals("... --- ..."), "摩斯题面不是预期的点划分组");
        if (step == 0 && !TONES.isEmpty()) {
            capture = "puzzle-morse-receiver";
            step = 1;
        } else if (step == 1 && TONES.length() >= 9) {
            check(TONES.toString().equals(SOS), "第一次实际播放的音符不符 " + TONES);
            delay = ticks + 7;
            step = 2;
        } else if (step == 2 && ticks >= delay) {
            int left = Math.max(12, (screen.width - 300) / 2);
            int cy = Math.max(56, screen.height / 2 - 22);
            screen.mouseClicked(left + 8, cy - 6, 0);
            screen.mouseReleased(left + 8, cy - 6, 0);
            step = 3;
        } else if (step == 3 && TONES.length() >= 18) {
            check(TONES.toString().equals(SOS + SOS) && challenge.getInt("replay") == 1, "重播改变或缺失音符 " + TONES);
            enter(screen, "SOS");
            step = 4;
        }
    }

    private static void pattern(LootScreen screen, CompoundTag challenge) {
        check(!challenge.getString("dot").equals(challenge.getString("dash")), "点划图案相同");
        int left = Math.max(12, (screen.width - 300) / 2);
        int cy = Math.max(56, screen.height / 2 - 22);
        if (step == 0) {
            capture = "puzzle-morse-plaque";
            step = 1;
        } else if (step == 1 && capture == null) {
            screen.mouseClicked(screen.width - left - 20, cy - 5, 0);
            capture = "puzzle-morse-chart";
            step = 2;
        } else if (step == 2 && capture == null) {
            screen.mouseClicked(left + 10, screen.height - 38, 0);
            enter(screen, "SOS");
            step = 3;
        }
    }

    private static void rolling(LootScreen screen, CompoundTag challenge) {
        int serverElapsed = challenge.getInt("elapsed");
        int locked = challenge.getInt("locked");
        int pauseTicks = challenge.getInt("pause");
        if (lastRollView != challenge) {
            lastRollView = challenge;
            rollViewTick = ticks;
        }
        // 每三刻才有一次视图，测试指针必须与屏幕一样在两份视图之间继续滚动。
        int elapsed = serverElapsed + (pauseTicks > 0 ? 0 : Math.min(2, ticks - rollViewTick));
        if (locked != lastColumn) {
            LOGGER.info("滚动锁已锁定位数 {}", locked);
            lastColumn = locked;
        }
        if (step == 0) {
            check(!screen.keyPressed(GLFW.GLFW_KEY_SPACE, 0, 0), "滚动锁仍接受旧挑战键");
            step = 1;
        }
        if (step == 2 && pauseTicks > 0) {
            check(locked == 1, "误按后锁定位归零");
            pause = pauseTicks;
            frozenTime = elapsed;
            operate(screen);
            capture = "puzzle-rolling-pause";
            step = 3;
            return;
        }
        if (step == 3 && pauseTicks > 0) {
            check(locked == 1 && elapsed == frozenTime && pauseTicks <= pause, "重复按键重置暂停或改变字符位置");
            pause = pauseTicks;
            return;
        }
        if (step == 3 && pauseTicks == 0) {
            step = 4;
        }
        if (pauseTicks > 0 || lastElapsed == elapsed || step == 2 || ticks - rollPressedAt < 6) {
            return;
        }
        lastElapsed = elapsed;
        boolean hitNow = centered(challenge, elapsed);
        boolean hitNext = centered(challenge, elapsed + 1);
        if (locked == 1 && step == 1 && !hitNow && !hitNext && !centered(challenge, elapsed + 2) && !centered(challenge, Math.max(0, elapsed - 1))) {
            operate(screen);
            rollPressedAt = ticks;
            step = 2;
        } else if (!(locked == 1 && step == 1) && hitNow && hitNext) {
            operate(screen);
            rollPressedAt = ticks;
        }
    }

    private static boolean centered(CompoundTag challenge, int elapsed) {
        int column = challenge.getInt("locked");
        double phase = elapsed * challenge.getIntArray("directions")[column] / (double)challenge.getIntArray("periods")[column];
        int center = (int)Math.floor(phase + .5);
        String alphabet = challenge.getString("alphabet");
        return Math.abs(center - phase) <= .30 && alphabet.charAt(Math.floorMod(center, alphabet.length())) == challenge.getString("target").charAt(column);
    }

    private static void operate(LootScreen screen) {
        check(screen.keyPressed(GLFW.GLFW_KEY_R, 0, 0), "重绑挑战键未被识别");
        screen.keyReleased(GLFW.GLFW_KEY_R, 0, 0);
    }

    private static void type(LootScreen screen, String text) {
        for (char c : text.toCharArray()) {
            check(screen.charTyped(c, 0), "输入框未接收字符 " + c);
        }
        check(screen.answer().equals(text), "输入框文本不符");
    }

    private static void enter(LootScreen screen, String text) {
        type(screen, text);
        screen.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
    }

    @SubscribeEvent
    public static void sound(PlaySoundSourceEvent event) {
        if (ENABLED) {
            ResourceLocation id = event.getSound().getLocation();
            if (id.getNamespace().equals(ExtractionCore.ID)) {
                if (id.getPath().equals("morse_dot")) {
                    TONES.append('.');
                } else if (id.getPath().equals("morse_dash")) {
                    TONES.append('-');
                }
            }
        }
    }

    @SubscribeEvent
    public static void frame(RenderFrameEvent.Post event) {
        if (capture != null) {
            Minecraft mc = Minecraft.getInstance();
            Screenshot.grab(DIR.toFile(), capture + ".png", mc.getMainRenderTarget(), result -> LOGGER.info("锁具画面 {}", result.getString()));
            capture = null;
        }
    }

    private static void check(boolean condition, String reason) {
        if (!condition) {
            throw new AssertionError(reason);
        }
    }

    private static void finish(Minecraft mc, String state, String result) {
        stopped = true;
        if (originalAction != null) {
            ExtractionClient.ACTION.setKey(originalAction);
            KeyMapping.resetMapping();
        }
        try {
            Files.writeString(DIR.resolve("puzzles." + state), result);
        } catch (IOException e) {
            LOGGER.error("无法写入锁具验收结果", e);
        }
        mc.stop();
    }
}
