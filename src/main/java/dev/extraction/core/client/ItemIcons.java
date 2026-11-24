package dev.extraction.core.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import dev.extraction.core.rules.Shape;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

final class ItemIcons {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<ImageKey, Optional<Image>> IMAGES = new HashMap<>();

    private ItemIcons() {
    }

    static void reload() {
        IMAGES.clear();
    }

    private record ImageKey(String field, String id, int cols, int rows) {
    }

    private record Image(ResourceLocation file, int width, int height) {
    }

    private static Optional<Image> image(String field, String id, int cols, int rows) {
        if (id == null || id.isEmpty()) {
            return Optional.empty();
        }
        return IMAGES.computeIfAbsent(new ImageKey(field, id, cols, rows), key -> {
            ResourceLocation name = ResourceLocation.tryParse(key.id());
            if (name != null) {
                ResourceLocation path = name.withPath(p -> "textures/" + p + ".png");
                try (InputStream source = Minecraft.getInstance().getResourceManager().open(path);
                     NativeImage texture = NativeImage.read(source)) {
                    if (cols == 0 || (long) texture.getWidth() * rows == (long) texture.getHeight() * cols) {
                        return Optional.of(new Image(path, texture.getWidth(), texture.getHeight()));
                    }
                } catch (IOException e) {
                    LOGGER.warn("无法读取 {} 的资源 {}，使用物品原图", key.field(), name, e);
                    return Optional.empty();
                }
            }
            LOGGER.warn("{} 的资源 {} 无效或宽高比不符，使用物品原图", key.field(), key.id());
            return Optional.empty();
        });
    }

    static boolean slot(GuiGraphics g, String id, int x, int y) {
        Image icon = image("icons.vanilla_slot", id, 0, 0).orElse(null);
        if (icon == null) {
            return false;
        }
        double scale = 16d / Math.max(icon.width(), icon.height());
        int w = Math.max(1, (int) Math.round(icon.width() * scale));
        int h = Math.max(1, (int) Math.round(icon.height() * scale));
        int left = x + (16 - w) / 2;
        int top = y + (16 - h) / 2;
        g.blit(icon.file(), left, top, left + w, top + h, 0, 1, 0, 1);
        return true;
    }

    static boolean grid(GuiGraphics g, StorageView.Entry e, int x, int y, int rotation) {
        Shape base = e.shape();
        Image icon = image("icons.grid", e.gridIcon(), base.width(), base.height()).orElse(null);
        int cell = StorageView.CELL;
        int w = base.width() * cell;
        int h = base.height() * cell;
        g.pose().pushMatrix();
        g.pose().translate(x + (rotation == 1 ? h : rotation == 2 ? w : 0),
            y + (rotation == 2 ? h : rotation == 3 ? w : 0));
        g.pose().rotate((float) (Math.PI / 2 * rotation));
        if (icon != null) {
            // 按连续占格绘制对应 UV，孔位保留给夹在其中的其他物品。
            for (int row = 0; row < base.height(); row++) {
                String cells = base.rows().get(row);
                for (int col = 0; col < cells.length();) {
                    if (cells.charAt(col) != 'X') {
                        col++;
                        continue;
                    }
                    int end = col + 1;
                    while (end < cells.length() && cells.charAt(end) == 'X') {
                        end++;
                    }
                    float u1 = (float) col / base.width();
                    float v1 = (float) row / base.height();
                    float u2 = (float) end / base.width();
                    float v2 = (float) (row + 1) / base.height();
                    g.blit(icon.file(), col * cell, row * cell, end * cell, (row + 1) * cell, u1, u2, v1, v2);
                    col = end;
                }
            }
        }
        g.pose().popMatrix();
        if (icon == null) {
            int first = 0;
            while (!base.contains(first % base.width(), first / base.width())) {
                first++;
            }
            Cell p = Cell.turn(first % base.width(), first / base.width(), base, rotation);
            g.renderItem(e.stack(), x + p.x() * cell + 1, y + p.y() * cell + 1);
        }
        return icon != null;
    }

    record Cell(int x, int y) {
        static Cell turn(int x, int y, Shape shape, int rotation) {
            return switch (Math.floorMod(rotation, 4)) {
                case 1 -> new Cell(shape.height() - 1 - y, x);
                case 2 -> new Cell(shape.width() - 1 - x, shape.height() - 1 - y);
                case 3 -> new Cell(y, shape.width() - 1 - x);
                default -> new Cell(x, y);
            };
        }
    }
}
