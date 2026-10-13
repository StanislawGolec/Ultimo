package pl.ultimo.protocol;

import pl.ultimo.core.model.Card;
import pl.ultimo.core.model.CardColor;
import pl.ultimo.core.model.CardValue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Encodes and decodes line-based protocol messages between text wire representation
 * and strongly-typed domain commands and events.
 * Wire lines are terminated with '\n'.
 */
public final class ProtocolCodec {

    private ProtocolCodec() {
        // Utility class
    }

    /**
     * Parses a raw line received from a client socket into a strongly typed ClientCommand.
     *
     * @param rawLine line received from client (with or without trailing newline)
     * @return parsed ClientCommand
     * @throws ProtocolException if format is invalid or command is unknown
     */
    public static ClientCommand decodeCommand(String rawLine) throws ProtocolException {
        if (rawLine == null || rawLine.isBlank()) {
            throw new ProtocolException("Empty or null command line received");
        }

        String line = rawLine.trim();
        String[] parts = line.split("\\s+");
        String commandName = parts[0].toUpperCase();

        try {
            return switch (commandName) {
                case "JOIN" -> {
                    if (parts.length < 2) {
                        throw new ProtocolException("JOIN command requires a username: JOIN <username>");
                    }
                    String username = line.substring(line.indexOf(' ') + 1).trim();
                    if (username.isEmpty()) {
                        throw new ProtocolException("Username cannot be empty");
                    }
                    yield new ClientCommand.Join(username);
                }
                case "PLAY" -> {
                    if (parts.length < 2) {
                        throw new ProtocolException("PLAY command requires card: PLAY <card> [chosenColor]");
                    }
                    CardColor chosenWildColor = null;
                    int cardEndIndex = parts.length;
                    if (parts.length > 2) {
                        try {
                            CardColor candidate = parseColor(parts[parts.length - 1]);
                            if (candidate.isStandardColor()) {
                                chosenWildColor = candidate;
                                cardEndIndex = parts.length - 1;
                            }
                        } catch (Exception ignored) {
                            // Last token is not a color (part of card combo tokens)
                        }
                    }

                    List<Card> cards = new ArrayList<>();
                    for (int i = 1; i < cardEndIndex; i++) {
                        String token = parts[i];
                        if (token.contains(",")) {
                            for (String subToken : token.split(",")) {
                                if (!subToken.isBlank()) {
                                    cards.add(parseCard(subToken.trim()));
                                }
                            }
                        } else {
                            cards.add(parseCard(token));
                        }
                    }

                    if (cards.isEmpty()) {
                        throw new ProtocolException("PLAY command requires card: PLAY <card> [chosenColor]");
                    }

                    boolean hasWild = cards.stream().anyMatch(Card::isWild);
                    if (hasWild && chosenWildColor == null) {
                        throw new ProtocolException("Wild cards require a chosen color: PLAY " + parts[1] + " <RED|YELLOW|GREEN|BLUE>");
                    }

                    yield new ClientCommand.Play(cards, chosenWildColor);
                }
                case "DRAW" -> new ClientCommand.Draw();
                case "PASS" -> new ClientCommand.Pass();
                case "CHAT" -> {
                    String message = parts.length > 1 ? line.substring(line.indexOf(' ') + 1).trim() : "";
                    yield new ClientCommand.Chat(message);
                }
                case "PING" -> new ClientCommand.Ping();
                case "START" -> new ClientCommand.Start();
                default -> throw new ProtocolException("Unknown command: " + commandName);
            };
        } catch (ProtocolException pe) {
            throw pe;
        } catch (Exception e) {
            throw new ProtocolException("Failed to decode command '" + line + "': " + e.getMessage(), e);
        }
    }

    /**
     * Serializes a ClientCommand into wire-format string terminated by '\n'.
     */
    public static String encodeCommand(ClientCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        return switch (command) {
            case ClientCommand.Join join -> "JOIN " + join.username() + "\n";
            case ClientCommand.Play play -> {
                String cardsStr = play.cards().stream()
                        .map(ProtocolCodec::formatCard)
                        .collect(Collectors.joining(","));
                boolean hasWild = play.cards().stream().anyMatch(Card::isWild);
                if (hasWild && play.chosenWildColor() != null) {
                    yield "PLAY " + cardsStr + " " + play.chosenWildColor() + "\n";
                }
                yield "PLAY " + cardsStr + "\n";
            }
            case ClientCommand.Draw draw -> "DRAW\n";
            case ClientCommand.Pass pass -> "PASS\n";
            case ClientCommand.Chat chat -> "CHAT " + chat.message() + "\n";
            case ClientCommand.Ping ping -> "PING\n";
            case ClientCommand.Start start -> "START\n";
        };
    }

