package pl.ultimo.protocol;

import pl.ultimo.core.model.Card;
import pl.ultimo.core.model.CardColor;

/**
 * Type-safe, sealed model representing all incoming client commands.
 */
public sealed interface ClientCommand {

    CommandType type();

    record Join(String username) implements ClientCommand {
        @Override
        public CommandType type() {
            return CommandType.JOIN;
        }
    }

    record Play(Card card, CardColor chosenWildColor) implements ClientCommand {
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
}
