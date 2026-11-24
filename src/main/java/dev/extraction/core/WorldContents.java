package dev.extraction.core;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.UUID;

final class WorldContents extends SavedData {
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
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
            new Factory<>(WorldContents::new, (tag, registries) -> new WorldContents(tag.getCompound("packs"), tag.getCompound("flags")), null),
            ExtractionCore.ID);
    }

    CompoundTag pack(UUID id) {
        return packs.getCompound(id.toString()).copy();
    }

    void savePack(UUID id, CompoundTag data) {
        packs.put(id.toString(), data.copy());
        setDirty();
    }

    boolean flag(ResourceLocation id) {
        return flags.getBoolean(id.toString());
    }

    void flag(ResourceLocation id, boolean value) {
        if (value) {
            flags.putBoolean(id.toString(), true);
        } else {
            flags.remove(id.toString());
        }
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put("packs", packs.copy());
        tag.put("flags", flags.copy());
        return tag;
    }
}
