package dev.extraction.core.rules;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.extraction.core.api.LockDefinition;
import dev.extraction.core.api.LockTypes;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;

import java.nio.charset.StandardCharsets;

/** 检查数据包字段并在错误中保留配置路径。 */
public final class DefinitionFields {
    private DefinitionFields() {}

    static JsonObject object(JsonElement element, String path) {
        if (element == null || !element.isJsonObject()) {
            throw invalid(path, "必须是对象");
        }
        return element.getAsJsonObject();
    }

    static JsonArray array(JsonObject parent, String key, String path) {
        JsonElement value = parent.get(key);
        if (value == null || !value.isJsonArray()) {
            throw invalid(path + "." + key, "必须是数组");
        }
        return value.getAsJsonArray();
    }

    static String string(JsonObject parent, String key, String path) {
        JsonElement value = parent.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isEmpty()) {
            throw invalid(path + "." + key, "必须是非空字符串");
        }
        return value.getAsString();
    }

    static boolean bool(JsonObject parent, String key, boolean fallback, String path) {
        if (!parent.has(key)) {
            return fallback;
        }
        JsonElement value = parent.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw invalid(path + "." + key, "必须是布尔值");
        }
        return value.getAsBoolean();
    }

    static int integer(JsonObject parent, String key, int fallback, int min, int max, String path) {
        if (!parent.has(key)) {
            return fallback;
        }
        JsonElement value = parent.get(key);
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            try {
                int result = value.getAsBigDecimal().intValueExact();
                if (result >= min && result <= max) {
                    return result;
                }
            } catch (ArithmeticException exception) {
                throw invalid(path + "." + key, "必须是范围内的整数");
            }
        }
        throw invalid(path + "." + key, "必须是 " + min + " 至 " + max + " 的整数");
    }

    static Identifier resource(String value, String path) {
        Identifier id = Identifier.tryParse(value);
        if (id == null) {
            throw invalid(path, "无效资源 ID " + value);
        }
        return id;
    }

    static IllegalArgumentException invalid(String path, String reason) {
        return new IllegalArgumentException(path + " " + reason);
    }

    static void access(JsonObject access, String path, boolean savedLayout) {
        JsonArray routes = array(access, "routes", path);
        for (int i = 0; i < routes.size(); i++) {
            String routePath = path + ".routes[" + i + "]";
            JsonObject route = object(routes.get(i), routePath);
            if (route.has("requires")) {
                JsonArray requirements = array(route, "requires", routePath);
                for (int n = 0; n < requirements.size(); n++) {
                    String requirementPath = routePath + ".requires[" + n + "]";
                    validateType(LockTypes.CONDITIONS, object(requirements.get(n), requirementPath), requirementPath, savedLayout);
                }
            }
            JsonArray steps = array(route, "steps", routePath);
            for (int n = 0; n < steps.size(); n++) {
                String stepPath = routePath + ".steps[" + n + "]";
                validateType(LockTypes.PUZZLES, object(steps.get(n), stepPath), stepPath, savedLayout);
            }
        }
    }

    private static <T extends LockDefinition> void validateType(Registry<T> registry, JsonObject definition, String path, boolean savedLayout) {
        String type = string(definition, "type", path);
        Identifier id = resource(type.indexOf(':') < 0 ? "extractioncombatcore:" + type : type, path + ".type");
        var entry = registry.getOptional(id);
        if (entry.isEmpty()) {
            if (savedLayout) {
                return;
            }
            throw invalid(path + ".type", "未注册的类型 " + id);
        }
        entry.get().validate(definition, path);
    }

    /** 校验核心注册的开柜条件，并在异常中标记数据包字段。 */
    public static void builtInCondition(String type, JsonObject condition, String path) {
        switch (type) {
            case "powered" -> {}
            case "unlocked" -> resource(string(condition, "flag", path), path + ".flag");
            case "held_item", "held_item_tag" -> {
                String key = type.equals("held_item") ? "item" : "tag";
                String id = string(condition, key, path);
                resource(key.equals("tag") && id.startsWith("#") ? id.substring(1) : id, path + "." + key);
                bool(condition, "consume", false, path);
                integer(condition, "damage", 0, 0, Integer.MAX_VALUE, path);
            }
            default -> throw invalid(path + ".type", "未知条件 " + type);
        }
    }

    /** 校验核心注册的小游戏参数，并在异常中标记数据包字段。 */
    public static void builtInStep(String type, JsonObject step, String path) {
        integer(step, "error_pause_ticks", 0, 0, Integer.MAX_VALUE, path);
        switch (type) {
            case "click", "sneak" -> {}
            case "hold" -> integer(step, "ticks", 20, 1, Integer.MAX_VALUE, path);
            case "password" -> answer(step, path);
            case "audio_morse", "pattern_morse" -> {
                String answer = answer(step, path);
                if (!answer.matches("[0-9A-Za-z ]+") || answer.isBlank()) {
                    throw invalid(path + ".answer", "摩斯消息仅支持字母、数字和空格");
                }
                bool(step, "show_table", true, path);
                bool(step, "flash", true, path);
                if (step.has("dot")) {
                    string(step, "dot", path);
                }
                if (step.has("dash")) {
                    string(step, "dash", path);
                }
            }
            case "rolling_lock" -> {
                integer(step, "positions", 5, 3, 9, path);
                if (step.has("alphabet") && string(step, "alphabet", path).chars().distinct().count() < 2) {
                    throw invalid(path + ".alphabet", "至少需要两种字符");
                }
            }
            case "accelerated_decode" -> {
                integer(step, "passive_ticks", 400, 1, Integer.MAX_VALUE, path);
                if (step.has("hit_bonus")) {
                    JsonElement bonus = step.get("hit_bonus");
                    if (!bonus.isJsonPrimitive() || !bonus.getAsJsonPrimitive().isNumber()
                        || !(bonus.getAsDouble() >= 0 && bonus.getAsDouble() <= 1)) {
                        throw invalid(path + ".hit_bonus", "必须是 0 至 1 的数值");
                    }
                }
            }
            default -> throw invalid(path + ".type", "未知步骤 " + type);
        }
    }

    private static String answer(JsonObject step, String path) {
        String value = string(step, "answer", path);
        if (value.getBytes(StandardCharsets.UTF_8).length > 32000) {
            throw invalid(path + ".answer", "答案的 UTF-8 编码超过操作包可用的 32000 字节");
        }
        return value;
    }
}
