package dev.extraction.core.api;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/** 注册到开柜条件表的只读判定。类型实例由所有容器与玩家共享。 */
@FunctionalInterface
public interface AccessCondition extends LockDefinition {
    /** 在服务端主线程检查指定玩家和柜子。预览、挑战进行中和成功结算前均可能调用，判定应无副作用。 */
    boolean test(ServerPlayer player, BlockPos position, JsonObject definition);
}
