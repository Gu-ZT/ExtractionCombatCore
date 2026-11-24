package dev.extraction.core.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.extraction.core.network.ExtractionNetwork;
import net.minecraft.core.UUIDUtil;
import net.minecraft.util.Util;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.entity.player.Input;
import org.lwjgl.glfw.GLFW;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class LootScreen extends Screen {
    private final UUID session;
    private final LockPanel lock = new LockPanel(this);
    CompoundTag view;
    StorageView storage;
    private EditBox edit;
    private @Nullable PuzzleRound puzzle;
    private String lastValidAnswer = "";
    StorageView.Drag drag;
    private long dragRevision;
    private StorageView.Hit lastClick;
    private long lastClickTime;
    private int startX;
    private int startY;
    private long clock;
    private boolean moving;
    private boolean closed;
    private boolean sneakInput;

    LootScreen(CompoundTag view) {
        super(tr("title"));
        session = view.read("session", UUIDUtil.CODEC).orElseThrow();
        update(view);
    }

    static Component tr(String key, Object... args) {
        return Component.translatable("gui.extractioncombatcore." + key, args);
    }

    UUID session() {
        return session;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private boolean locked() {
        return view.getStringOr("mode", "").equals("lock");
    }

    void update(CompoundTag next) {
        view = next;
        if (locked()) {
            lock.update(next, clock);
        } else {
            storage = new StorageView(next, storage);
        }
        positionAnswer();
        updatePuzzle();
    }

    @Override
    protected void init() {
        String previous = answer();
        edit = new EditBox(font, 0, 0, 90, 17, tr("answer"));
        edit.setMaxLength(32767);
        edit.setResponder(text -> {
            if (text.getBytes(StandardCharsets.UTF_8).length <= 32000) {
                lastValidAnswer = text;
            } else {
                edit.setValue(lastValidAnswer);
            }
        });
        edit.setValue(previous);
        edit.setHint(tr("answer"));
        edit.visible = false;
        addWidget(edit);
        positionAnswer();
        removePuzzle();
        updatePuzzle();
    }

    private void updatePuzzle() {
        if (edit == null) {
            return;
        }
        CompoundTag challenge = view.getCompoundOrEmpty("challenge");
        if (!locked() || !challenge.contains("view")) {
            removePuzzle();
            return;
        }
        Identifier type = Identifier.parse(challenge.getStringOr("type", ""));
        int round = challenge.getIntOr("round", 0);
        int step = challenge.getIntOr("step", 0);
        CompoundTag snapshot = challenge.getCompoundOrEmpty("view");
        if (puzzle != null && puzzle.type.equals(type) && puzzle.round == round && puzzle.step == step) {
            puzzle.snapshot = snapshot;
            return;
        }
        removePuzzle();
        puzzle = new PuzzleRound(type, round, step, snapshot);
        addWidget(puzzle.widget);
        setFocused(puzzle.widget);
    }

    private void removePuzzle() {
        if (puzzle != null) {
            AbstractWidget widget = puzzle.widget;
            puzzle = null;
            if (getFocused() == widget) {
                setFocused(null);
            }
            setDragging(false);
            removeWidget(widget);
        }
    }

    private final class PuzzleRound implements PuzzleWidgets.Context {
        private final Identifier type;
        private final int round;
        private final int step;
        private final AbstractWidget widget;
        private CompoundTag snapshot;

        private PuzzleRound(Identifier type, int round, int step, CompoundTag snapshot) {
            this.type = type;
            this.round = round;
            this.step = step;
            this.snapshot = snapshot;
            widget = PuzzleWidgets.create(type, this, font, 8, 23, Math.max(1, width - 16), Math.max(1, height - 47));
        }

        @Override
        public CompoundTag view() {
            return snapshot.copy();
        }

        @Override
        public void send(CompoundTag input) {
            if (puzzle == this && !closed) {
                CompoundTag message = action("puzzle_input");
                message.putInt("round", round);
                message.putInt("step", step);
                message.put("input", input.copy());
                LootScreen.this.send(message);
            }
        }
    }

    void positionAnswer() {
        if (edit == null) {
            return;
        }
        boolean wasVisible = edit.visible;
        edit.visible = locked() && lock.takesText() && !lock.isTable();
        edit.active = edit.visible;
        int left = Math.max(12, (width - 300) / 2);
        edit.setX(left);
        edit.setY(Math.max(56, height / 2 - 22) + 15);
        edit.setWidth(Math.max(12, width - left * 2 - 60));
        if (edit.visible && !wasVisible) {
            setFocused(edit);
        } else if (!edit.visible && getFocused() == edit) {
            setFocused(null);
        }
    }

    String answer() {
        return edit == null ? "" : edit.getValue();
    }

    void clearAnswer() {
        if (edit != null) {
            edit.setValue("");
        }
    }

    CompoundTag action(String name) {
        CompoundTag data = new CompoundTag();
        data.putString("action", name);
        data.store("session", UUIDUtil.CODEC, session);
        data.putLong("revision", view.getLongOr("revision", 0L));
        return data;
    }

    void send(CompoundTag action) {
        ExtractionNetwork.send(action);
    }

    void sendSneakPress(CompoundTag action) {
        if (minecraft.player.isShiftKeyDown()) {
            send(action);
            return;
        }
        InputConstants.Key binding = minecraft.options.keyShift.getKey();
        var window = minecraft.getWindow();
        boolean down = switch (binding.getType()) {
            case KEYSYM -> binding.getValue() >= 0 && InputConstants.isKeyDown(window, binding.getValue());
            case MOUSE -> GLFW.glfwGetMouseButton(window.handle(), binding.getValue()) == GLFW.GLFW_PRESS;
            default -> sneakInput;
        };
        if (down) {
            Input previous = minecraft.player.input.keyPresses;
            Input shifted = new Input(previous.forward(), previous.backward(), previous.left(), previous.right(), previous.jump(), true, previous.sprint());
            minecraft.player.connection.send(new ServerboundPlayerInputPacket(shifted));
            send(action);
            minecraft.player.connection.send(new ServerboundPlayerInputPacket(previous));
        }
    }

    @Override
    public void tick() {
        clock++;
        if (locked()) {
            lock.tick();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partialTick) {
        g.fill(1, 1, width - 1, height - 1, 0xf315201c);
        g.outline(1, 1, width - 2, height - 2, 0xff657c6e);
        g.fill(2, 2, width - 2, 20, 0xff2a4035);
        g.fill(8, 7, 10, 15, 0xffdeaf6b);
        g.text(font, locked() ? tr("lock_title") : title, 15, 6, 0xffe4ecdf, false);
        g.text(font, "×", width - 17, 6, mx >= width - 24 && my < 20 ? 0xffffd097 : 0xffbdcbbd, false);
        if (puzzle != null) {
            puzzle.widget.extractRenderState(g, mx, my, partialTick);
        } else if (locked()) {
            lock.draw(g, font, width, height, mx, my, partialTick, clock);
            if (edit.visible) {
                edit.extractRenderState(g, mx, my, partialTick);
            }
        } else {
            storage.draw(g, font, width, height, mx, my, clock, drag, moving);
        }
        g.fill(2, height - 21, width - 2, height - 2, 0xff202d26);
        String message = view.getStringOr("message", "");
        if (view.getBooleanOr("organizing", false)) {
            g.text(font, tr("organizing"), 9, height - 15, 0xffedcca1, false);
            g.text(font, tr("cancel"), width - 10 - font.width(tr("cancel")), height - 15, 0xffe4e6d7, false);
        } else if (!message.isEmpty()) {
            g.text(font, font.plainSubstrByWidth(Component.translatableWithFallback(message, message).getString(), width - 19),
                9, height - 15, 0xffeabb8d, false);
        } else {
            Component tip = locked() ? tr("leave") : tr("drag_hint");
            g.text(font, font.plainSubstrByWidth(tip.getString(), width - 19), 9, height - 15, 0xffa8b6ab, false);
        }
        if (!locked() && drag == null) {
            storage.tooltip(g, font, mx, my);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int button = event.button();
        int mx = (int) event.x();
        int my = (int) event.y();
        if (button == 0 && mx >= width - 24 && my < 20) {
            onClose();
            return true;
        }
        if (view.getBooleanOr("organizing", false) && my >= height - 21 && mx >= width - 80) {
            send(action("cancel"));
            return true;
        }
        if (locked()) {
            if (puzzle == null && (lock.click(mx, my, button) || ExtractionClient.ACTION.matchesMouse(event) && lock.press())) {
                return true;
            }
            return super.mouseClicked(event, doubleClick);
        }
        if (view.getBooleanOr("organizing", false)) {
            return true;
        }
        int pack = storage.nextPack(mx, my);
        if (pack >= 0) {
            CompoundTag select = action("select");
            select.putInt("slot", pack);
            send(select);
            return true;
        }
        StorageView.Hit hit = storage.hit(mx, my);
        if (hit == null || hit.entry() == null) {
            return false;
        }
        if (hit.vanilla() && button == 1) {
            CompoundTag change = action(hit.entry().recovery() ? "recover" : "equip");
            int slot = hit.entry().recovery() ? hit.slot() : storage.equipTarget(hit);
            if (slot < 0) {
                return false;
            }
            change.putInt("slot", slot);
            send(change);
            return true;
        }
        if (button != 0 || hit.entry().stack().isEmpty()) {
            return false;
        }
        long now = Util.getMillis();
        if (lastClick != null && now - lastClickTime <= 300 && sameEntry(lastClick, hit)) {
            CompoundTag transfer = action(hit.entry().recovery() ? "recover" : "transfer");
            StorageView.source(transfer, hit);
            send(transfer);
            lastClick = null;
            drag = null;
        } else {
            lastClick = hit;
            lastClickTime = now;
            drag = new StorageView.Drag(hit);
            dragRevision = view.getLongOr("revision", 0L);
            moving = false;
            startX = mx;
            startY = my;
        }
        return true;
    }

    private static boolean sameEntry(StorageView.Hit first, StorageView.Hit second) {
        if (first.vanilla() || second.vanilla()) {
            return first.vanilla() && second.vanilla() && first.slot() == second.slot();
        }
        return first.entry().id().equals(second.entry().id());
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (drag != null) {
            if (Math.abs(event.x() - startX) + Math.abs(event.y() - startY) >= 3) {
                moving = true;
                lastClick = null;
            }
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (locked()) {
            lock.release();
        }
        if (drag != null && event.button() == 0) {
            if (moving) {
                CompoundTag move = action("move");
                move.putLong("revision", dragRevision);
                if (storage.destination(move, drag, (int) event.x(), (int) event.y())) {
                    StorageView.source(move, drag.source);
                    send(move);
                }
            }
            moving = false;
            drag = null;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (puzzle != null) {
            return super.mouseScrolled(x, y, horizontal, vertical);
        }
        if (locked()) {
            lock.scroll(vertical);
        } else {
            storage.scroll(horizontal, vertical, minecraft.hasShiftDown());
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (minecraft.options.keyShift.matches(event)) {
            sneakInput = true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE || ExtractionClient.OPEN.matches(event) && puzzle == null && !(locked() && lock.takesText())) {
            onClose();
            return true;
        }
        if (locked() && puzzle == null) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                lock.submit();
                return true;
            }
            if (lock.routeMenu() && (key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_UP)) {
                lock.select(key == GLFW.GLFW_KEY_UP ? -1 : 1);
                return true;
            }
            if (ExtractionClient.ACTION.matches(event) && lock.press()) {
                return true;
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        if (minecraft.options.keyShift.matches(event)) {
            sneakInput = false;
        }
        if (locked() && puzzle == null && ExtractionClient.ACTION.matches(event)) {
            lock.release();
            return true;
        }
        return super.keyReleased(event);
    }

    void closeFromServer() {
        closed = true;
        onClose();
    }

    @Override
    public void removed() {
        removePuzzle();
        ExtractionClient.dismissed(session);
        if (!closed && minecraft != null && minecraft.getConnection() != null) {
            closed = true;
            send(action("close"));
        }
        super.removed();
    }
}
