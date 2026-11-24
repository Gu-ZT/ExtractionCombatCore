package dev.extraction.core.server;

import com.google.gson.JsonObject;
import dev.extraction.core.GameAccess;
import dev.extraction.core.api.AccessCondition;
import dev.extraction.core.api.PuzzleSession;
import dev.extraction.core.api.PuzzleType;
import dev.extraction.core.rules.DefinitionFields;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;

/** 将核心提供的条件和开锁步骤加入与附属模组共用的注册表。 */
public final class BuiltInLockTypes {
    private BuiltInLockTypes() {}

    public static void register(DeferredRegister<AccessCondition> conditions, DeferredRegister<PuzzleType> puzzles) {
        for (String name : List.of("powered", "unlocked", "held_item", "held_item_tag")) {
            conditions.register(name, () -> new Condition(name));
        }
        for (String name : List.of("click", "sneak", "hold", "password", "audio_morse", "pattern_morse", "rolling_lock", "accelerated_decode")) {
            puzzles.register(name, () -> new Puzzle(name));
        }
    }

    record Condition(String name) implements AccessCondition {
        boolean held() {
            return name.equals("held_item") || name.equals("held_item_tag");
        }

        @Override
        public boolean test(ServerPlayer player, BlockPos position, JsonObject definition) {
            return switch (name) {
                case "powered" -> player.level().hasNeighborSignal(position);
                case "unlocked" -> GameAccess.flag(player.level(), Identifier.parse(definition.get("flag").getAsString()));
                case "held_item", "held_item_tag" -> HeldRequirement.check(player, List.of(new HeldRequirement(name, definition)), false);
                default -> throw new IllegalStateException(name);
            };
        }

        @Override
        public void validate(JsonObject definition, String path) {
            DefinitionFields.builtInCondition(name, definition, path);
        }

        @Override
        public String description(JsonObject definition) {
            return switch (name) {
                case "powered" -> "需要红石供电";
                case "unlocked" -> "需要地图机关";
                case "held_item", "held_item_tag" -> {
                    String key = name.equals("held_item") ? "item" : "tag";
                    StringBuilder text = new StringBuilder("手持 ").append(definition.get(key).getAsString());
                    if (definition.has("consume") && definition.get("consume").getAsBoolean()) {
                        text.append("（消耗 1 件）");
                    }
                    if (definition.has("damage") && definition.get("damage").getAsInt() > 0) {
                        text.append("（耐久 ").append(definition.get("damage").getAsInt()).append("）");
                    }
                    yield text.toString();
                }
                default -> throw new IllegalStateException(name);
            };
        }
    }

    record Puzzle(String name) implements PuzzleType {
        @Override
        public PuzzleSession start(ServerPlayer player, BlockPos position, JsonObject definition) {
            return new BuiltInPuzzleSession(name, player, definition);
        }

        @Override
        public void validate(JsonObject definition, String path) {
            DefinitionFields.builtInStep(name, definition, path);
        }

        @Override
        public String description(JsonObject definition) {
            return switch (name) {
                case "click" -> "交互";
                case "sneak" -> "潜行交互";
                case "hold" -> "长按 " + (definition.has("ticks") ? definition.get("ticks").getAsInt() : 20) + " 刻";
                case "password" -> "密码锁";
                case "audio_morse" -> "听取摩斯电码";
                case "pattern_morse" -> "辨认图案摩斯";
                case "rolling_lock" -> "滚动字符锁";
                case "accelerated_decode" -> "自动破译 " + (definition.has("passive_ticks") ? definition.get("passive_ticks").getAsInt() : 400) + " 刻";
                default -> throw new IllegalStateException(name);
            };
        }
    }
}
