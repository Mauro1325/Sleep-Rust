package com.example.persistentbodies.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import com.example.persistentbodies.PersistentBodies;
import com.example.persistentbodies.client.DownedClient;
import com.example.persistentbodies.downed.ReviveManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Network channel of the downed / revive system. The server is the only authority: the client may only ASK to revive
 * ({@link ReviveHoldPacket}); every condition is re-validated on the server every tick. The two server-to-client packets
 * are presentation only (HUD, input lock) and never decide anything.
 */
public final class DownedNetwork {
    private static final String PROTOCOL = "2";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(PersistentBodies.MOD_ID, "main"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private DownedNetwork() { }

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, ReviveHoldPacket.class, ReviveHoldPacket::encode, ReviveHoldPacket::decode,
                ReviveHoldPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, DownedSyncPacket.class, DownedSyncPacket::encode, DownedSyncPacket::decode,
                DownedSyncPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, ReviveProgressPacket.class, ReviveProgressPacket::encode,
                ReviveProgressPacket::decode, ReviveProgressPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public static void sendToAll(Object message) {
        CHANNEL.send(PacketDistributor.ALL.noArg(), message);
    }

    public static void sendTo(ServerPlayer player, Object message) {
        if (player.connection != null) CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    // ---- client -> server: "I am holding the revive key looking at this player" (heartbeat while held) / release
    public record ReviveHoldPacket(UUID target, boolean holding) {
        static void encode(ReviveHoldPacket m, FriendlyByteBuf buf) {
            buf.writeUUID(m.target);
            buf.writeBoolean(m.holding);
        }

        static ReviveHoldPacket decode(FriendlyByteBuf buf) {
            return new ReviveHoldPacket(buf.readUUID(), buf.readBoolean());
        }

        static void handle(ReviveHoldPacket m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                ServerPlayer sender = c.getSender();
                if (sender != null) ReviveManager.onHold(sender, m.target, m.holding); // validated there
            });
            c.setPacketHandled(true);
        }
    }

    // ---- server -> client: full list of downed players (uuid, ticks left until bleed-out or -1)
    public record DownedSyncPacket(List<Entry> entries) {
        public record Entry(UUID id, int ticksLeft) { }

        static void encode(DownedSyncPacket m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.entries.size());
            for (Entry e : m.entries) {
                buf.writeUUID(e.id);
                buf.writeVarInt(e.ticksLeft + 1); // -1 (unlimited) is stored as 0
            }
        }

        static DownedSyncPacket decode(FriendlyByteBuf buf) {
            int n = Math.min(buf.readVarInt(), 1024);
            List<Entry> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) list.add(new Entry(buf.readUUID(), buf.readVarInt() - 1));
            return new DownedSyncPacket(list);
        }

        static void handle(DownedSyncPacket m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> DownedClient.onSync(m.entries)));
            c.setPacketHandled(true);
        }
    }

    // ---- server -> client: progress 0..1 of the revive of {@code target} and its total length in ticks; negative = nothing in progress
    public record ReviveProgressPacket(UUID target, float progress, int totalTicks) {
        static void encode(ReviveProgressPacket m, FriendlyByteBuf buf) {
            buf.writeUUID(m.target);
            buf.writeFloat(m.progress);
            buf.writeVarInt(Math.max(0, m.totalTicks));
        }

        static ReviveProgressPacket decode(FriendlyByteBuf buf) {
            return new ReviveProgressPacket(buf.readUUID(), buf.readFloat(), buf.readVarInt());
        }

        static void handle(ReviveProgressPacket m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> DownedClient.onProgress(m.target, m.progress, m.totalTicks)));
            c.setPacketHandled(true);
        }
    }
}
