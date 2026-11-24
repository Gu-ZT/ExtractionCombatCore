package dev.extraction.core;

import dev.extraction.core.server.CabinetStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class CabinetEntity extends BlockEntity implements CabinetStorage {
    private ResourceLocation definition = ExtractionCore.id("service_cabinet");
    private CompoundTag contents = new CompoundTag();

    CabinetEntity(BlockPos pos, BlockState state) {
        super(ExtractionCore.CABINET_ENTITY.get(), pos, state);
    }

    public void bind(ResourceLocation id) {
        definition = id;
        setChanged();
    }

    @Override
    public ResourceLocation definitionId() {
        return definition;
    }

    @Override
    public CompoundTag cabinetData() {
        return contents.copy();
    }

    @Override
    public void saveCabinet(CompoundTag data) {
        contents = data.copy();
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("definition", definition.toString());
        tag.put("contents", contents.copy());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("definition")) {
            definition = ResourceLocation.parse(tag.getString("definition"));
        }
        contents = tag.getCompound("contents");
    }
}
