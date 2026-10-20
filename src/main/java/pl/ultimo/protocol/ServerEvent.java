package pl.ultimo.protocol;

import pl.ultimo.core.model.Card;
import pl.ultimo.core.model.CardColor;
import pl.ultimo.core.model.CardValue;

import java.util.List;

/**
 * Type-safe, sealed model representing all outgoing server events.
 */
public sealed interface ServerEvent {

    EventType type();

    record Welcome(String assignedPlayerId, String reconnectToken, String message) implements ServerEvent {
        public Welcome(String assignedPlayerId, String message) {
            this(assignedPlayerId, assignedPlayerId, message);
        }

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

    record ServerShutdown(int drainSeconds) implements ServerEvent {
        @Override
        public EventType type() {
            return EventType.SERVER_SHUTDOWN;
        }
    }

    record UltimoDeclared(String playerName) implements ServerEvent {
        @Override
        public EventType type() {
            return EventType.ULTIMO_DECLARED;
        }
    }

    record UltimoPenalty(String penalizedPlayer, String challenger, int penaltyCards) implements ServerEvent {
        @Override
        public EventType type() {
            return EventType.ULTIMO_PENALTY;
        }
    }

    record ChallengeResult(String challenger, String offender, boolean isGuilty, String penalizedPlayer, int penaltyCards) implements ServerEvent {
        @Override
        public EventType type() {
            return EventType.CHALLENGE_RESULT;
        }
    }

    record RoundOver(String roundWinner, int pointsEarned, java.util.Map<String, Integer> tournamentScores, boolean isTournamentWon) implements ServerEvent {
        @Override
        public EventType type() {
            return EventType.ROUND_OVER;
        }
    }

    record RoomOwnerChanged(String newOwner) implements ServerEvent {
        @Override
        public EventType type() {
            return EventType.ROOM_OWNER_CHANGED;
        }
    }

    public record RoomEntry(String roomId, String name, int currentPlayers, int maxPlayers, String status) {}

    record RoomList(List<RoomEntry> rooms) implements ServerEvent {
        public RoomList {
            rooms = rooms != null ? List.copyOf(rooms) : List.of();
        }

        @Override
        public EventType type() {
            return EventType.ROOM_LIST;
        }
    }

    public record Placement(int rank, String playerName) {}

    record PlayerFinished(String playerName, int rank) implements ServerEvent {
        @Override
        public EventType type() {
            return EventType.PLAYER_FINISHED;
        }
    }

    record MatchStandings(List<Placement> placements) implements ServerEvent {
        public MatchStandings {
            placements = placements != null ? List.copyOf(placements) : List.of();
        }

        @Override
        public EventType type() {
            return EventType.MATCH_STANDINGS;
        }
    }

    record ActionDefenseState(CardValue defenseType, int accumulatedPenalty, String targetPlayer) implements ServerEvent {
        @Override
        public EventType type() {
            return EventType.ACTION_DEFENSE_STATE;
        }
    }

    record PlayerState(String username, String status, int graceSecondsRemaining) implements ServerEvent {
        @Override
        public EventType type() {
            return EventType.PLAYER_STATE;
        }
    }

    record RoomLeft(String roomId) implements ServerEvent {
        @Override
        public EventType type() {
            return EventType.ROOM_LEFT;
        }
    }
}