    /**
     * Serializes a ServerEvent into wire-format string terminated by '\n'.
     */
    public static String encodeEvent(ServerEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        return switch (event) {
            case ServerEvent.Welcome welcome ->
                    "WELCOME " + welcome.assignedPlayerId() + " " + welcome.message() + "\n";
            case ServerEvent.RoomStatus roomStatus -> {
                String playerList = roomStatus.playerNames().isEmpty() ? "-" : String.join(",", roomStatus.playerNames());
                yield "ROOM_STATUS " + roomStatus.roomId() + " " + roomStatus.currentPlayers() + " "
                        + roomStatus.maxPlayers() + " " + playerList + "\n";
            }
            case ServerEvent.HandUpdate handUpdate -> {
                if (handUpdate.cards().isEmpty()) {
                    yield "HAND_UPDATE\n";
                }
                String cardsStr = handUpdate.cards().stream()
                        .map(ProtocolCodec::formatCard)
                        .collect(Collectors.joining(" "));
                yield "HAND_UPDATE " + cardsStr + "\n";
            }
            case ServerEvent.TopCard topCard ->
                    "TOP_CARD " + formatCard(topCard.card()) + " " + topCard.activeColor() + "\n";
            case ServerEvent.TurnAnnounced turn ->
                    "TURN_ANNOUNCED " + turn.activePlayerName() + "\n";
            case ServerEvent.OpponentUpdate opp ->
                    "OPPONENT_UPDATE " + opp.opponentName() + " " + opp.cardCount() + "\n";
            case ServerEvent.DrawResult draw ->
                    "DRAW_RESULT " + formatCard(draw.drawnCard()) + "\n";
            case ServerEvent.ErrorEvent err ->
                    "ERROR " + err.errorMessage() + "\n";
            case ServerEvent.ChatBroadcast chat ->
                    "CHAT_BROADCAST " + chat.sender() + " " + chat.message() + "\n";
            case ServerEvent.GameOver gameOver ->
                    "GAME_OVER " + gameOver.winnerName() + "\n";
            case ServerEvent.Pong pong ->
                    "PONG\n";
        };
    }

    /**
     * Parses a raw line into a strongly typed ServerEvent.
     */
    public static ServerEvent decodeEvent(String rawLine) throws ProtocolException {
        if (rawLine == null || rawLine.isBlank()) {
            throw new ProtocolException("Empty or null event line received");
        }

        String line = rawLine.trim();
        String[] parts = line.split("\\s+");
        String eventName = parts[0].toUpperCase();

        try {
            return switch (eventName) {
                case "WELCOME" -> {
                    if (parts.length < 2) {
                        throw new ProtocolException("Malformed WELCOME event: " + line);
                    }
                    String id = parts[1];
                    String message = parts.length > 2 ? line.substring(line.indexOf(parts[2])).trim() : "";
                    yield new ServerEvent.Welcome(id, message);
                }
                case "ROOM_STATUS" -> {
                    if (parts.length < 4) {
                        throw new ProtocolException("Malformed ROOM_STATUS event: " + line);
                    }
                    String roomId = parts[1];
                    int current = Integer.parseInt(parts[2]);
                    int max = Integer.parseInt(parts[3]);
                    List<String> players = (parts.length > 4 && !"-".equals(parts[4]))
                            ? Arrays.asList(parts[4].split(","))
                            : List.of();
                    yield new ServerEvent.RoomStatus(roomId, current, max, players);
                }
                case "HAND_UPDATE" -> {
                    List<Card> cards = new ArrayList<>();
                    for (int i = 1; i < parts.length; i++) {
                        cards.add(parseCard(parts[i]));
                    }
                    yield new ServerEvent.HandUpdate(cards);
                }
                case "TOP_CARD" -> {
                    if (parts.length < 3) {
                        throw new ProtocolException("Malformed TOP_CARD event: " + line);
                    }
                    Card card = parseCard(parts[1]);
                    CardColor color = parseColor(parts[2]);
                    yield new ServerEvent.TopCard(card, color);
                }
                case "TURN_ANNOUNCED" -> {
                    if (parts.length < 2) {
                        throw new ProtocolException("Malformed TURN_ANNOUNCED event: " + line);
                    }
                    yield new ServerEvent.TurnAnnounced(parts[1]);
                }
                case "OPPONENT_UPDATE" -> {
                    if (parts.length < 3) {
                        throw new ProtocolException("Malformed OPPONENT_UPDATE event: " + line);
                    }
                    yield new ServerEvent.OpponentUpdate(parts[1], Integer.parseInt(parts[2]));
                }
                case "DRAW_RESULT" -> {
                    if (parts.length < 2) {
                        throw new ProtocolException("Malformed DRAW_RESULT event: " + line);
                    }
                    yield new ServerEvent.DrawResult(parseCard(parts[1]));
                }
                case "ERROR" -> {
                    String msg = parts.length > 1 ? line.substring(line.indexOf(' ') + 1).trim() : "Unknown error";
                    yield new ServerEvent.ErrorEvent(msg);
                }
                case "CHAT_BROADCAST" -> {
                    if (parts.length < 2) {
                        throw new ProtocolException("Malformed CHAT_BROADCAST event: " + line);
                    }
                    String sender = parts[1];
                    String msg = parts.length > 2 ? line.substring(line.indexOf(parts[2])).trim() : "";
                    yield new ServerEvent.ChatBroadcast(sender, msg);
                }
                case "GAME_OVER" -> {
                    if (parts.length < 2) {
                        throw new ProtocolException("Malformed GAME_OVER event: " + line);
                    }
                    yield new ServerEvent.GameOver(parts[1]);
                }
                case "PONG" -> new ServerEvent.Pong();
                default -> throw new ProtocolException("Unknown server event: " + eventName);
            };
        } catch (ProtocolException pe) {
            throw pe;
        } catch (Exception e) {
            throw new ProtocolException("Failed to decode event '" + line + "': " + e.getMessage(), e);
        }
    }

