package dev.extraction.core;

import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.UUID;

final class WorldContents extends SavedData {
    private static final SavedDataType<WorldContents> TYPE = new SavedDataType<>(ExtractionCore.ID,
        WorldContents::new, RecordCodecBuilder.create(instance -> instance.group(
            CompoundTag.CODEC.fieldOf("packs").forGetter(data -> data.packs),
            CompoundTag.CODEC.fieldOf("flags").forGetter(data -> data.flags)
        ).apply(instance, WorldContents::new)));
    private final CompoundTag packs;
    private final CompoundTag flags;

    private WorldContents() {
        this(new CompoundTag(), new CompoundTag());
    }

    private WorldContents(CompoundTag packs, CompoundTag flags) {
        this.packs = packs;
        this.flags = flags;
    }

    static WorldContents get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    CompoundTag pack(UUID id) {
        return packs.getCompoundOrEmpty(id.toString()).copy();
    }

    void savePack(UUID id, CompoundTag data) {
        packs.put(id.toString(), data.copy());
        setDirty();
    }

    boolean flag(ResourceLocation id) {
        return flags.getBooleanOr(id.toString(), false);
    }

    void flag(ResourceLocation id, boolean value) {
        if (value) {
            flags.putBoolean(id.toString(), true);
        } else {
            flags.remove(id.toString());
        }
        setDirty();
    }
}
