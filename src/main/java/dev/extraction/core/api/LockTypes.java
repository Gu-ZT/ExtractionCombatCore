package dev.extraction.core.api;

import dev.extraction.core.ExtractionCore;
import dev.extraction.core.server.BuiltInLockTypes;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 容器开启路线的 NeoForge 注册表。附属模组在自己的模组事件总线上通过 DeferredRegister 注册类型。 */
public final class LockTypes {
    public static final ResourceKey<Registry<AccessCondition>> CONDITIONS_KEY = ResourceKey.createRegistryKey(ExtractionCore.id("access_condition"));
    public static final ResourceKey<Registry<PuzzleType>> PUZZLES_KEY = ResourceKey.createRegistryKey(ExtractionCore.id("puzzle_type"));
    private static final DeferredRegister<AccessCondition> CONDITION_ENTRIES = DeferredRegister.create(CONDITIONS_KEY, ExtractionCore.ID);
    private static final DeferredRegister<PuzzleType> PUZZLE_ENTRIES = DeferredRegister.create(PUZZLES_KEY, ExtractionCore.ID);
    public static final Registry<AccessCondition> CONDITIONS = CONDITION_ENTRIES.makeRegistry(builder -> builder.sync(true));
    public static final Registry<PuzzleType> PUZZLES = PUZZLE_ENTRIES.makeRegistry(builder -> builder.sync(true));

    private LockTypes() {}

    /** 将核心注册表和内置类型绑定到核心的模组事件总线，由核心构造器调用一次。 */
    public static void register(IEventBus bus) {
        BuiltInLockTypes.register(CONDITION_ENTRIES, PUZZLE_ENTRIES);
        CONDITION_ENTRIES.register(bus);
        PUZZLE_ENTRIES.register(bus);
    }

    /** 解析配置中的类型 ID。省略命名空间时使用 extractioncombatcore，格式错误时抛出异常。 */
    public static Identifier id(String name) {
        return name.indexOf(':') < 0 ? ExtractionCore.id(name) : Identifier.parse(name);
    }
}
