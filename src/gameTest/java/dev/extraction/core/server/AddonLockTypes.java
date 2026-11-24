package dev.extraction.core.server;

import com.google.gson.JsonObject;
import dev.extraction.core.ExtractionCore;
import dev.extraction.core.api.LockTypes;
import dev.extraction.core.api.PuzzleSession;
import dev.extraction.core.api.PuzzleType;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

@EventBusSubscriber(modid = ExtractionCore.ID)
public final class AddonLockTypes {
    public static final Identifier CABINET = Identifier.fromNamespaceAndPath("example", "dial_cabinet");

    private AddonLockTypes() {}

    @SubscribeEvent
    public static void register(RegisterEvent event) {
        event.register(LockTypes.CONDITIONS_KEY, entries -> entries.register(Identifier.fromNamespaceAndPath("example", "compass"),
            (player, position, definition) -> player.getOffhandItem().is(Items.COMPASS)));
        event.register(LockTypes.PUZZLES_KEY, entries -> entries.register(Identifier.fromNamespaceAndPath("example", "dial"), new Dial()));
    }

    private static final class Dial implements PuzzleType {
        @Override
        public void validate(JsonObject definition, String path) {
            if (!definition.has("turns") || definition.get("turns").getAsInt() < 1) {
                throw new IllegalArgumentException(path + ".turns 必须为正数");
            }
        }

        @Override
        public PuzzleSession start(ServerPlayer player, BlockPos position, JsonObject definition) {
            int goal = definition.get("turns").getAsInt();
            return new PuzzleSession() {
                private int turns;

                @Override
                public CompoundTag view() {
                    CompoundTag tag = new CompoundTag();
                    tag.putInt("turns", turns);
                    return tag;
                }

                @Override
                public boolean input(CompoundTag input) {
                    return input.getBooleanOr("turn", false) && ++turns == goal;
                }
            };
        }
    }
}
