package pl.ultimo.protocol;

import pl.ultimo.core.model.Card;
import pl.ultimo.core.model.CardColor;

import java.util.List;

/**
 * Type-safe, sealed model representing all incoming client commands.
 */
public sealed interface ClientCommand {

    CommandType type();

    record Join(String username, String roomId, Integer maxPlayers, String accessCode) implements ClientCommand {
        public Join(String username) {
            this(username, null, null, null);
        }

        public Join(String username, String roomId) {
            this(username, roomId, null, null);
        }

        public Join(String username, String roomId, Integer maxPlayers) {
            this(username, roomId, maxPlayers, null);
        }

        @Override
        public CommandType type() {
            return CommandType.JOIN;
        }
    }

    record Play(List<Card> cards, CardColor chosenWildColor) implements ClientCommand {
        public Play(Card card, CardColor chosenWildColor) {
            this(List.of(card), chosenWildColor);
        }

        public Card card() {
            return cards != null && !cards.isEmpty() ? cards.get(0) : null;
        }

        @Override
        public CommandType type() {
            return CommandType.PLAY;
        }
    }

    record Draw() implements ClientCommand {
        @Override
        public CommandType type() {
            return CommandType.DRAW;
        }
    }

    record Pass() implements ClientCommand {
        @Override
        public CommandType type() {
            return CommandType.PASS;
        }
    }

    record Chat(String message) implements ClientCommand {
        @Override
        public CommandType type() {
            return CommandType.CHAT;
        }
    }

    record Ping() implements ClientCommand {
        @Override
        public CommandType type() {
            return CommandType.PING;
        }
    }

    record Start() implements ClientCommand {
        @Override
        public CommandType type() {
            return CommandType.START;
        }
    }

    record Ultimo() implements ClientCommand {
        @Override
        public CommandType type() {
            return CommandType.ULTIMO;
        }
    }

    record CatchUltimo(String targetUsername) implements ClientCommand {
        @Override
        public CommandType type() {
            return CommandType.CATCH_ULTIMO;
        }
    }

    record ChallengeWildFour() implements ClientCommand {
        @Override
        public CommandType type() {
            return CommandType.CHALLENGE_WILD_FOUR;
        }
    }

    record CreateRoom(String name, RoomVisibility visibility, String accessCode, Integer maxPlayers) implements ClientCommand {
        public CreateRoom(String name) {
            this(name, RoomVisibility.PUBLIC, null, null);
        }

        public CreateRoom(String name, RoomVisibility visibility) {
            this(name, visibility, null, null);
        }

        @Override
        public CommandType type() {
            return CommandType.CREATE_ROOM;
        }
    }

    record ListRooms() implements ClientCommand {
        @Override
        public CommandType type() {
            return CommandType.LIST_ROOMS;
        }
    }

    record Kick(String targetPlayer) implements ClientCommand {
        @Override
        public CommandType type() {
            return CommandType.KICK;
        }
    }

    record TransferOwnership(String targetPlayer) implements ClientCommand {
        @Override
        public CommandType type() {
            return CommandType.TRANSFER_OWNERSHIP;
        }
    }
}
