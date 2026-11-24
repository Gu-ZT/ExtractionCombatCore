package dev.extraction.core;

import dev.extraction.core.data.DemoResources;
import dev.extraction.core.network.ExtractionNetwork;
import dev.extraction.core.rules.RuleBook;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.List;
import java.util.function.Supplier;

@Mod(ExtractionCore.ID)
public final class ExtractionCore {
    public static final String ID = "extractioncombatcore";
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ID);
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ID);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ID);
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, ID);
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ID);
    public static final Supplier<Item> PACK = ITEMS.registerItem("backpack", FieldPack::new, props -> props.stacksTo(1));
    public static final Supplier<Block> CABINET = BLOCKS.registerBlock("cabinet", CabinetBlock::new,
        props -> props.strength(-1, 3600000).pushReaction(PushReaction.BLOCK).noLootTable());
    public static final Supplier<BlockItem> CABINET_ITEM = ITEMS.registerSimpleBlockItem("cabinet", CABINET);
    public static final Supplier<Item> KEY = ITEMS.registerSimpleItem("service_key");
    public static final Supplier<Item> DECODER = ITEMS.registerSimpleItem("decoder", props -> props.durability(128));
    public static final Supplier<Item> HOOK = ITEMS.registerSimpleItem("service_hook");
    public static final Supplier<BlockEntityType<CabinetEntity>> CABINET_ENTITY = ENTITIES.register("cabinet",
        () -> new BlockEntityType<>(CabinetEntity::new, CABINET.get()));
    public static final Supplier<AttachmentType<ItemStack>> EQUIPPED = ATTACHMENTS.register("equipped_backpack",
        () -> AttachmentType.builder(() -> ItemStack.EMPTY).serialize(ItemStack.MAP_CODEC, stack -> !stack.isEmpty()).copyOnDeath().build());
    public static final List<String> DEMO_CABINETS = List.of("service_cabinet", "password", "audio_morse", "pattern_morse", "rolling_lock", "tool_rack");
    static final ModConfigSpec.BooleanValue MAIN;
    static final ModConfigSpec.IntValue HOTBAR;
    static final ModConfigSpec CONFIG;
    static {
        ModConfigSpec.Builder config = new ModConfigSpec.Builder();
        MAIN = config.define("vanilla_main_inventory_enabled", true);
        HOTBAR = config.defineInRange("enabled_hotbar_slots", 9, 0, 9);
        CONFIG = config.build();
        TABS.register("equipment", () -> CreativeModeTab.builder().title(Component.translatable("tab.extractioncombatcore"))
            .icon(() -> new ItemStack(PACK.get())).displayItems((parameters, output) -> {
                output.accept(GameAccess.bound(PACK.get(), id("field_pack")));
                for (String cabinet : DEMO_CABINETS) {
                    output.accept(GameAccess.bound(CABINET_ITEM.get(), id(cabinet)));
                }
                output.accept(KEY.get());
                output.accept(DECODER.get());
                output.accept(HOOK.get());
            }).build());
    }

    public ExtractionCore(IEventBus bus, ModContainer container) {
        ITEMS.register(bus);
        BLOCKS.register(bus);
        ENTITIES.register(bus);
        ATTACHMENTS.register(bus);
        TABS.register(bus);
        container.registerConfig(ModConfig.Type.SERVER, CONFIG);
        bus.addListener(ExtractionNetwork::register);
        bus.addListener(DemoResources::gather);
        NeoForge.EVENT_BUS.addListener(RuleBook::register);
        NeoForge.EVENT_BUS.addListener(MapCommands::register);
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(ID, path);
    }
}
