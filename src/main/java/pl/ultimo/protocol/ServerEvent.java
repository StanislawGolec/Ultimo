package pl.ultimo.protocol;

import pl.ultimo.core.model.Card;
import pl.ultimo.core.model.CardColor;

import java.util.List;

/**
 * Type-safe, sealed model representing all outgoing server events.
 */
public sealed interface ServerEvent {

    EventType type();

    record Welcome(String assignedPlayerId, String message) implements ServerEvent {
        @Override
        public EventType type() {
            return EventType.WELCOME;
        }
    }

    record RoomStatus(String roomId, int currentPlayers, int maxPlayers, List<String> playerNames) implements ServerEvent {
        @Override
        public EventType type() {
            return EventType.ROOM_STATUS;
        }
    }

    record HandUpdate(List<Card> cards) implements ServerEvent {
        @Override
        public EventType type() {
            return EventType.HAND_UPDATE;
        }
    }

    record TopCard(Card card, CardColor activeColor) implements ServerEvent {
        @Override
        public EventType type() {
            return EventType.TOP_CARD;
        }
    }

    record TurnAnnounced(String activePlayerName) implements ServerEvent {
        @Override
        public EventType type() {
            return EventType.TURN_ANNOUNCED;
        }
    }

    record OpponentUpdate(String opponentName, int cardCount) implements ServerEvent {
        @Override
        public EventType type() {
            return EventType.OPPONENT_UPDATE;
        }
    }

    record DrawResult(Card drawnCard) implements ServerEvent {
        @Override
        public EventType type() {
            return EventType.DRAW_RESULT;
        }
    }

    record ErrorEvent(String errorMessage) implements ServerEvent {
        @Override
        public EventType type() {
            return EventType.ERROR;
        }
    }

    record ChatBroadcast(String sender, String message) implements ServerEvent {
        @Override
        public EventType type() {
            return EventType.CHAT_BROADCAST;
        }
    }

    record GameOver(String winnerName) implements ServerEvent {
        @Override
        public EventType type() {
            return EventType.GAME_OVER;
        }
    }

    record Pong() implements ServerEvent {
        @Override
        public EventType type() {
            return EventType.PONG;
        }
    }
}
