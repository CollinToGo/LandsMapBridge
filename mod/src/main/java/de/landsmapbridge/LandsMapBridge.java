package de.landsmapbridge;

import de.landsmapbridge.protocol.Protocol;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ServerboundPlayChannelEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

public final class LandsMapBridge implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("LandsMapBridge");

    // Clientbound and serverbound payloads are registered separately, so HELLO can use one id for both.
    private static final CustomPacketPayload.Type<RawPayload> HELLO_IN = type(Protocol.HELLO);
    private static final CustomPacketPayload.Type<RawPayload> HELLO_OUT = type(Protocol.HELLO);
    private static final CustomPacketPayload.Type<RawPayload> LANDS = type(Protocol.LANDS);
    private static final CustomPacketPayload.Type<RawPayload> CHUNKS = type(Protocol.CHUNKS);
    private static final CustomPacketPayload.Type<RawPayload> CLAIM = type(Protocol.CLAIM);

    private static boolean helloSent;

    @Override
    public void onInitializeClient() {
        PayloadTypeRegistry.clientboundPlay().register(HELLO_IN, RawPayload.codec(HELLO_IN));
        PayloadTypeRegistry.clientboundPlay().register(LANDS, RawPayload.codec(LANDS));
        PayloadTypeRegistry.clientboundPlay().register(CHUNKS, RawPayload.codec(CHUNKS));
        PayloadTypeRegistry.serverboundPlay().register(HELLO_OUT, RawPayload.codec(HELLO_OUT));
        PayloadTypeRegistry.serverboundPlay().register(CLAIM, RawPayload.codec(CLAIM));

        receive(HELLO_IN, data -> ClaimStore.INSTANCE.onWelcome(Protocol.Welcome.decode(data)));
        receive(LANDS, data -> ClaimStore.INSTANCE.apply(Protocol.LandsUpdate.decode(data)));
        receive(CHUNKS, data -> ClaimStore.INSTANCE.apply(Protocol.ChunksUpdate.decode(data)));

        ClientPlayConnectionEvents.JOIN.register((listener, sender, client) -> {
            helloSent = false;
            ClaimStore.INSTANCE.clear();
            trySendHello();
        });
        // A Paper server announces its plugin channels after the join, so wait for them as well.
        ServerboundPlayChannelEvents.REGISTER.register((listener, sender, client, channels) -> {
            if (channels.contains(HELLO_OUT.id())) {
                client.execute(LandsMapBridge::trySendHello);
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> client.execute(ClaimStore.INSTANCE::clear));
    }

    public static void sendClaimRequest(Protocol.ClaimRequest request) {
        if (ClientPlayNetworking.canSend(CLAIM)) {
            ClientPlayNetworking.send(new RawPayload(CLAIM, request.encode()));
        }
    }

    private static void trySendHello() {
        if (!helloSent && ClientPlayNetworking.canSend(HELLO_OUT)) {
            helloSent = true;
            ClientPlayNetworking.send(new RawPayload(HELLO_OUT, new Protocol.Hello(Protocol.VERSION).encode()));
        }
    }

    private static void receive(CustomPacketPayload.Type<RawPayload> type, Consumer<byte[]> handler) {
        ClientPlayNetworking.registerGlobalReceiver(type, (payload, context) -> {
            try {
                handler.accept(payload.data());
            } catch (RuntimeException e) {
                LOGGER.warn("Could not read {} packet", type.id(), e);
            }
        });
    }

    private static CustomPacketPayload.Type<RawPayload> type(String channel) {
        return new CustomPacketPayload.Type<>(Identifier.parse(channel));
    }

    /** Plugin message with the plain bytes the Paper plugin reads and writes. */
    record RawPayload(CustomPacketPayload.Type<RawPayload> type, byte[] data) implements CustomPacketPayload {
        static StreamCodec<FriendlyByteBuf, RawPayload> codec(CustomPacketPayload.Type<RawPayload> type) {
            return StreamCodec.of((buf, payload) -> buf.writeBytes(payload.data()), buf -> {
                byte[] data = new byte[buf.readableBytes()];
                buf.readBytes(data);
                return new RawPayload(type, data);
            });
        }
    }
}
