package dev.extraction.core.server;

import com.google.gson.JsonObject;
import dev.extraction.core.api.PuzzleSession;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

final class BuiltInPuzzleSession implements PuzzleSession {
    private static final String SYMBOLS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final String[] MORSE = {".-", "-...", "-.-.", "-..", ".", "..-.", "--.", "....", "..", ".---",
        "-.-", ".-..", "--", "-.", "---", ".--.", "--.-", ".-.", "...", "-", "..-", "...-", ".--", "-..-",
        "-.--", "--..", "-----", ".----", "..---", "...--", "....-", ".....", "-....", "--...", "---..", "----."};
    private final String type;
    private final ServerPlayer player;
    private final JsonObject step;
    private boolean complete;
    private int elapsed;
    private int pause;
    private int locked;
    private int replay;
    private int[] periods = new int[0];
    private int[] directions = new int[0];
    private String target = "";
    private boolean held;
    private double bonus;
    private int lastSweep = -1;

    BuiltInPuzzleSession(String type, ServerPlayer player, JsonObject step) {
        this.type = type;
        this.player = player;
        this.step = step;
        if (type.equals("rolling_lock")) {
            int length = integer(step, "positions", 5);
            periods = new int[length];
            directions = new int[length];
            StringBuilder marks = new StringBuilder();
            String alphabet = alphabet();
            for (int i = 0; i < length; i++) {
                periods[i] = 5 + player.getRandom().nextInt(6);
                directions[i] = player.getRandom().nextBoolean() ? 1 : -1;
                marks.append(alphabet.charAt(player.getRandom().nextInt(alphabet.length())));
            }
            target = marks.toString();
        }
    }

    private String alphabet() {
        return string(step, "alphabet", "0123456789");
    }

    @Override
    public boolean tick() {
        if (pause > 0) {
            pause--;
            if (type.equals("accelerated_decode")) {
                elapsed++;
            }
        } else if (!type.equals("hold") || held) {
            elapsed++;
        }
        if ((type.equals("hold") && elapsed >= integer(step, "ticks", 20))
            || (type.equals("accelerated_decode") && progress() >= 1)) {
            complete = true;
        }
        return complete;
    }

    @Override
    public boolean input(CompoundTag message) {
        String action = message.getStringOr("action", "");
        if (action.equals("release") && type.equals("hold")) {
            held = false;
            elapsed = 0;
            return false;
        }
        if (pause > 0) {
            return false;
        }
        switch (type) {
            case "click", "sneak" -> {
                if (action.equals("press") && (!type.startsWith("sneak") || player.isShiftKeyDown())) {
                    complete = true;
                }
            }
            case "hold" -> {
                if (action.equals("press")) {
                    held = true;
                }
            }
            case "password", "audio_morse", "pattern_morse" -> {
                if (action.equals("replay") && type.equals("audio_morse")) {
                    replay++;
                    elapsed = 0;
                } else if (action.equals("answer")) {
                    byte[] answer = message.getByteArray("text_utf8").orElseGet(() -> new byte[0]);
                    boolean matches = type.equals("password") ? Arrays.equals(answer, solution().getBytes(StandardCharsets.UTF_8))
                        : new String(answer, StandardCharsets.UTF_8).equalsIgnoreCase(solution());
                    if (matches) {
                        complete = true;
                    } else {
                        pause = integer(step, "error_pause_ticks", 20);
                    }
                }
            }
            case "rolling_lock", "accelerated_decode" -> {
                if (action.equals("press")) {
                    timingInput();
                }
            }
            default -> {
            }
        }
        return complete;
    }

    private void timingInput() {
        int tolerance = Math.min(2, Math.max(0, Math.round(player.connection.latency() / 100f)));
        boolean rolling = type.equals("rolling_lock");
        for (int t = elapsed; t >= Math.max(0, elapsed - tolerance); t--) {
            if (rolling) {
                double phase = t * (double)directions[locked] / periods[locked];
                int center = (int)Math.floor(phase + 0.5);
                if (Math.abs(center - phase) <= 0.30 && alphabet().charAt(Math.floorMod(center, alphabet().length())) == target.charAt(locked)) {
                    if (++locked == periods.length) {
                        complete = true;
                    }
                    return;
                }
            } else {
                double cursor = 1 - Math.abs((t % 40) / 20d - 1);
                if (cursor >= 0.42 && cursor <= 0.58) {
                    int sweep = t / 20;
                    if (sweep != lastSweep) {
                        lastSweep = sweep;
                        bonus += number(step, "hit_bonus", 0.15);
                        if (progress() >= 1) {
                            complete = true;
                        }
                    }
                    return;
                }
            }
        }
        pause = integer(step, "error_pause_ticks", rolling ? 40 : 6);
    }

    private String solution() {
        return string(step, "answer", string(step, "message", ""));
    }

    private double progress() {
        return Math.min(1, bonus + elapsed / (double)integer(step, "passive_ticks", 400));
    }

    @Override
    public CompoundTag view() {
        CompoundTag out = new CompoundTag();
        out.putInt("elapsed", elapsed);
        out.putInt("pause", pause);
        out.putInt("duration", integer(step, "ticks", integer(step, "passive_ticks", 400)));
        out.putInt("replay", replay);
        if (type.equals("accelerated_decode")) {
            out.putFloat("progress", (float)progress());
        } else if (type.equals("rolling_lock")) {
            out.putInt("locked", locked);
            out.putInt("positions", periods.length);
            out.putIntArray("periods", periods);
            out.putIntArray("directions", directions);
            out.putString("alphabet", alphabet());
            out.putString("target", target);
        } else if (type.endsWith("morse")) {
            StringBuilder signals = new StringBuilder();
            for (char ch : solution().toUpperCase(Locale.ROOT).toCharArray()) {
                if (!signals.isEmpty()) {
                    signals.append(' ');
                }
                int letter = SYMBOLS.indexOf(ch);
                signals.append(letter >= 0 ? MORSE[letter] : "/");
            }
            if (signals.length() > 60000) {
                out.putByteArray("signals_utf8", signals.toString().getBytes(StandardCharsets.US_ASCII));
            } else {
                out.putString("signals", signals.toString());
            }
            out.putString("dot", string(step, "dot", "●"));
            out.putString("dash", string(step, "dash", "▬"));
            out.putBoolean("flash", bool(step, "flash", true));
            out.putBoolean("show_table", bool(step, "show_table", true));
        }
        return out;
    }

    private static String string(JsonObject obj, String key, String fallback) {
        return obj.has(key) ? obj.get(key).getAsString() : fallback;
    }

    private static double number(JsonObject obj, String key, double fallback) {
        return obj.has(key) ? obj.get(key).getAsDouble() : fallback;
    }

    private static int integer(JsonObject obj, String key, int fallback) {
        return obj.has(key) ? obj.get(key).getAsInt() : fallback;
    }

    private static boolean bool(JsonObject obj, String key, boolean fallback) {
        return obj.has(key) ? obj.get(key).getAsBoolean() : fallback;
    }
}
