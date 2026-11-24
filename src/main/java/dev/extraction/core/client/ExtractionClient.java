package dev.extraction.core.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.extraction.core.GameAccess;
import dev.extraction.core.network.ExtractionNetwork;
import dev.extraction.core.rules.RuleBook;
import net.minecraft.core.UUIDUtil;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.lwjgl.glfw.GLFW;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@EventBusSubscriber(modid = "extractioncombatcore", value = Dist.CLIENT)
public final class ExtractionClient {
    private static final KeyMapping.Category CATEGORY = new KeyMapping.Category(Identifier.fromNamespaceAndPath("extractioncombatcore", "general"));
    static final KeyMapping OPEN = new KeyMapping("key.extractioncombatcore.open", GLFW.GLFW_KEY_B, CATEGORY);
    static final KeyMapping ACTION = new KeyMapping("key.extractioncombatcore.challenge", InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_SPACE, CATEGORY);
    private static UUID dismissed;
    private static final ByteArrayOutputStream POLICY_BYTES = new ByteArrayOutputStream();
    private static String pendingRules;
    private static boolean vanillaSlots;
    private static boolean policyReceived;

    private ExtractionClient() {
    }

    @EventBusSubscriber(modid = "extractioncombatcore", value = Dist.CLIENT)
    public static final class Registration {
        @SubscribeEvent
        public static void keys(RegisterKeyMappingsEvent event) {
            event.registerCategory(CATEGORY);
            event.register(OPEN);
            event.register(ACTION);
        }

        @SubscribeEvent
        public static void textures(AddClientReloadListenersEvent event) {
            event.addListener(Identifier.fromNamespaceAndPath("extractioncombatcore", "item_icons"), (ResourceManagerReloadListener) resources -> ItemIcons.reload());
        }
    }

    public static void receive(CompoundTag view) {
        Minecraft client = Minecraft.getInstance();
        String kind = view.getStringOr("kind", "");
        if (kind.equals("inventory_policy") || kind.equals("policy") && view.getIntOr("part", 0) == 0) {
            GameAccess.setClientPolicy(new GameAccess.InventoryPolicy(view.getBooleanOr("main_enabled", false), view.getIntOr("hotbar", 0)));
        }
        if (kind.equals("inventory_policy")) {
            return;
        }
        if (kind.equals("policy")) {
            int part = view.getIntOr("part", 0);
            if (part == 0) {
                POLICY_BYTES.reset();
            }
            POLICY_BYTES.writeBytes(view.getByteArray("rules_utf8").orElseThrow());
            if (part + 1 == view.getIntOr("parts", 0)) {
                pendingRules = POLICY_BYTES.toString(StandardCharsets.UTF_8);
                POLICY_BYTES.reset();
                applyPolicy(client);
            }
            return;
        }
        if (client.player == null || !view.read("session", UUIDUtil.CODEC).isPresent()) {
            return;
        }
        UUID id = view.read("session", UUIDUtil.CODEC).orElseThrow();
        if (view.getStringOr("kind", "").equals("closed")) {
            if (client.gui.screen() instanceof LootScreen open && id.equals(open.session())) {
                open.closeFromServer();
            }
        } else if (view.getStringOr("kind", "").equals("view") && !id.equals(dismissed)) {
            if (client.gui.screen() instanceof LootScreen open && id.equals(open.session())) {
                open.update(view);
            } else {
                client.gui.setScreen(new LootScreen(view));
            }
        }
    }

    private static void applyPolicy(Minecraft client) {
        if (client.player != null && pendingRules != null) {
            RuleBook.installPublicItems(pendingRules, client.player.registryAccess());
            pendingRules = null;
            policyReceived = true;
        }
    }

    static void dismissed(UUID id) {
        dismissed = id;
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        dismissed = null;
        pendingRules = null;
        POLICY_BYTES.reset();
        vanillaSlots = false;
        policyReceived = false;
        GameAccess.setClientPolicy(GameAccess.DEFAULT_POLICY);
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        applyPolicy(client);
        while (OPEN.consumeClick()) {
            if (client.player != null && client.gui.screen() == null) {
                requestOpen();
            }
        }
    }

    @SubscribeEvent
    public static void inventoryKey(ScreenEvent.KeyPressed.Pre event) {
        if (event.getScreen() instanceof InventoryScreen && OPEN.matches(event.getKeyEvent())) {
            requestOpen();
            event.setCanceled(true);
        }
    }

    private static void requestOpen() {
        CompoundTag message = new CompoundTag();
        message.putString("action", "open");
        ExtractionNetwork.send(message);
    }

    public static boolean drawVanillaIcon(GuiGraphicsExtractor g, ItemStack stack, int x, int y) {
        if (!vanillaSlots || !policyReceived || stack.isEmpty()) {
            return false;
        }
        return RuleBook.client().item(stack).slotIcon().map(id -> ItemIcons.slot(g, id.toString(), x, y)).orElse(false);
    }

    @SubscribeEvent
    public static void inventoryBackground(ScreenEvent.Render.Background event) {
        vanillaSlots = event.getScreen() instanceof AbstractContainerScreen<?>;
        if (event.getScreen() instanceof InventoryScreen screen) {
            int x = screen.getLeftPos() + 77;
            int y = screen.getTopPos() + 8;
            GuiGraphicsExtractor g = event.getGuiGraphics();
            g.fill(x - 1, y - 1, x + 17, y + 17, 0xff373737);
            g.fill(x, y, x + 17, y + 17, 0xffffffff);
            g.fill(x, y, x + 16, y + 16, 0xff8b8b8b);
            g.outline(x + 4, y + 4, 8, 10, 0xff666d68);
            g.outline(x + 6, y + 2, 4, 3, 0xff666d68);
            g.horizontalLine(x + 5, x + 10, y + 9, 0xff666d68);
        }
    }

    @SubscribeEvent
    public static void inventoryForeground(ScreenEvent.Render.Foreground event) {
        vanillaSlots = false;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !(event.getScreen() instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        GuiGraphicsExtractor g = event.getGuiGraphics();
        for (Slot slot : screen.getMenu().slots) {
            int i = slot.getContainerSlot();
            if (slot.container == client.player.getInventory() && restricted(i)) {
                int x = screen.getLeftPos() + slot.x;
                int y = screen.getTopPos() + slot.y;
                g.fill(x, y, x + 16, y + 16, slot.hasItem() ? 0x50272a29 : 0xad272a29);
                g.fill(x + 12, y + 1, x + 15, y + 3, 0xffcf8954);
            }
        }
    }

    static boolean restricted(int slot) {
        return !GameAccess.clientPolicy().enabled(slot);
    }

    @SubscribeEvent
    public static void hudPre(RenderGuiLayerEvent.Pre event) {
        vanillaSlots = event.getName().equals(VanillaGuiLayers.HOTBAR);
    }

    @SubscribeEvent
    public static void hudPost(RenderGuiLayerEvent.Post event) {
        vanillaSlots = false;
        int hotbar = GameAccess.clientPolicy().hotbar();
        if (!event.getName().equals(VanillaGuiLayers.HOTBAR) || hotbar == 9 || Minecraft.getInstance().gui.hud.isHidden()) {
            return;
        }
        GuiGraphicsExtractor g = event.getGuiGraphics();
        int x = g.guiWidth() / 2 - 91;
        int y = g.guiHeight() - 22;
        for (int slot = hotbar; slot < 9; slot++) {
            g.fill(x + 2 + slot * 20, y + 2, x + 20 + slot * 20, y + 20, 0xc8252c2a);
        }
    }
}
