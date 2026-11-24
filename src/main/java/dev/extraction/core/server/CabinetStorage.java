package dev.extraction.core.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;

/** 通用容器方块实体在服务端主线程上读写搜刮进度与库存。 */
public interface CabinetStorage {
    Identifier definitionId();

    CompoundTag cabinetData();

    void saveCabinet(CompoundTag data);
}
