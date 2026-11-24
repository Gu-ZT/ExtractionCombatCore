package dev.extraction.core.client;

import net.minecraft.core.UUIDUtil;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import dev.extraction.core.ExtractionCore;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.BackupConfirmScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

@EventBusSubscriber(modid = "extractioncombatcore", value = Dist.CLIENT)
public final class ClientVisualChecks {
    private enum Phase {
        START, ROTATE, PLACED, NUGGET_MOVED, HOLE, ICONS, INVENTORY, EXIT, REOPEN, JOIN, RESTORED, CABINET
    }

    private static final int[] ROTATIONS = {0, 1, 2, 3, 1};
    private static Phase phase = Phase.START;
    private static boolean active;
    private static boolean frame;
    private static int direction;
    private static int pointerX;
    private static int pointerY;
    private static UUID hookId;
    private static UUID nuggetId;
    private static StorageView.Entry sample;
    private static boolean rendered;
    private static Throwable renderFailure;
    private static Path destination;

    private ClientVisualChecks() {
    }

    static boolean tick(Minecraft mc, Path dir, String world) throws IOException {
        active = true;
        destination = dir.resolve("screenshots");
        Files.createDirectories(destination);
        if (renderFailure != null) {
            throw new AssertionError("客户端绘制未通过", renderFailure);
        }
        if (phase == Phase.INVENTORY && frame) {
            next(Phase.EXIT);
        }
        if (phase == Phase.EXIT) {
            mc.level.disconnect(net.minecraft.network.chat.Component.literal("退出并重进验收世界"));
            mc.disconnect(new TitleScreen(), false);
            mc.setScreen(new TitleScreen());
            next(Phase.REOPEN);
            return false;
        }
        if (phase == Phase.REOPEN) {
            if (mc.screen instanceof TitleScreen title && mc.getOverlay() == null) {
                next(Phase.JOIN);
                mc.createWorldOpenFlows().openWorld(world, () -> mc.setScreen(title));
            }
            return false;
        }
        if (phase == Phase.JOIN) {
            if (mc.screen instanceof BackupConfirmScreen confirm) {
                confirm.children().stream().filter(e -> e instanceof Button).map(e -> (Button)e).findFirst().orElseThrow().onPress();
            }
            if (mc.player != null && mc.screen == null && mc.getOverlay() == null) {
                KeyMapping.click(ExtractionClient.OPEN.getKey());
                next(Phase.RESTORED);
            }
            return false;
        }
        if (!(mc.screen instanceof LootScreen screen) || screen.storage == null) {
            return false;
        }
        switch (phase) {
            case START -> {
                StorageView.Hit hook = find(screen, h -> h.panel().equals("cabinet") && h.entry() != null && h.entry().stack().is(ExtractionCore.HOOK.get()));
                if (hook == null) {
                    return false;
                }
                sample = hook.entry();
                hookId = sample.id();
                screen.mouseClicked(hook.centerX(), hook.centerY(), 0);
                require(screen.drag != null, "未抓起挂钩");
                aim(screen);
                next(Phase.ROTATE);
            }
            case ROTATE -> {
                if (!frame) {
                    return false;
                }
                require(screen.drag.rotation == ROTATIONS[direction], "指针靠边后的挂钩方向不符");
                CompoundTag move = screen.action("move");
                require(screen.storage.destination(move, screen.drag, pointerX, pointerY), "挂钩预览没有目标格");
                StorageView.Hit touched = screen.storage.hit(pointerX, pointerY);
                ItemIcons.Cell anchor = ItemIcons.Cell.turn(screen.drag.grip.x(), screen.drag.grip.y(), sample.shape(), screen.drag.rotation);
                require(move.getIntOr("x", 0) + anchor.x() == touched.cellX() && move.getIntOr("y", 0) + anchor.y() == touched.cellY(),
                    "转向后原抓取格偏离鼠标");
                screen.storage.destination(move, screen.drag, touched.centerX(), touched.centerY());
                require(screen.drag.rotation == ROTATIONS[direction], "移到格子中央后跳转方向");
                screen.storage.destination(move, screen.drag, touched.centerX() - 8, touched.centerY() - 8);
                require(screen.drag.rotation == ROTATIONS[direction], "进入两边等距位置后跳转方向");
                if (++direction < ROTATIONS.length) {
                    aim(screen);
                    frame = false;
                    rendered = false;
                } else {
                    screen.mouseReleased(pointerX, pointerY, 0);
                    next(Phase.PLACED);
                }
            }
            case PLACED -> {
                if (!at(screen, hookId, 1, 0, 1)) {
                    return false;
                }
                StorageView.Hit nugget = find(screen, h -> h.panel().equals("bag") && h.entry() != null && h.entry().stack().is(Items.IRON_NUGGET));
                StorageView.Hit hole = bagCell(screen, 2, 0);
                if (nugget == null || hole == null) {
                    return false;
                }
                require(hole.entry() == null, "挂钩占用了其形状里的空格");
                nuggetId = nugget.entry().id();
                screen.mouseClicked(nugget.centerX(), nugget.centerY(), 0);
                screen.mouseDragged(hole.centerX(), hole.centerY(), 0, 36, 0);
                screen.mouseReleased(hole.centerX(), hole.centerY(), 0);
                next(Phase.NUGGET_MOVED);
            }
            case NUGGET_MOVED -> {
                if (!at(screen, nuggetId, 2, 0, 0)) {
                    return false;
                }
                StorageView.Hit hole = bagCell(screen, 2, 0);
                StorageView.Hit occupied = bagCell(screen, 1, 0);
                if (hole == null || occupied == null) {
                    return false;
                }
                require(hole.entry().id().equals(nuggetId) && occupied.entry().id().equals(hookId), "凹口命中与实际占格不符");
                screen.mouseClicked(hole.centerX(), hole.centerY(), 0);
                require(screen.drag != null && screen.drag.source.entry().id().equals(nuggetId), "点击凹口没有单独拿起铁粒");
                screen.mouseReleased(hole.centerX(), hole.centerY(), 0);
                next(Phase.HOLE);
            }
            case HOLE -> {
                if (frame) {
                    next(Phase.ICONS);
                }
            }
            case ICONS -> {
                if (frame) {
                    screen.onClose();
                    mc.setScreen(new InventoryScreen(mc.player));
                    next(Phase.INVENTORY);
                }
            }
            case RESTORED -> {
                require(at(screen, nuggetId, 2, 0, 0) && at(screen, hookId, 1, 0, 1), "重载世界后背包实例、朝向或位置发生变化");
                require(StorageView.compounds(screen.view, "packs").stream().anyMatch(p -> p.getIntOr("slot", 0) == 41 && p.getBooleanOr("selected", false)),
                    "重新进档后装备位或所选背包丢失");
                screen.onClose();
                ClientPlaythrough.useCabinet(mc);
                next(Phase.CABINET);
            }
            case CABINET -> {
                require(screen.view.getStringOr("mode", "").equals("storage"), "重新进档后维修柜再次上锁");
                require(screen.view.contains("cabinet") && StorageView.compounds(screen.view.getCompoundOrEmpty("cabinet"), "items").isEmpty(),
                    "重新进档后柜子产生了重复物品");
                active = false;
                return true;
            }
            default -> {
            }
        }
        return false;
    }

