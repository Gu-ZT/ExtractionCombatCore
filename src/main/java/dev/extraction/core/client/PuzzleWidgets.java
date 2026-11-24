package dev.extraction.core.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** 附属模组在物理客户端为已注册的开柜小游戏绑定原版界面控件。 */
public final class PuzzleWidgets {
    private static final Map<ResourceLocation, Factory> FACTORIES = new ConcurrentHashMap<>();

    private PuzzleWidgets() {}

    /** 在客户端初始化期间按小游戏注册 ID 注册一次，重复注册抛出 IllegalStateException。 */
    public static void register(ResourceLocation type, Factory factory) {
        if (FACTORIES.putIfAbsent(type, Objects.requireNonNull(factory)) != null) {
            throw new IllegalStateException("小游戏界面已注册 " + type);
        }
    }

    static AbstractWidget create(ResourceLocation type, Context context, Font font, int x, int y, int width, int height) {
        Factory factory = FACTORIES.get(type);
        if (factory != null) {
            return factory.create(context, x, y, width, height);
        }
        Component message = Component.translatable("gui.extractioncombatcore.missing_puzzle_widget", type.toString());
        return new StringWidget(x, y, width, height, message, font);
    }

    /** 每次开始新步骤或改变界面尺寸时，在客户端主线程创建控件。 */
    @FunctionalInterface
    public interface Factory {
        /** 返回在给定屏幕区域内绘制和接收原版输入事件的控件。 */
        AbstractWidget create(Context context, int x, int y, int width, int height);
    }

    /** 控件专用的当前步骤上下文，读取和提交输入均在客户端主线程执行。 */
    public interface Context {
        /** 返回服务端最近公开的数据副本。 */
        CompoundTag view();

        /** 把输入提交给服务端判定。关闭、换步或重建后，旧控件的提交将被忽略。 */
        void send(CompoundTag input);
    }
}
