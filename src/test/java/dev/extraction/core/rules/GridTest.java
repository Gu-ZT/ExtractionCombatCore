package dev.extraction.core.rules;

import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GridTest {
    @BeforeAll
    static void initializeItems() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static Grid.Entry entry(Item item, int count, List<String> shape, int x) {
        Grid.Style style = new Grid.Style(Shape.of(shape), true, 64, Optional.empty(), Optional.empty(), Optional.empty());
        return new Grid.Entry(UUID.randomUUID(), new ItemStack(item, count), style, 0, x, 0, 0);
    }

    private static List<Grid.Section> board(String... rows) {
        return List.of(new Grid.Section("main", Shape.of(List.of(rows)), Optional.empty()));
    }

    @Test
    void triesMergingAndRotatingWithoutMovingOtherItems() {
        Grid.Entry iron = entry(Items.IRON_NUGGET, 63, List.of("X"), 1);
        Grid.Entry more = entry(Items.IRON_NUGGET, 2, List.of("X"), 0);
        List<Grid.Entry> placed = Grid.direct(board("XX"), List.of(iron), more, s -> true).orElseThrow();
        assertEquals(64, placed.getFirst().stack().getCount());
        assertEquals(1, placed.getFirst().x());
        assertEquals(1, placed.getLast().stack().getCount());
        assertEquals(0, placed.getLast().x());
        assertEquals(63, iron.stack().getCount());
        assertTrue(Grid.direct(board("XX"), List.of(iron), more, s -> false).isEmpty());
        Grid.Entry hook = entry(Items.TRIPWIRE_HOOK, 1, List.of("  X  ", "XXXXX"), 0);
        Grid.Entry result = Grid.direct(board("XX", "XX", "XX", "XX", "XX"), List.of(), hook, s -> true).orElseThrow().getFirst();
        assertEquals(1, result.rotation());
    }

    @Test
    void waitsForSearchAndCommitsOnlyCompleteLayouts() {
        Grid.Entry a = entry(Items.IRON_NUGGET, 1, List.of("X"), 1);
        Grid.Entry b = entry(Items.GOLD_NUGGET, 1, List.of("X"), 3);
        Grid.Entry tool = entry(Items.TRIPWIRE_HOOK, 1, List.of("XXX"), 0);
        List<Grid.Section> sections = board("XXXXX");
        assertTrue(Grid.direct(sections, List.of(a, b), tool, s -> true).isEmpty());
        Grid.Search search = Grid.repack(sections, List.of(a, b), tool, s -> true);
        assertEquals(Grid.Status.RUNNING, search.step(1));
        int steps = 0;
        while (search.step(1) == Grid.Status.RUNNING) {
            assertTrue(++steps < 500);
        }
        List<Grid.Entry> result = search.result();
        assertEquals(3, result.size());
        for (Grid.Entry element : result) {
            assertTrue(Grid.fits(sections, result.stream().filter(e -> !e.id().equals(element.id())).toList(), element, s -> true));
        }
        assertEquals(1, a.x());
        assertEquals(3, b.x());
        assertEquals(Grid.Status.IMPOSSIBLE, Grid.repack(board("X X X X X"), List.of(a, b), tool, s -> true).step(500));
    }

    @Test
    void preservesSeparateLootDiscoveriesAndCanMergeOldStacksWhenRepacking() {
        Grid.Entry first = entry(Items.IRON_NUGGET, 2, List.of("X"), 0);
        Grid.Entry second = entry(Items.IRON_NUGGET, 3, List.of("X"), 1);
        Grid.Entry tool = entry(Items.TRIPWIRE_HOOK, 1, List.of("XX"), 0);
        List<Grid.Entry> separate = Grid.directNew(board("XXX"), List.of(first), second, s -> true).orElseThrow();
        assertEquals(2, separate.size());
        assertEquals(2, separate.getFirst().stack().getCount());
        var sections = board("XXX");
        assertTrue(Grid.direct(sections, separate, tool, s -> true).isEmpty());
        Grid.Search search = Grid.repack(sections, separate, tool, s -> true);
        assertEquals(Grid.Status.FOUND, search.step(30));
        assertEquals(2, search.result().size());
        assertEquals(5, search.result().stream().filter(e -> e.stack().is(Items.IRON_NUGGET)).findFirst().orElseThrow().stack().getCount());
    }

    @Test
    void keepsUnfinishedSearchPrivateAndPreventsCrossingSections() {
        List<Grid.Section> sections = List.of(
            new Grid.Section("left", Shape.of(List.of("XX")), Optional.empty()),
            new Grid.Section("right", Shape.of(List.of("XX")), Optional.empty()));
        Grid.Entry iron = entry(Items.IRON_NUGGET, 2, List.of("X"), 1);
        Grid.Entry tool = entry(Items.TRIPWIRE_HOOK, 1, List.of("XXX"), 0);
        List<Grid.Entry> original = List.of(iron);
        Grid.Search partial = Grid.repack(sections, original, tool, s -> true);
        assertThrows(IllegalStateException.class, partial::result);
        assertTrue(Grid.direct(sections, original, tool, s -> true).isEmpty());
        assertEquals(Grid.Status.IMPOSSIBLE, partial.step(100));
        assertThrows(IllegalStateException.class, partial::result);
        assertEquals(2, original.getFirst().stack().getCount());
        assertEquals(1, original.getFirst().x());
        assertEquals(0, original.getFirst().section());
    }

    @Test
    void distinguishesVanillaInlineAndBothDisabledQualityForms() {
        String json = """
            {"items":{
              "example:plain":{"item":{"items":"minecraft:diamond"}},
              "example:polished":{"item":{"items":"minecraft:diamond_sword"},"quality":{"id":"example:polished","name":"抛光","border_color":"#c4cdd6"}},
              "example:hidden":{"item":{"items":"minecraft:compass"},"quality":"disable"},
              "example:hidden_object":{"item":{"items":"minecraft:clock"},"quality":{"id":"disable"}}
            }}
            """;
        var registry = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        RuleBook book = RuleBook.restore(json, registry);
        assertTrue(book.item(new ItemStack(Items.DIAMOND)).quality().isEmpty());
        Grid.Quality polished = book.item(new ItemStack(Items.DIAMOND_SWORD)).quality().orElseThrow();
        assertEquals(new Grid.Quality("抛光", 0xC4CDD6, false), polished);
        Grid.Quality disabled = book.item(new ItemStack(Items.COMPASS)).quality().orElseThrow();
        assertTrue(disabled.disabled());
        assertEquals(disabled, book.item(new ItemStack(Items.CLOCK)).quality().orElseThrow());
    }

    @Test
    void loadsSharedQualityOverridesAndFreezesResolvedTags(@TempDir Path temporary) throws IOException {
        Path low = temporary.resolve("low");
        Path high = temporary.resolve("high");
        write(low, "foo/extraction/qualities/shared", """
            {"name":"普通","border_color":"#eeeeee"}
            """);
        write(high, "foo/extraction/qualities/shared", """
            {"name":"精工","border_color":"#ee8800"}
            """);
        write(low, "foo/extraction/items/tools", """
            {"item":{"items":"#foo:tools"},"quality":"foo:shared"}
            """);
        write(low, "bar/extraction/items/iron", """
            {"item":{"items":"minecraft:iron_nugget"},"quality":"foo:shared"}
            """);
        write(low, "bar/extraction/backpacks/bag", """
            {"outer":{"shape":["X"]},"sections":[{"id":"main","shape":["XX"],"accepts_tag":"foo:tools"}]}
            """);
        var registry = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        try (var manager = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(pack(low), pack(high)))) {
            var tag = ResourceLocation.parse("foo:tools");
            RuleBook book = RuleBook.resolve(RuleBook.read(manager), Map.of(tag, List.of(Items.COMPASS.builtInRegistryHolder())), registry);
            Grid.Quality expected = new Grid.Quality("精工", 0xEE8800, false);
            assertEquals(expected, book.item(new ItemStack(Items.COMPASS)).quality().orElseThrow());
            assertEquals(expected, book.item(new ItemStack(Items.IRON_NUGGET)).quality().orElseThrow());
            var stored = book.backpack(ResourceLocation.parse("bar:bag")).orElseThrow();
            Grid.Section restored = RuleBook.restoreStorage(stored.definition(), true).sections().getFirst();
            assertTrue(restored.accepts(new ItemStack(Items.COMPASS)));
            assertFalse(restored.accepts(new ItemStack(Items.IRON_NUGGET)));
        }
    }

    private static PathPackResources pack(Path root) {
        String name = root.getFileName().toString();
        return new PathPackResources(new PackLocationInfo(name, Component.literal(name), PackSource.DEFAULT, Optional.empty()), root);
    }

    private static void write(Path pack, String path, String json) throws IOException {
        Path file = pack.resolve("data").resolve(path + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, json);
    }

    @Test
    void selectsMostSpecificWholePredicateAcrossNamesAndComponents() {
        String json = """
            {
              "qualities":{"foo:precision":{"name":"精工","border_color":"#F59E42"}},
              "items":{
                "foo:a_tools":{"item":{"items":["minecraft:tripwire_hook","minecraft:compass"]},"grid_stack_limit":3},
                "foo:b_hook":{"item":{"items":"minecraft:tripwire_hook"},"quality":"foo:precision"},
                "foo:c_model":{"item":{"items":"minecraft:tripwire_hook","components":{"minecraft:custom_model_data":{"floats":[7]}}},"quality":{"id":"disable"}}
              },
              "containers":{"foo:locker":{"sections":[{"id":"main","shape":["X"]}],
                "access":{"routes":[{"steps":[{"type":"password","answer":"0472"}]}]}}}
            }
            """;
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        RuleBook book = RuleBook.restore(json, registries);
        assertEquals(3, book.item(new ItemStack(Items.COMPASS)).stackLimit());
        ItemStack hook = new ItemStack(Items.TRIPWIRE_HOOK);
        Grid.Style normal = book.item(hook);
        assertEquals("精工", normal.quality().orElseThrow().name());
        assertEquals(Grid.Style.DEFAULT.stackLimit(), normal.stackLimit());
        hook.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(7f), List.of(), List.of(), List.of()));
        assertTrue(book.item(hook).quality().orElseThrow().disabled());
        RuleBook.installPublicItems(book.publicItems(), registries);
        RuleBook previous = RuleBook.client();
        assertTrue(previous.container(ResourceLocation.parse("foo:locker")).isEmpty());
        assertTrue(previous.item(hook).quality().orElseThrow().disabled());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
            RuleBook.installPublicItems(book.publicItems().replace("\"quality\":\"foo:precision\"", "\"quality\":\"foo:missing\""), registries));
        assertTrue(error.getMessage().contains("foo:b_hook.quality"));
        assertTrue(error.getMessage().contains("foo:missing"));
        assertSame(previous, RuleBook.client());
    }
}
