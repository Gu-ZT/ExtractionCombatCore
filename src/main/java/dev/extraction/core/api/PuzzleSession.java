package dev.extraction.core.api;

import net.minecraft.nbt.CompoundTag;

/** 单个玩家的一次开锁步骤。核心在服务端主线程调用各方法，并在步骤结束或路线取消时丢弃会话。 */
public interface PuzzleSession {
    /** 返回可发送给此玩家的界面数据。答案和其他私有判定状态应由会话自行保存。 */
    CompoundTag view();

    /** 处理玩家传来的小游戏输入。实现需检查输入的字段、范围和当前状态，返回 true 时进入下一步。 */
    boolean input(CompoundTag input);

    /** 每个服务端游戏刻调用一次。返回 true 时进入下一步。 */
    default boolean tick() {
        return false;
    }
}
