package dev.extraction.core.network;

import dev.anvilcraft.lib.v2.network.packet.IServerboundPacket;
import dev.anvilcraft.lib.v2.rpc.IRemoteCallableValidator;
import dev.anvilcraft.lib.v2.rpc.RPC;
import dev.anvilcraft.lib.v2.rpc.RemoteCallable;
import dev.anvilcraft.lib.v2.rpc.RpcTarget;
import dev.extraction.core.client.ExtractionClient;
import dev.extraction.core.server.LootSessions;
import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.lang.reflect.Method;

public final class ExtractionNetwork {
    private ExtractionNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(Action.TYPE, Action.CODEC, Action::serverHandler);
    }

    public static void send(CompoundTag message) {
        ClientPacketDistributor.sendToServer(new Action(message));
    }

    public static void show(ServerPlayer player, CompoundTag message) {
        RPC.call(RpcTarget.player(player), ExtractionNetwork::receive, message);
    }

    @RemoteCallable(validator = ToClient.class)
    public static void receive(CompoundTag message) {
        ExtractionClient.receive(message);
    }

    public static final class ToClient implements IRemoteCallableValidator {
        @Override
        public boolean validate(IPayloadContext context, Method method, Object[] arguments) {
            return context.flow().isClientbound();
        }
    }

    private record Action(CompoundTag message) implements IServerboundPacket {
        private static final Type<Action> TYPE = new Type<>(Identifier.fromNamespaceAndPath("extractioncombatcore", "action"));
        private static final StreamCodec<ByteBuf, Action> CODEC =
            ByteBufCodecs.COMPOUND_TAG.map(Action::new, Action::message);

        @Override
        public void handleOnServer(Player player) {
            if (player instanceof ServerPlayer sender) {
                LootSessions.act(sender, message);
            }
        }

        @Override
        public CustomPacketPayload.Type<Action> type() {
            return TYPE;
        }
    }
}
