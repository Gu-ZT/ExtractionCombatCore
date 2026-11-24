package dev.extraction.core.client;

import dev.extraction.core.GameAccess;
import dev.extraction.core.InventoryGameTests;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class ClientInventoryChecks {
    private static final int[] COUNTS = {3, 0, 9};
    private static int step;
    private static boolean requested;
    private static boolean observed;

    private ClientInventoryChecks() {}

    static boolean tick(Minecraft game, boolean host, Path directory) throws IOException {
        if (step == COUNTS.length) {
            return true;
        }
        int count = COUNTS[step];
        boolean main = count == 9;
        if (host && !requested) {
            game.getSingleplayerServer().execute(() -> InventoryGameTests.setInventoryPolicy(main, count));
            requested = true;
        }
        String marker = ".inventory-" + count;
        if (!observed) {
            var policy = GameAccess.policy(game.player);
            if (policy.hotbar() != count || policy.mainEnabled() != main) {
                return false;
            }
            if (ExtractionClient.restricted(0) != (count == 0)
                || ExtractionClient.restricted(3) != (count < 4) || ExtractionClient.restricted(9) == main) {
                throw new AssertionError("原版栏遮罩未使用服务端库存策略 " + count);
            }
            var inventory = game.player.getInventory();
            inventory.selected = 2;
            if (count == 0 && !inventory.getSelected().isEmpty()) {
                throw new AssertionError("服务端将快捷栏改为零后，客户端主手仍可取物");
            }
            long window = game.getWindow().getWindow();
            var scroll = GLFW.glfwSetScrollCallback(window, null);
            if (scroll == null) {
                throw new AssertionError("未发现游戏注册的滚轮回调");
            }
            GLFW.glfwSetScrollCallback(window, scroll);
            scroll.invoke(window, 0, -1);
            if (inventory.selected != (count == 9 ? 3 : 0)) {
                throw new AssertionError("滚轮未按开放的快捷栏数量回绕 " + count);
            }
            inventory.selected = 0;
            Files.writeString(directory.resolve((host ? "host" : "guest") + marker), "主背包=" + main + " 快捷栏=" + count);
            observed = true;
        }
        if (Files.exists(directory.resolve((host ? "guest" : "host") + marker))) {
            step++;
            requested = false;
            observed = false;
        }
        return step == COUNTS.length;
    }
}