    private static void next(Phase target) {
        phase = target;
        rendered = false;
        frame = false;
        LogUtils.getLogger().info("客户端画面验收 {}", phase);
    }

    private static boolean at(LootScreen screen, UUID id, int x, int y, int rotation) {
        return StorageView.compounds(screen.view.getCompoundOrEmpty("bag"), "items").stream().anyMatch(e -> e.read("id", UUIDUtil.CODEC).orElseThrow().equals(id)
            && e.getIntOr("x", 0) == x && e.getIntOr("y", 0) == y && e.getIntOr("rotation", 0) == rotation);
    }

    private static StorageView.Hit find(LootScreen screen, Predicate<StorageView.Hit> predicate) {
        for (int y = 45; y < screen.height - 21; y += 5) {
            for (int x = 6; x < screen.width - 4; x += 5) {
                StorageView.Hit h = screen.storage.hit(x, y);
                if (h != null && predicate.test(h)) {
                    return h;
                }
            }
        }
        return null;
    }

    private static StorageView.Hit bagCell(LootScreen screen, int x, int y) {
        return find(screen, h -> h.panel().equals("bag") && h.sectionIndex() == 0 && h.cellX() == x && h.cellY() == y);
    }

    private static void aim(LootScreen screen) {
        int rotation = ROTATIONS[direction];
        ItemIcons.Cell grip = ItemIcons.Cell.turn(screen.drag.grip.x(), screen.drag.grip.y(), sample.shape(), rotation);
        StorageView.Hit h = direction == ROTATIONS.length - 1 ? bagCell(screen, 1 + grip.x(), grip.y()) : bagCell(screen, 2, 2);
        require(h != null, "主仓目标超出可见视口");
        pointerX = h.centerX() + (rotation == 1 ? 8 : rotation == 3 ? -8 : 0);
        pointerY = h.centerY() + (rotation == 2 ? 8 : rotation == 0 ? -8 : 0);
        screen.mouseDragged(pointerX, pointerY, 0, 20, 20);
    }

    @SubscribeEvent
    public static void pointer(ScreenEvent.Render.Pre event) {
        if (active && phase == Phase.ROTATE && renderFailure == null && event.getScreen() instanceof LootScreen screen) {
            event.setCanceled(true);
            try {
                screen.render(event.getGuiGraphics(), pointerX, pointerY, event.getPartialTick());
                rendered = true;
            } catch (RuntimeException | AssertionError ex) {
                renderFailure = ex;
            }
        }
    }

