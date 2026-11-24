package dev.extraction.core;

import com.mojang.brigadier.arguments.BoolArgumentType;
import dev.extraction.core.rules.RuleBook;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

final class MapCommands {
    private MapCommands() {}

    static void register(RegisterCommandsEvent event) {
        var root = Commands.literal("extraction").requires(source -> source.hasPermission(2));
        for (String kind : new String[]{"backpack", "cabinet"}) {
            root.then(Commands.literal(kind).then(Commands.argument("id", ResourceLocationArgument.id()).executes(ctx -> {
                ResourceLocation id = ResourceLocationArgument.getId(ctx, "id");
                boolean pack = kind.equals("backpack");
                if ((pack ? RuleBook.current().backpack(id) : RuleBook.current().container(id)).isEmpty()) {
                    ctx.getSource().sendFailure(Component.literal("找不到定义 " + id));
                    return 0;
                }
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                ItemStack stack = GameAccess.bound(pack ? ExtractionCore.PACK.get() : ExtractionCore.CABINET_ITEM.get(), id);
                if (!player.getInventory().add(stack)) {
                    player.drop(stack, false);
                }
                return 1;
            })));
        }
        root.then(Commands.literal("flag").then(Commands.argument("id", ResourceLocationArgument.id()).then(
            Commands.argument("value", BoolArgumentType.bool()).executes(ctx -> {
                ResourceLocation id = ResourceLocationArgument.getId(ctx, "id");
                boolean value = BoolArgumentType.getBool(ctx, "value");
                WorldContents.get(ctx.getSource().getLevel()).flag(id, value);
                ctx.getSource().sendSuccess(() -> Component.literal(id + " = " + value), true);
                return 1;
            }))));
        event.getDispatcher().register(root);
    }
}