    /**
     * Formats a card instance into wire representation.
     */
    public static String formatCard(Card card) {
        Objects.requireNonNull(card, "card must not be null");
        if (card.value() == CardValue.WILD) {
            return "WILD";
        }
        if (card.value() == CardValue.WILD_DRAW_FOUR) {
            return "WILD_DRAW_FOUR";
        }
        return card.color().name() + "_" + card.value().name();
    }

    /**
     * Parses a card token from wire representation into a Card instance.
     * Supports formats like:
     * - "WILD", "WILD_DRAW_FOUR"
     * - "RED_FIVE", "BLUE_SKIP", "YELLOW_DRAW_TWO"
     * - "RED_5", "BLUE_0", "GREEN_DRAW2"
     */
    public static Card parseCard(String token) throws ProtocolException {
        if (token == null || token.isBlank()) {
            throw new ProtocolException("Empty card token");
        }

        String normalized = token.trim().toUpperCase();

        if ("WILD".equals(normalized) || "WILD_WILD".equals(normalized)) {
            return new Card(CardColor.WILD, CardValue.WILD);
        }
        if ("WILD_DRAW_FOUR".equals(normalized) || "WILD_DRAW4".equals(normalized) || "WILD_WILD_DRAW_FOUR".equals(normalized)) {
            return new Card(CardColor.WILD, CardValue.WILD_DRAW_FOUR);
        }

        int separator = normalized.indexOf('_');
        if (separator <= 0 || separator >= normalized.length() - 1) {
            throw new ProtocolException("Invalid card format (expected COLOR_VALUE, got: " + token + ")");
        }

        String colorPart = normalized.substring(0, separator);
        String valuePart = normalized.substring(separator + 1);

        CardColor color = parseColor(colorPart);
        CardValue value = parseValue(valuePart);

        return new Card(color, value);
    }

    private static CardColor parseColor(String token) throws ProtocolException {
        try {
            return CardColor.valueOf(token.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ProtocolException("Unknown card color: " + token);
        }
    }

    private static CardValue parseValue(String token) throws ProtocolException {
        String normalized = token.trim().toUpperCase();
        return switch (normalized) {
            case "0", "ZERO" -> CardValue.ZERO;
            case "1", "ONE" -> CardValue.ONE;
            case "2", "TWO" -> CardValue.TWO;
            case "3", "THREE" -> CardValue.THREE;
            case "4", "FOUR" -> CardValue.FOUR;
            case "5", "FIVE" -> CardValue.FIVE;
            case "6", "SIX" -> CardValue.SIX;
            case "7", "SEVEN" -> CardValue.SEVEN;
            case "8", "EIGHT" -> CardValue.EIGHT;
            case "9", "NINE" -> CardValue.NINE;
            case "SKIP" -> CardValue.SKIP;
            case "REVERSE" -> CardValue.REVERSE;
            case "DRAW_TWO", "DRAW2", "DRAW_2" -> CardValue.DRAW_TWO;
            case "WILD" -> CardValue.WILD;
            case "WILD_DRAW_FOUR", "WILD_DRAW4" -> CardValue.WILD_DRAW_FOUR;
            default -> throw new ProtocolException("Unknown card value: " + token);
        };
    }
}