    @SubscribeEvent
    public static void draw(ScreenEvent.Render.Post event) {
        if (!active || frame || renderFailure != null) {
            return;
        }
        try {
            if (event.getScreen() instanceof LootScreen screen) {
                if (phase == Phase.ICONS) {
                    event.getGuiGraphics().nextStratum();
                    drawIcons(event.getGuiGraphics(), screen);
                } else if (phase != Phase.HOLE) {
                    return;
                }
                rendered = true;
            } else if (phase == Phase.INVENTORY && event.getScreen() instanceof InventoryScreen) {
                rendered = true;
            }
        } catch (RuntimeException | AssertionError ex) {
            renderFailure = ex;
        }
    }

    private static void drawIcons(GuiGraphics g, LootScreen screen) {
        Minecraft mc = Minecraft.getInstance();
        String missing = "extractioncombatcore:item/missing_visual_test";
        List<String> labels = List.of("完整资源", "缺少网格图", "网格图比例错误", "缺少单格图");
        g.fill(1, 1, screen.width - 1, screen.height - 1, 0xff19251f);
        for (int i = 0; i < 4; i++) {
            int x = 8 + i % 2 * (screen.width / 2);
            int y = 10 + (i / 2) * (screen.height / 2);
            g.drawString(mc.font, labels.get(i), x, y, 0xffe3ead9, false);
            String grid = i == 1 ? missing : i == 2 ? sample.slotIcon() : sample.gridIcon();
            String slot = i == 3 ? missing : sample.slotIcon();
            StorageView.Entry e = new StorageView.Entry(sample.id(), sample.stack(), sample.shape(), sample.placed(), true,
                grid, slot, sample.quality(), sample.color(), 0, 0, 0, -1, false);
            g.fill(x, y + 15, x + 90, y + 51, 0xff304335);
            g.fill(x + 98, y + 15, x + 116, y + 33, 0xff304335);
            boolean gridDrawn = ItemIcons.grid(g, e, x, y + 15, 0);
            boolean slotDrawn = ItemIcons.slot(g, slot, x + 99, y + 16);
            if (!slotDrawn) {
                g.renderItem(e.stack(), x + 99, y + 16);
            }
            require(gridDrawn == (i == 0 || i == 3) && slotDrawn == (i != 3), "单项贴图回退影响了另一位置 " + labels.get(i));
        }
    }

    private static boolean framePending;

    @SubscribeEvent
    public static void sampleFrame(RenderFrameEvent.Post event) {
        if (!active || !rendered || frame || framePending || renderFailure != null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        framePending = true;
        Screenshot.takeScreenshot(mc.getMainRenderTarget(), image -> {
            try (image) {
                String name = switch (phase) {
                    case ROTATE -> "host-rotation-" + direction;
                    case ICONS -> "host-icon-fallbacks";
                    case INVENTORY -> "host-vanilla-slot";
                    default -> "host-interlocked-items";
                };
                image.writeToFile(destination.resolve(name + ".png"));
                if (phase == Phase.INVENTORY) {
                    verifyVanilla(mc, (InventoryScreen) mc.screen, image);
                }
                frame = true;
            } catch (IOException | RuntimeException | AssertionError ex) {
                renderFailure = ex;
            }
            framePending = false;
        });
    }

    private static void verifyVanilla(Minecraft mc, InventoryScreen screen, NativeImage actual) throws IOException {
        Slot slot = screen.getMenu().getSlot(37);
        require(slot.getItem().is(ExtractionCore.HOOK.get()), "快捷栏第二格缺少测试挂钩");
        int left = screen.getGuiLeft() + slot.x;
        int top = screen.getGuiTop() + slot.y;
        ResourceLocation id = ResourceLocation.parse(sample.slotIcon()).withPath(p -> "textures/" + p + ".png");
        int total = 0;
        int matches = 0;
        int distinctive = 0;
        double scale = mc.getWindow().getGuiScale();
        ResourceLocation model = ExtractionCore.id("textures/item/service_hook.png");
        try (InputStream input = mc.getResourceManager().open(id); NativeImage expected = NativeImage.read(input);
             InputStream modelInput = mc.getResourceManager().open(model); NativeImage held = NativeImage.read(modelInput)) {
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    int want = expected.getPixel(x, y);
                    if ((want >>> 24) < 240) {
                        continue;
                    }
                    if (held.getPixel(x, y) != want) {
                        distinctive++;
                    }
                    int px = (int) ((left + x + .5) * scale);
                    int py = (int) ((top + y + .5) * scale);
                    int got = actual.getPixel(px, py);
                    total++;
                    if (Math.abs((got & 255) - (want & 255)) <= 5 && Math.abs((got >>> 8 & 255) - (want >>> 8 & 255)) <= 5
                        && Math.abs((got >>> 16 & 255) - (want >>> 16 & 255)) <= 5) {
                        matches++;
                    }
                }
            }
        }
        require(distinctive >= 8, "单格贴图与手持贴图缺少可区分像素");
        require(matches > 0 && matches >= total * .9, "原版槽图像素不符 " + matches + "/" + total);
        LogUtils.getLogger().info("原版物品栏挂钩贴图匹配 {}/{} 个不透明像素", matches, total);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
