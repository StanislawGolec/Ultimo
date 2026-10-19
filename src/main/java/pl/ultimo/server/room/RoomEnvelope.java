package pl.ultimo.server.room;

import pl.ultimo.protocol.ClientCommand;
import pl.ultimo.server.net.ClientSession;

import java.util.concurrent.CompletableFuture;

/**
 * Sealed actor mailbox message hierarchy for {@link GameRoom}.
 * All room state mutations and lifecycle transitions are sequenced through these envelopes
 * on the room's single virtual-thread actor loop.
 */
public sealed interface RoomEnvelope permits
        RoomEnvelope.CommandEnvelope,
        RoomEnvelope.JoinEnvelope,
        RoomEnvelope.ReconnectEnvelope,
        RoomEnvelope.DisconnectEnvelope,
        RoomEnvelope.WatchdogTickEnvelope,
        RoomEnvelope.AddBotEnvelope {

    record CommandEnvelope(ClientSession session, ClientCommand command) implements RoomEnvelope {}

    record JoinEnvelope(
            ClientSession session,
            String username,
            String accessCode,
            CompletableFuture<Boolean> future
    ) implements RoomEnvelope {
        public JoinEnvelope(ClientSession session, String username, CompletableFuture<Boolean> future) {
            this(session, username, null, future);
        }
    }

    record ReconnectEnvelope(
            ClientSession session,
            String username,
            String token,
            CompletableFuture<Boolean> future
    ) implements RoomEnvelope {}

    record DisconnectEnvelope(ClientSession session) implements RoomEnvelope {}

    record WatchdogTickEnvelope(long nowMillis) implements RoomEnvelope {
        public WatchdogTickEnvelope() {
            this(System.currentTimeMillis());
        }
    }

    record AddBotEnvelope(
            String botName,
            String difficulty,
            CompletableFuture<Boolean> future
    ) implements RoomEnvelope {
        public AddBotEnvelope(String botName, CompletableFuture<Boolean> future) {
            this(botName, "NORMAL", future);
        }
    }
}
