package dev.extraction.core;

import dev.extraction.core.server.CabinetStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class CabinetEntity extends BlockEntity implements CabinetStorage {
    private Identifier definition = ExtractionCore.id("service_cabinet");
    private CompoundTag contents = new CompoundTag();

    CabinetEntity(BlockPos pos, BlockState state) {
        super(ExtractionCore.CABINET_ENTITY.get(), pos, state);
    }

    public void bind(Identifier id) {
        definition = id;
        setChanged();
    }

    @Override
    public Identifier definitionId() {
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
    protected void saveAdditional(ValueOutput tag) {
        super.saveAdditional(tag);
        tag.putString("definition", definition.toString());
        tag.store("contents", CompoundTag.CODEC, contents);
    }

    @Override
    protected void loadAdditional(ValueInput tag) {
        super.loadAdditional(tag);
        definition = tag.getString("definition").map(Identifier::parse).orElseGet(() -> ExtractionCore.id("service_cabinet"));
        contents = tag.read("contents", CompoundTag.CODEC).orElseGet(CompoundTag::new);
    }
}
