package dev.extraction.core.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.extraction.core.api.AccessCondition;
import dev.extraction.core.api.LockDefinition;
import dev.extraction.core.api.LockTypes;
import dev.extraction.core.api.PuzzleSession;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

final class AccessChallenge {
    private final JsonObject route;
    private final JsonArray steps;
    private final BlockPos position;
    private int index;
    private int round;
    private Optional<Stage> active = Optional.empty();

    private record Stage(ResourceLocation id, PuzzleSession session) {}

    AccessChallenge(JsonObject route, ServerPlayer player, BlockPos position) {
        this.route = route;
        this.steps = array(route, "steps");
        this.position = position.immutable();
        start(player);
    }

    private void start(ServerPlayer player) {
        if (complete()) {
            active = Optional.empty();
            return;
        }
        round = player.getRandom().nextInt();
        JsonObject step = steps.get(index).getAsJsonObject();
        ResourceLocation id = type(step);
        active = LockTypes.PUZZLES.getOptional(id)
            .map(type -> new Stage(id, type.start(player, position, step.deepCopy())));
    }

    private void advance(ServerPlayer player) {
        index++;
        start(player);
    }

    boolean complete() {
        return index >= steps.size();
    }

    boolean allowed(ServerPlayer player, BlockPos position, boolean pay) {
        return conditions(route, player, position, pay);
    }

    static boolean conditions(JsonObject route, ServerPlayer player, BlockPos pos, boolean pay) {
        for (JsonElement element : array(route, "steps")) {
            if (!LockTypes.PUZZLES.containsKey(type(element.getAsJsonObject()))) {
                return false;
            }
        }
        List<HeldRequirement> held = new ArrayList<>();
        for (JsonElement element : array(route, "requires")) {
            JsonObject requirement = element.getAsJsonObject();
            Optional<AccessCondition> registered = LockTypes.CONDITIONS.getOptional(type(requirement));
            if (registered.isEmpty()) {
                return false;
            }
            AccessCondition condition = registered.get();
            if (condition instanceof BuiltInLockTypes.Condition builtIn && builtIn.held()) {
                held.add(new HeldRequirement(builtIn.name(), requirement));
            } else if (!condition.test(player, pos, requirement)) {
                return false;
            }
        }
        return HeldRequirement.check(player, held, pay);
    }

    static ListTag routeList(StoredGrid grid, ServerPlayer player, BlockPos position) {
        ListTag routes = new ListTag();
        for (JsonElement element : grid.routes()) {
            JsonObject route = element.getAsJsonObject();
            CompoundTag detail = new CompoundTag();
            detail.putString("name", route.has("name") ? route.get("name").getAsString() : "路线 " + (routes.size() + 1));
            detail.putBoolean("available", conditions(route, player, position, false));
            StringJoiner description = new StringJoiner(" ");
            describe(description, LockTypes.CONDITIONS, array(route, "requires"));
            describe(description, LockTypes.PUZZLES, array(route, "steps"));
            detail.putString("description", description.toString());
            routes.add(detail);
        }
        return routes;
    }

    private static <T extends LockDefinition> void describe(StringJoiner text, Registry<T> registry, JsonArray definitions) {
        for (JsonElement element : definitions) {
            JsonObject definition = element.getAsJsonObject();
            ResourceLocation id = type(definition);
            String description = registry.getOptional(id).map(entry -> entry.description(definition)).orElse("缺少类型 " + id);
            if (!description.isBlank()) {
                text.add(description);
            }
        }
    }

    void tick(ServerPlayer player) {
        if (active.isPresent() && active.get().session.tick()) {
            advance(player);
        }
    }

    void input(ServerPlayer player, CompoundTag message) {
        if (active.isEmpty() || !(message.get("round") instanceof IntTag) || message.getInt("round") != round) {
            return;
        }
        Stage stage = active.get();
        boolean completed;
        if (stage.session instanceof BuiltInPuzzleSession) {
            completed = stage.session.input(message);
        } else if (message.getString("action").equals("puzzle_input") && message.get("step") instanceof IntTag
            && message.getInt("step") == index && message.get("input") instanceof CompoundTag input) {
            completed = stage.session.input(input);
        } else {
            return;
        }
        if (completed) {
            advance(player);
        }
    }

    CompoundTag view() {
        CompoundTag out = new CompoundTag();
        if (active.isPresent()) {
            Stage stage = active.get();
            if (stage.session instanceof BuiltInPuzzleSession) {
                out = stage.session.view();
                out.putString("type", stage.id.getPath());
            } else {
                out.putString("type", stage.id.toString());
                out.put("view", stage.session.view().copy());
            }
            out.putInt("round", round);
            out.putInt("step", index);
        }
        return out;
    }

    private static ResourceLocation type(JsonObject definition) {
        return LockTypes.id(definition.get("type").getAsString());
    }

    private static JsonArray array(JsonObject object, String key) {
        return object.has(key) ? object.getAsJsonArray(key) : new JsonArray();
    }
}
