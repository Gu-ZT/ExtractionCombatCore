package dev.extraction.core.api;

import com.google.gson.JsonObject;

/** 容器开启路线使用的注册类型。数据包加载时校验定义，服务端在路线选择界面展示说明。 */
public interface LockDefinition {
    /** 校验只读定义。配置出错时抛出 IllegalArgumentException，消息应包含 path 和出错的字段名。 */
    default void validate(JsonObject definition, String path) {}

    /** 在服务端主线程生成可向玩家公开的路线说明；空字符串表示省略说明。 */
    default String description(JsonObject definition) {
        return "";
    }
}
