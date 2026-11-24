package dev.extraction.core.data;

import com.google.gson.Gson;
import dev.extraction.core.ExtractionCore;
import dev.extraction.core.client.ClientText;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.data.event.GatherDataEvent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class DemoResources implements DataProvider {
    private static final String ID = ExtractionCore.ID;
    private final PackOutput output;
    private final Map<String, Object> files = new LinkedHashMap<>();

    private DemoResources(PackOutput output) {
        this.output = output;
    }

    public static void gather(GatherDataEvent event) {
        event.getGenerator().addProvider(true, new DemoResources(event.getGenerator().getPackOutput()));
    }

    @Override
    public CompletableFuture<?> run(CachedOutput cache) {
        definitions();
        models();
        languages();
        Gson gson = new Gson();
        var writes = files.entrySet().stream().map(e -> DataProvider.saveStable(cache, gson.toJsonTree(e.getValue()),
            output.getOutputFolder().resolve(e.getKey() + ".json"))).toArray(CompletableFuture<?>[]::new);
        return CompletableFuture.allOf(writes);
    }

    private void asset(String path, Object value) {
        files.put("assets/" + ID + "/" + path, value);
    }

    private void data(String path, Object value) {
        files.put("data/" + ID + "/" + path, value);
    }

    private static String id(String path) {
        return ID + ":" + path;
    }

    private static Object section(String name, String... rows) {
        return Map.of("id", name, "shape", List.of(rows));
    }

    private static Object route(List<?> conditions, Object step) {
        return Map.of("requires", conditions, "steps", List.of(step));
    }

    private void definitions() {
        data("extraction/backpacks/field_pack", Map.of(
            "outer", Map.of("shape", List.of("XX", "XX", "XX"), "rotatable", true),
            "sections", List.of(section("main", "XXXX", "XXXX", "XXXX", "XXXX", "XXXX"), section("side", " X", "XX"))));
        data("extraction/qualities/precision", Map.of("name", "精工", "border_color", "#F59E42"));
        data("extraction/items/service_hook", Map.of("item", Map.of("items", id("service_hook")),
            "shape", List.of("  X  ", "XXXXX"), "rotatable", true, "grid_stack_limit", 1,
            "icons", Map.of("grid", id("item/service_hook_grid"), "vanilla_slot", id("item/service_hook_slot")),
            "quality", id("precision")));
        for (String tool : List.of("service_key", "decoder")) {
            data("extraction/items/" + tool, Map.of("item", Map.of("items", id(tool)),
                "shape", List.of("X"), "icons", Map.of("vanilla_slot", id("item/" + tool))));
        }
        data("loot_table/service_cabinet", Map.of("type", "minecraft:chest", "pools", List.of(
            pool("minecraft:iron_nugget", 4), pool(id("service_hook"), 1))));
        List<?> zones = List.of(section("main", "XXXX  ", "XXXXXX", "XXXXXX"), section("drawer", "XXX", "XX "));
        Object powered = Map.of("type", "powered");
        Object key = Map.of("type", "held_item", "item", id("service_key"), "consume", true);
        Object decoder = Map.of("type", "held_item", "item", id("decoder"));
        for (String variant : ExtractionCore.DEMO_CABINETS) {
            List<?> routes = switch (variant) {
                case "service_cabinet" -> List.of(route(List.of(powered, key), Map.of("type", "hold", "ticks", 20)),
                    route(List.of(powered, decoder), Map.of("type", "accelerated_decode", "passive_ticks", 400, "hit_bonus", 0.15)));
                case "password" -> List.of(route(List.of(), Map.of("type", variant, "answer", "0472")));
                case "audio_morse", "pattern_morse" -> List.of(route(List.of(), Map.of("type", variant, "answer", "SOS", "show_table", true, "flash", true)));
                case "rolling_lock" -> List.of(route(List.of(), Map.of("type", variant, "positions", 5, "error_pause_ticks", 40)));
                default -> List.of();
            };
            Map<String, Object> cabinet = new LinkedHashMap<>();
            cabinet.put("sections", zones);
            cabinet.put("loot_table", id("service_cabinet"));
            cabinet.put("search", variant.equals("tool_rack") ? Map.of("mode", "instant") : Map.of("mode", "per_item", "ticks_per_item", 24));
            if (!routes.isEmpty()) {
                cabinet.put("access", Map.of("routes", routes));
            }
            data("extraction/containers/" + variant, cabinet);
        }
    }

    private static Object pool(String item, int count) {
        return Map.of("rolls", 1, "entries", List.of(Map.of("type", "minecraft:item", "name", item,
            "functions", List.of(Map.of("function", "minecraft:set_count", "count", count)))));
    }

    private static Object box(int x1, int y1, int z1, int x2, int y2, int z2, String surface) {
        Map<String, Object> faces = new LinkedHashMap<>();
        for (String side : List.of("up", "down", "east", "west", "north", "south")) {
            faces.put(side, Map.of("texture", "#" + surface));
        }
        return Map.of("from", List.of(x1, y1, z1), "to", List.of(x2, y2, z2), "faces", faces);
    }

    private void objectModel(String path, Map<String, String> textures, Object... elements) {
        asset("models/" + path, Map.of("parent", "minecraft:block/block", "textures", textures, "elements", List.of(elements),
            "display", Map.of("gui", Map.of("rotation", List.of(25, 222, 0), "scale", List.of(0.84, 0.84, 0.84)),
                "firstperson_righthand", Map.of("rotation", List.of(0, 145, 0), "scale", List.of(0.6, 0.6, 0.6)),
                "thirdperson_righthand", Map.of("rotation", List.of(70, 45, 0), "scale", List.of(0.45, 0.45, 0.45)),
                "ground", Map.of("scale", List.of(0.44, 0.44, 0.44), "translation", List.of(0, 3, 0)))));
    }

    private void models() {
        asset("sounds", Map.of("morse_dot", Map.of("sounds", List.of(id("morse_dot"))),
            "morse_dash", Map.of("sounds", List.of(id("morse_dash")))));
        String cloth = id("block/canvas");
        String metal = id("block/cabinet_steel");
        String trim = id("block/dark_trim");
        String brass = id("block/brass");
        objectModel("item/backpack", Map.of("particle", cloth, "cloth", cloth, "trim", trim, "brass", brass),
            box(3, 2, 5, 13, 13, 12, "cloth"), box(2, 10, 4, 14, 14, 12, "cloth"),
            box(4, 2, 3, 12, 7, 5, "cloth"), box(4, 6, 3, 12, 7, 4, "trim"),
            box(1, 3, 6, 3, 9, 11, "cloth"), box(13, 3, 6, 15, 9, 11, "cloth"),
            box(4, 8, 3, 6, 13, 4, "trim"), box(10, 8, 3, 12, 13, 4, "trim"),
            box(4, 9, 2, 6, 10, 4, "brass"), box(10, 9, 2, 12, 10, 4, "brass"),
            box(5, 14, 7, 6, 16, 9, "trim"), box(10, 14, 7, 11, 16, 9, "trim"), box(6, 15, 7, 10, 16, 9, "trim"));
        objectModel("block/cabinet", Map.of("particle", metal, "metal", metal, "trim", trim, "brass", brass),
            box(0, 0, 1, 16, 16, 16, "trim"), box(1, 1, 1, 15, 15, 15, "metal"),
            box(1, 5, 0, 15, 15, 1, "metal"), box(1, 1, 0, 15, 4, 1, "metal"),
            box(2, 12, 0, 7, 13, 1, "trim"), box(2, 10, 0, 7, 11, 1, "trim"),
            box(11, 8, -1, 13, 11, 1, "brass"), box(6, 2, -1, 10, 3, 1, "trim"));
        asset("models/item/cabinet", Map.of("parent", id("block/cabinet")));
        Map<String, Object> variants = new LinkedHashMap<>();
        int turns = 0;
        for (String facing : List.of("north", "east", "south", "west")) {
            variants.put("facing=" + facing, Map.of("model", id("block/cabinet"), "y", turns * 90));
            turns++;
        }
        asset("blockstates/cabinet", Map.of("variants", variants));
        objectModel("item/service_key", Map.of("particle", brass, "metal", brass, "trim", trim),
            box(3, 10, 7, 5, 15, 9, "metal"), box(9, 10, 7, 11, 15, 9, "metal"),
            box(5, 14, 7, 9, 16, 9, "metal"), box(4, 9, 7, 10, 11, 9, "metal"),
            box(6, 1, 7, 8, 10, 9, "metal"), box(8, 1, 7, 11, 3, 9, "metal"),
            box(8, 4, 7, 10, 5, 9, "metal"), box(5, 8, 6, 9, 9, 10, "trim"));
        String phosphor = id("block/phosphor");
        objectModel("item/decoder", Map.of("particle", trim, "metal", metal, "trim", trim, "brass", brass, "display", phosphor),
            box(3, 1, 5, 13, 12, 10, "trim"), box(4, 2, 4, 12, 11, 5, "metal"),
            box(5, 7, 3, 11, 10, 4, "display"), box(5, 4, 3, 7, 5, 4, "brass"),
            box(9, 4, 3, 11, 5, 4, "trim"), box(10, 12, 6, 12, 14, 9, "metal"),
            box(10, 14, 7, 11, 16, 8, "trim"), box(5, 12, 6, 7, 13, 8, "brass"));
        asset("models/item/service_hook", Map.of("parent", "minecraft:item/handheld",
            "textures", Map.of("layer0", id("item/service_hook"))));
    }

    private void languages() {
        String[] names = {"backpack","cabinet","service_key","decoder","service_hook","field_pack","service_cabinet","password","audio_morse","pattern_morse","rolling_lock","tool_rack"};
        String[] zh = {"随身背包","搜刮柜","维修钥匙","手持译码器","维修挂钩","野外随身包","通电维修柜","密码柜","音频摩斯柜","图案摩斯柜","滚动锁柜","敞开工具架"};
        String[] en = {"Backpack","Search Cabinet","Service Key","Handheld Decoder","Service Hook","Field Pack","Powered Service Cabinet","Password Cabinet","Audio Morse Cabinet","Pattern Morse Cabinet","Rolling Lock Cabinet","Open Tool Rack"};
        for (boolean chinese : List.of(true, false)) {
            Map<String, String> strings = new LinkedHashMap<>();
            for (int i = 0; i < names.length; i++) {
                String prefix = i == 1 ? "block" : i < 5 ? "item" : "definition";
                strings.put(prefix + "." + ID + "." + names[i], chinese ? zh[i] : en[i]);
            }
            strings.put("tab." + ID, chinese ? "搜刮演示套件" : "Extraction Demo Equipment");
            ClientText.fill(strings::put, !chinese);
            asset("lang/" + (chinese ? "zh_cn" : "en_us"), strings);
        }
    }

    @Override
    public String getName() { return "搜刮演示定义与客户端资源"; }
}
