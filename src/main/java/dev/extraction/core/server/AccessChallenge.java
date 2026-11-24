package dev.extraction.core.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.extraction.core.GameAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class AccessChallenge {
    private static final String SYMBOLS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final String[] MORSE = {".-", "-...", "-.-.", "-..", ".", "..-.", "--.", "....", "..", ".---",
        "-.-", ".-..", "--", "-.", "---", ".--.", "--.-", ".-.", "...", "-", "..-", "...-", ".--", "-..-",
        "-.--", "--..", "-----", ".----", "..---", "...--", "....-", ".....", "-....", "--...", "---..", "----."};
    private final JsonObject route;
    private final JsonArray steps;
    private int index;
    private int elapsed;
    private int pause;
    private int locked;
    private int replay;
    private int round;
    private int[] periods = new int[0];
    private int[] directions = new int[0];
    private String target = "";
    private boolean held;
    private double bonus;
    private int lastSweep = -1;

    AccessChallenge(JsonObject route, ServerPlayer player) {
        this.route = route;
        steps = array(route, "steps");
        start(player);
    }

    private void start(ServerPlayer player) {
        round = player.getRandom().nextInt();
        elapsed = 0;
        pause = 0;
        locked = 0;
        replay = 0;
        held = false;
        bonus = 0;
        lastSweep = -1;
        if (type().equals("rolling_lock")) {
            int length = integer(step(), "positions", 5);
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

    private void advance(ServerPlayer player) {
        index++;
        if (!complete()) {
            start(player);
        }
    }

    private JsonObject step() {
        return complete() ? new JsonObject() : steps.get(index).getAsJsonObject();
    }

    private String type() {
        return string(step(), "type", "");
    }

    private String alphabet() {
        return string(step(), "alphabet", "0123456789");
    }

    boolean complete() {
        return index >= steps.size();
    }

    boolean allowed(ServerPlayer player, BlockPos position, boolean pay) {
        return conditions(route, player, position, pay);
    }

    static boolean conditions(JsonObject route, ServerPlayer player, BlockPos pos, boolean pay) {
        int[] consume = new int[2];
        int[] damage = new int[2];
        List<JsonObject> held = new ArrayList<>();
        for (JsonElement element : array(route, "requires")) {
            JsonObject requirement = element.getAsJsonObject();
            String type = string(requirement, "type", "");
            switch (type) {
                case "powered" -> {
                    if (!player.serverLevel().hasNeighborSignal(pos)) {
                        return false;
                    }
                }
                case "unlocked", "flag", "mechanism_unlocked" -> {
                    String id = string(requirement, "flag", string(requirement, "id", ""));
                    ResourceLocation flag = ResourceLocation.tryParse(id);
                    if (flag == null || !GameAccess.flag(player.serverLevel(), flag)) {
                        return false;
                    }
                }
                case "held_item", "held_item_tag" -> held.add(requirement);
                default -> {
                    return false;
                }
            }
        }
        if (!assignHands(player, held, 0, consume, damage)) {
            return false;
        }
        if (pay) {
            for (InteractionHand hand : InteractionHand.values()) {
                int i = hand.ordinal();
                ItemStack stack = player.getItemInHand(hand);
                if (damage[i] > 0) {
                    stack.hurtAndBreak(damage[i], player, hand == InteractionHand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);
                }
                stack.shrink(consume[i]);
            }
        }
        return true;
    }

    private static boolean assignHands(ServerPlayer player, List<JsonObject> held, int next, int[] consume, int[] damage) {
        if (next == held.size()) {
            return true;
        }
        JsonObject requirement = held.get(next);
        String type = string(requirement, "type", "");
        String key = type.equals("held_item") ? "item" : "tag";
        ResourceLocation id = ResourceLocation.tryParse(string(requirement, key, "").replaceFirst("^#", ""));
        if (id == null) {
            return false;
        }
        int quantity = bool(requirement, "consume", false) ? 1 : 0;
        int cost = integer(requirement, "damage", 0);
        for (InteractionHand hand : InteractionHand.values()) {
            int i = hand.ordinal();
            ItemStack stack = player.getItemInHand(hand);
            if (hand == InteractionHand.MAIN_HAND && !GameAccess.isEnabled(player, player.getInventory().selected)) {
                continue;
            }
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
            boolean matches;
            if (type.equals("held_item")) {
                matches = itemId.equals(id);
            } else if (requirement.has("_resolved_items")) {
                matches = requirement.getAsJsonArray("_resolved_items").asList().stream()
                    .anyMatch(item -> item.getAsString().equals(itemId.toString()));
            } else {
                matches = stack.is(TagKey.create(Registries.ITEM, id));
            }
            if (!matches || stack.isEmpty() || quantity > stack.getCount() - consume[i]
                || (cost > 0 && (!stack.isDamageableItem() || cost > stack.getMaxDamage() - stack.getDamageValue() - damage[i]))) {
                continue;
            }
            consume[i] += quantity;
            damage[i] += cost;
            if (assignHands(player, held, next + 1, consume, damage)) {
                return true;
            }
            consume[i] -= quantity;
            damage[i] -= cost;
        }
        return false;
    }

    static ListTag routeList(StoredGrid grid, ServerPlayer player, BlockPos position) {
        ListTag routes = new ListTag();
        int index = 1;
        for (JsonElement element : grid.routes()) {
            JsonObject route = element.getAsJsonObject();
            CompoundTag detail = new CompoundTag();
            detail.putString("name", string(route, "name", "路线 " + index++));
            detail.putBoolean("available", conditions(route, player, position, false));
            StringBuilder text = new StringBuilder();
            for (JsonElement value : array(route, "requires")) {
                JsonObject req = value.getAsJsonObject();
                switch (string(req, "type", "")) {
                    case "powered" -> text.append("需要红石供电 ");
                    case "held_item", "held_item_tag" -> {
                        String name = string(req, "item", string(req, "tag", ""));
                        text.append("手持 ").append(name);
                        if (bool(req, "consume", false)) {
                            text.append("（消耗 1 件）");
                        }
                        int damage = integer(req, "damage", integer(req, "durability_cost", 0));
                        if (damage > 0) {
                            text.append("（耐久 ").append(damage).append("）");
                        }
                        text.append(' ');
                    }
                    default -> text.append("需要地图机关 ");
                }
            }
            for (JsonElement value : array(route, "steps")) {
                JsonObject rule = value.getAsJsonObject();
                text.append(switch (string(rule, "type", "")) {
                    case "click", "right_click", "interact" -> "交互";
                    case "sneak", "sneak_interact" -> "潜行交互";
                    case "hold" -> "长按 " + integer(rule, "ticks", 20) + " 刻";
                    case "password" -> "密码锁";
                    case "audio_morse" -> "听取摩斯电码";
                    case "pattern_morse" -> "辨认图案摩斯";
                    case "rolling_lock" -> "滚动字符锁";
                    case "accelerated_decode" -> "自动破译 " + integer(rule, "passive_ticks", 400) + " 刻";
                    default -> "";
                }).append(' ');
            }
            detail.putString("description", text.toString().strip());
            routes.add(detail);
        }
        return routes;
    }

    void tick(ServerPlayer player) {
        if (complete()) {
            return;
        }
        if (pause > 0) {
            pause--;
            if (type().equals("accelerated_decode")) {
                elapsed++;
            }
        } else if (!type().equals("hold") || held) {
            elapsed++;
        }
        if ((type().equals("hold") && elapsed >= integer(step(), "ticks", 20))
            || (type().equals("accelerated_decode") && progress() >= 1)) {
            advance(player);
        }
    }

    void input(ServerPlayer player, CompoundTag message) {
        if (complete() || round != message.getInt("round")) {
            return;
        }
        String action = message.getString("action");
        if (action.equals("release") && type().equals("hold")) {
            held = false;
            elapsed = 0;
            return;
        }
        if (pause > 0) {
            return;
        }
        switch (type()) {
            case "click", "right_click", "interact", "sneak", "sneak_interact" -> {
                if (action.equals("press") && (!type().startsWith("sneak") || player.isShiftKeyDown())) {
                    advance(player);
                }
            }
            case "hold" -> {
                if (action.equals("press")) {
                    held = true;
                }
            }
            case "password", "audio_morse", "pattern_morse" -> {
                if (action.equals("replay") && type().equals("audio_morse")) {
                    replay++;
                    elapsed = 0;
                } else if (action.equals("answer")) {
                    byte[] answer = message.getByteArray("text_utf8");
                    boolean matches = type().equals("password") ? Arrays.equals(answer, solution().getBytes(StandardCharsets.UTF_8))
                        : new String(answer, StandardCharsets.UTF_8).equalsIgnoreCase(solution());
                    if (matches) {
                        advance(player);
                    } else {
                        pause = integer(step(), "error_pause_ticks", 20);
                    }
                }
            }
            case "rolling_lock", "accelerated_decode" -> {
                if (action.equals("press")) {
                    timingInput(player);
                }
            }
            default -> {
            }
        }
    }

    private void timingInput(ServerPlayer player) {
        int tolerance = Math.min(2, Math.max(0, Math.round(player.connection.latency() / 100f)));
        boolean rolling = type().equals("rolling_lock");
        for (int t = elapsed; t >= Math.max(0, elapsed - tolerance); t--) {
            if (rolling) {
                double phase = t * (double)directions[locked] / periods[locked];
                int center = (int)Math.floor(phase + 0.5);
                if (Math.abs(center - phase) <= 0.30 && alphabet().charAt(Math.floorMod(center, alphabet().length())) == target.charAt(locked)) {
                    if (++locked == periods.length) {
                        advance(player);
                    }
                    return;
                }
            } else {
                double cursor = 1 - Math.abs((t % 40) / 20d - 1);
                if (cursor >= 0.42 && cursor <= 0.58) {
                    int sweep = t / 20;
                    if (sweep != lastSweep) {
                        lastSweep = sweep;
                        bonus += number(step(), "hit_bonus", 0.15);
                        if (progress() >= 1) {
                            advance(player);
                        }
                    }
                    return;
                }
            }
        }
        pause = integer(step(), "error_pause_ticks", rolling ? 40 : 6);
    }

    private String solution() {
        return string(step(), "answer", string(step(), "message", ""));
    }

    private double progress() {
        return Math.min(1, bonus + elapsed / (double)integer(step(), "passive_ticks", 400));
    }

    CompoundTag view() {
        CompoundTag out = new CompoundTag();
        String type = type();
        out.putString("type", type);
        out.putInt("round", round);
        out.putInt("elapsed", elapsed);
        out.putInt("pause", pause);
        out.putInt("duration", integer(step(), "ticks", integer(step(), "passive_ticks", 400)));
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
            out.putString("dot", string(step(), "dot", "●"));
            out.putString("dash", string(step(), "dash", "▬"));
            out.putBoolean("flash", bool(step(), "flash", true));
            out.putBoolean("show_table", bool(step(), "show_table", true));
        }
        return out;
    }

    private static JsonArray array(JsonObject obj, String key) {
        return obj.has(key) ? obj.getAsJsonArray(key) : new JsonArray();
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
