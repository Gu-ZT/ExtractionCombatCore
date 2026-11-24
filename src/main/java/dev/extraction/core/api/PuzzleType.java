package dev.extraction.core.api;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/** 注册到小游戏类型表的工厂。相同类型可以在多个容器、玩家和步骤中同时使用。 */
@FunctionalInterface
public interface PuzzleType extends LockDefinition {
    /** 在服务端主线程开始当前步骤。每次调用返回独立会话，definition 是该步骤的配置副本。 */
    PuzzleSession start(ServerPlayer player, BlockPos position, JsonObject definition);
}
