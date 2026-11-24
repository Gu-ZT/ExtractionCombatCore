package dev.extraction.core.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class LockPanel {
    private static final String LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final String[] CODES = {".-", "-...", "-.-.", "-..", ".", "..-.", "--.", "....", "..", ".---",
        "-.-", ".-..", "--", "-.", "---", ".--.", "--.-", ".-.", "...", "-", "..-", "...-", ".--", "-..-",
        "-.--", "--..", "-----", ".----", "..---", "...--", "....-", ".....", "-....", "--...", "---..", "----."};
    private static final SoundEvent DOT = tone("morse_dot");
    private static final SoundEvent DASH = tone("morse_dash");
    private final LootScreen screen;
    private final List<Cue> cues = new ArrayList<>();
    private List<CompoundTag> routes = List.of();
    private CompoundTag challenge = new CompoundTag();
    private int scroll;
    private int maxScroll;
    private int audioTick = -1;
    private int cueIndex;
    private long received;
    private boolean held;
    private boolean table;
    private int selectedRoute;
    private List<Row> routeRows = List.of();
    private String morse = "";
    private String pattern = "";
    private int patternWidth = -1;
    private List<FormattedCharSequence> patternLines = List.of();

    private record Cue(int start, int end, boolean dash) {}
    private record Row(int index, int y, int height) {}

    LockPanel(LootScreen screen) {
        this.screen = screen;
    }

    private static SoundEvent tone(String id) {
        return SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath("extractioncombatcore", id));
    }

    String type() {
        return challenge.getStringOr("type", "");
    }

    boolean takesText() {
        return type().equals("password") || type().endsWith("morse");
    }

    void update(CompoundTag view, long clock) {
        CompoundTag next = view.getCompoundOrEmpty("challenge");
        boolean stageChanged = !next.getStringOr("type", "").equals(type()) || next.getIntOr("round", 0) != challenge.getIntOr("round", 0);
        boolean play = next.getStringOr("type", "").equals("audio_morse") && (stageChanged || next.getIntOr("replay", 0) != challenge.getIntOr("replay", 0));
        if (stageChanged) {
            held = false;
            table = false;
            scroll = 0;
            cues.clear();
            audioTick = -1;
            screen.clearAnswer();
        }
        challenge = next;
        if (stageChanged) {
            morse = next.contains("signals_utf8") ? new String(next.getByteArray("signals_utf8").orElseGet(() -> new byte[0]), StandardCharsets.UTF_8)
                : next.getStringOr("signals", "");
            patternWidth = -1;
            if (type().equals("pattern_morse")) {
                StringBuilder out = new StringBuilder();
                for (char signal : morse.toCharArray()) {
                    if (signal == '.') {
                        out.append(next.getStringOr("dot", ""));
                    } else if (signal == '-') {
                        out.append(next.getStringOr("dash", ""));
                    } else {
                        out.append(signal);
                    }
                }
                pattern = out.toString();
            }
        }
        routes = StorageView.compounds(view, "routes");
        received = clock;
        if (play) {
            startAudio(morse);
        }
    }

    private void startAudio(String signals) {
        cues.clear();
        cueIndex = 0;
        audioTick = -1;
        int time = 0;
        for (String letter : signals.trim().split("\\s+")) {
            if (letter.equals("/")) {
                time += 12;
                continue;
            }
            for (int i = 0; i < letter.length(); i++) {
                boolean dash = letter.charAt(i) == '-';
                int duration = dash ? 9 : 3;
                cues.add(new Cue(time, time + duration, dash));
                time += duration + 3;
            }
            time += 6;
        }
    }

    void tick() {
        if (!type().equals("audio_morse") || cues.isEmpty()) {
            return;
        }
        audioTick++;
        if (cueIndex < cues.size() && cues.get(cueIndex).start == audioTick) {
            Cue cue = cues.get(cueIndex++);
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(cue.dash ? DASH : DOT, 1f));
        }
    }

    boolean isTable() {
        return table;
    }

    boolean routeMenu() {
        return type().isEmpty();
    }

    void draw(GuiGraphics g, Font font, int w, int h, int mx, int my, float fraction, long clock) {
        if (routeMenu()) {
            drawRoutes(g, font, w, h, mx, my);
            return;
        }
        int left = Math.max(12, (w - 300) / 2);
        int right = w - left;
        int mid = w / 2;
        g.drawString(font, LootScreen.tr("lock." + type()), left, 31, 0xffe9e5d6, false);
        if (table) {
            drawTable(g, font, left, right, h);
            return;
        }
        boolean paused = challenge.getIntOr("pause", 0) > 0;
        float since = Mth.clamp((float) (clock - received) + fraction, 0, 10);
        int duration = Math.max(1, challenge.getIntOr("duration", 0));
        float elapsed = challenge.getIntOr("elapsed", 0);
        if ((!paused || type().equals("accelerated_decode")) && (!type().equals("hold") || held)) {
            elapsed += since;
        }
        int y = Math.max(56, h / 2 - 22);
        switch (type()) {
            case "password" -> g.drawString(font, LootScreen.tr("password_hint"), left, y - 7, 0xffc3d0c7, false);
            case "audio_morse" -> {
                button(g, font, left, y - 13, 75, LootScreen.tr("replay"), true);
                Cue latest = cueIndex == 0 ? null : cues.get(cueIndex - 1);
                if (challenge.getBooleanOr("flash", false)) {
                    boolean lit = latest != null && audioTick < latest.end;
                    g.fill(left + 81, y - 9, left + 91, y + 1, lit ? 0xffa2ecab : 0xff354039);
                }
                tableButton(g, font, right, y);
            }
            case "pattern_morse" -> {
                int available = Math.max(1, right - left - 69);
                if (available != patternWidth) {
                    patternLines = font.split(Component.literal(pattern), available);
                    patternWidth = available;
                }
                g.enableScissor(left, 45, right, y + 8);
                int first = scroll / 11;
                int lineY = 44 + first * 11 - scroll;
                for (int i = first; i < patternLines.size() && lineY < y + 8; i++) {
                    g.drawString(font, patternLines.get(i), left, lineY, 0xffc8debd, false);
                    lineY += 11;
                }
                maxScroll = Math.max(0, 44 + patternLines.size() * 11 - y - 6);
                g.disableScissor();
                tableButton(g, font, right, y);
            }
            case "rolling_lock" -> {
                drawRolling(g, font, left, right, y, elapsed);
                g.drawCenteredString(font, LootScreen.tr("rolling_hint", ExtractionClient.ACTION.getTranslatedKeyMessage()),
                    mid, y + 33, 0xffc9d6cc);
            }
            case "accelerated_decode" -> {
                int size = right - left;
                float sweep = elapsed % 40 / 20;
                float cursor = 1 - Math.abs(sweep - 1);
                g.fill(left, y - 9, right, y + 1, 0xff26372f);
                g.fill(left + Math.round(size * .42f), y - 9, left + Math.round(size * .58f), y + 1, 0xffe3eada);
                int x = left + Math.round((size - 3) * cursor);
                g.fill(x, y - 13, x + 3, y + 5, paused ? 0xffbd6557 : 0xffe9b66b);
                float progress = Math.min(1, challenge.getFloatOr("progress", 0f) + since / duration);
                progressBar(g, left, right, y + 18, progress);
                g.drawCenteredString(font, Math.round(progress * 100) + "%", mid, y + 29, 0xffc9d6cc);
                g.drawCenteredString(font, LootScreen.tr("decode_hint", ExtractionClient.ACTION.getTranslatedKeyMessage()),
                    mid, y - 29, 0xffc9d6cc);
            }
            case "hold" -> {
                progressBar(g, left, right, y, Mth.clamp(elapsed / duration, 0, 1));
                g.drawCenteredString(font, LootScreen.tr("hold_hint", ExtractionClient.ACTION.getTranslatedKeyMessage()), mid, y + 15, 0xffcfdbd0);
            }
            default -> g.drawCenteredString(font, LootScreen.tr(type().startsWith("sneak") ? "sneak_hint" : "press_hint",
                ExtractionClient.ACTION.getTranslatedKeyMessage()), mid, y, 0xffcfdbd0);
        }
        if (takesText()) {
            button(g, font, right - 54, y + 15, 54, LootScreen.tr("submit"), !paused);
        } else {
            button(g, font, mid - 50, h - 47, 100, paused ? LootScreen.tr("cooldown") : LootScreen.tr("trigger",
                ExtractionClient.ACTION.getTranslatedKeyMessage()), !paused);
        }
        if (paused && takesText()) {
            g.drawString(font, LootScreen.tr("wrong"), left, y + 39, 0xffe79076, false);
        }
    }

    private static void progressBar(GuiGraphics g, int left, int right, int y, float progress) {
        g.fill(left, y, right, y + 6, 0xff344239);
        g.fill(left, y, left + Math.round((right - left) * progress), y + 6, 0xff8dbf95);
    }

    private void drawRolling(GuiGraphics g, Font font, int left, int right, int y, float elapsed) {
        int[] periods = challenge.getIntArray("periods").orElseGet(() -> new int[0]);
        int[] directions = challenge.getIntArray("directions").orElseGet(() -> new int[0]);
        String alphabet = challenge.getStringOr("alphabet", "");
        String target = challenge.getStringOr("target", "");
        int count = periods.length;
        if (count == 0 || alphabet.isEmpty()) {
            return;
        }
        int cell = Math.min(25, (right - left) / count);
        int x = (left + right - cell * count) / 2;
        g.enableScissor(x, y - 18, x + cell * count, y + 25);
        for (int i = 0; i < count; i++) {
            int xx = x + cell * i;
            boolean locked = i < challenge.getIntOr("locked", 0);
            g.fill(xx + 1, y - 18, xx + cell - 1, y + 25, locked ? 0xff2c4936 : 0xff24322b);
            if (locked) {
                g.drawCenteredString(font, String.valueOf(target.charAt(i)), xx + cell / 2, y, 0xffd5efca);
            } else {
                double phase = elapsed * directions[i] / periods[i];
                int center = (int) Math.floor(phase + .5);
                for (int k = -2; k <= 2; k++) {
                    char symbol = alphabet.charAt(Math.floorMod(center + k, alphabet.length()));
                    int yy = y + (int) Math.round((center + k - phase) * 14);
                    if (symbol == target.charAt(i)) {
                        g.fill(xx + 2, yy - 2, xx + cell - 2, yy + 10, 0xff4d8054);
                    }
                    g.drawCenteredString(font, String.valueOf(symbol), xx + cell / 2, yy, 0xffe4ede0);
                }
            }
            if (i == challenge.getIntOr("locked", 0)) {
                g.renderOutline(xx, y - 3, cell, 15, 0xfffbf8ef);
            }
        }
        g.disableScissor();
    }

    private void tableButton(GuiGraphics g, Font font, int right, int y) {
        if (challenge.getBooleanOr("show_table", false)) {
            button(g, font, right - 65, y - 13, 65, LootScreen.tr("table"), true);
        }
    }

    private void drawTable(GuiGraphics g, Font font, int left, int right, int h) {
        int cols = Math.max(2, (right - left) / 62);
        int rows = (LETTERS.length() + cols - 1) / cols;
        maxScroll = Math.max(0, 51 + rows * 13 - (h - 49));
        g.enableScissor(left, 47, right, h - 48);
        for (int i = 0; i < LETTERS.length(); i++) {
            int x = left + i % cols * ((right - left) / cols);
            int y = 50 + i / cols * 13 - scroll;
            g.drawString(font, String.valueOf(LETTERS.charAt(i)), x, y, 0xffe5cda3, false);
            g.drawString(font, CODES[i], x + 13, y, 0xffccd6cc, false);
        }
        g.disableScissor();
        button(g, font, left, h - 45, 74, LootScreen.tr("back"), true);
    }

    private void drawRoutes(GuiGraphics g, Font font, int w, int h, int mx, int my) {
        int left = Math.max(12, (w - 324) / 2);
        int right = w - left;
        g.drawString(font, LootScreen.tr("routes"), left, 28, 0xffd7e1d5, false);
        g.enableScissor(left, 41, right, h - 23);
        List<Row> rows = new ArrayList<>();
        int y = 45 - scroll;
        for (int i = 0; i < routes.size(); i++) {
            CompoundTag route = routes.get(i);
            var lines = font.split(Component.literal(route.getStringOr("description", "")), right - left - 20);
            int height = 20 + lines.size() * 10;
            rows.add(new Row(i, y, height));
            boolean available = route.getBooleanOr("available", false);
            g.fill(left, y, right, y + height, selectedRoute == i || mx >= left && mx <= right && my >= y && my < y + height
                ? 0xff32473a : 0xff26362e);
            g.fill(left, y, left + 2, y + height, available ? 0xffa2c795 : 0xffa37756);
            g.drawString(font, route.getStringOr("name", ""), left + 7, y + 4, 0xffeceddf, false);
            if (!available) {
                g.drawString(font, LootScreen.tr("unavailable"), right - 6 - font.width(LootScreen.tr("unavailable")), y + 4, 0xffcf9771, false);
            }
            int lineY = y + 16;
            for (var text : lines) {
                g.drawString(font, text, left + 8, lineY, 0xffb7c7b9, false);
                lineY += 10;
            }
            y += height + 5;
        }
        g.disableScissor();
        routeRows = rows;
        maxScroll = Math.max(0, y + scroll - h + 23);
    }

    private static void button(GuiGraphics g, Font font, int x, int y, int w, Component label, boolean enabled) {
        g.fill(x, y, x + w, y + 17, enabled ? 0xff395748 : 0xff343c37);
        g.renderOutline(x, y, w, 17, enabled ? 0xff708a73 : 0xff50564e);
        g.drawCenteredString(font, label, x + w / 2, y + 4, enabled ? 0xfff0f1df : 0xff929b91);
    }

    void scroll(double dy) {
        scroll = Mth.clamp(scroll - (int) Math.round(dy * 16), 0, maxScroll);
    }

    void select(int delta) {
        if (!routeMenu() || routes.isEmpty()) {
            return;
        }
        selectedRoute = Math.floorMod(selectedRoute + delta, routes.size());
        if (selectedRoute < routeRows.size()) {
            Row row = routeRows.get(selectedRoute);
            if (row.y < 41) {
                scroll = Math.max(0, scroll + row.y - 43);
            } else if (row.y + row.height > screen.height - 23) {
                scroll = Math.min(maxScroll, scroll + row.y + row.height - screen.height + 25);
            }
        }
    }

    void submit() {
        if (routeMenu()) {
            choose(selectedRoute);
        } else if (takesText() && !table && challenge.getIntOr("pause", 0) == 0) {
            CompoundTag message = screen.action("answer");
            message.putByteArray("text_utf8", screen.answer().getBytes(StandardCharsets.UTF_8));
            sendRound(message);
            screen.clearAnswer();
        }
    }

    private void choose(int index) {
        if (index < routes.size() && routes.get(index).getBooleanOr("available", false)) {
            CompoundTag message = screen.action("route");
            message.putInt("index", index);
            screen.send(message);
        }
    }

    private void sendRound(CompoundTag message) {
        message.putInt("round", challenge.getIntOr("round", 0));
        if (type().startsWith("sneak") && message.getStringOr("action", "").equals("press")) {
            screen.sendSneakPress(message);
        } else {
            screen.send(message);
        }
    }

    boolean press() {
        if (takesText() || routeMenu() || held) {
            return false;
        }
        held = true;
        sendRound(screen.action("press"));
        return true;
    }

    void release() {
        if (held && type().equals("hold")) {
            sendRound(screen.action("release"));
        }
        held = false;
    }

    boolean click(int x, int y, int button) {
        int left = Math.max(12, (screen.width - 300) / 2);
        int right = screen.width - left;
        int cy = Math.max(56, screen.height / 2 - 22);
        if (table) {
            if (y >= screen.height - 45 && y < screen.height - 27 && x >= left && x < left + 74) {
                table = false;
                scroll = 0;
                screen.positionAnswer();
            }
            return true;
        }
        if (routeMenu()) {
            int edge = Math.max(12, (screen.width - 324) / 2);
            if (x >= edge && x < screen.width - edge && y >= 41 && y < screen.height - 23) {
                for (Row row : routeRows) {
                    if (y >= row.y && y < row.y + row.height) {
                        selectedRoute = row.index;
                        choose(row.index);
                        return true;
                    }
                }
            }
            return false;
        }
        if (button == 0 && takesText()) {
            if (x >= right - 54 && x < right && y >= cy + 15 && y < cy + 32) {
                submit();
                return true;
            }
            if (type().endsWith("morse") && y >= cy - 13 && y < cy + 4) {
                if (challenge.getBooleanOr("show_table", false) && x >= right - 65 && x < right) {
                    table = true;
                    scroll = 0;
                    screen.positionAnswer();
                    return true;
                }
                if (type().equals("audio_morse") && x >= left && x < left + 75) {
                    sendRound(screen.action("replay"));
                    return true;
                }
            }
        } else if (button == 1 && (type().contains("click") || type().contains("interact") || type().startsWith("sneak"))
            || x >= screen.width / 2 - 50 && x < screen.width / 2 + 50 && y >= screen.height - 47 && y < screen.height - 30) {
            return press();
        }
        return false;
    }
}
