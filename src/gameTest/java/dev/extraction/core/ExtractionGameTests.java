package dev.extraction.core;

import dev.extraction.core.server.CabinetGameTests;
import dev.extraction.core.server.StorageReloadGameTests;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

@EventBusSubscriber(modid = ExtractionCore.ID)
public final class ExtractionGameTests {
    private interface Scenario {
        void run(GameTestHelper test) throws IOException;
    }

    private record Case(String id, Scenario scenario, boolean reload) {}

    private static final List<Case> TESTS = List.of(
        new Case("disabled_slots_and_equipment", InventoryGameTests::disabledSlotsAndEquipment, false),
        new Case("recipe_and_ammo_respect_inventory_policy", InventoryGameTests::recipeAndAmmoRespectInventoryPolicy, false),
        new Case("pickup_retains_components_and_remainder", InventoryGameTests::pickupRetainsComponentsAndRemainder, false),
        new Case("equipped_backpack_follows_death_rule", InventoryGameTests::equippedBackpackFollowsDeathRule, false),
        new Case("concurrent_cabinet", CabinetGameTests::concurrentCabinet, false),
        new Case("challenge_inputs", CabinetGameTests::challengeInputs, false),
        new Case("smaller_data_pack", StorageReloadGameTests::smallerDataPack, true),
        new Case("decoder_and_cancellation", StorageReloadGameTests::decoderAndCancellation, false)
    );

    @SubscribeEvent
    public static void functions(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, registry -> TESTS.forEach(test ->
            registry.register(ExtractionCore.id(test.id), helper -> {
                try {
                    test.scenario.run(helper);
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            })));
    }

    @SubscribeEvent
    public static void tests(RegisterGameTestsEvent event) {
        var play = event.registerEnvironment(ExtractionCore.id("play"));
        var reload = event.registerEnvironment(ExtractionCore.id("reload"));
        TESTS.forEach(test -> {
            var id = ExtractionCore.id(test.id);
            event.registerTest(id, new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                new TestData<>(test.reload ? reload : play, ExtractionCore.id("test_room"), 100, 0, true)));
        });
    }

    private ExtractionGameTests() {}
}
