package pl.ultimo.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.ultimo.core.model.Card;
import pl.ultimo.core.model.CardColor;
import pl.ultimo.core.model.CardValue;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ProtocolCodec Unit Tests")
class ProtocolCodecTest {

    @Test
    @DisplayName("Should decode valid JOIN command")
    void shouldDecodeJoinCommand() throws ProtocolException {
        ClientCommand command = ProtocolCodec.decodeCommand("JOIN Alice");
        assertInstanceOf(ClientCommand.Join.class, command);
        assertEquals("Alice", ((ClientCommand.Join) command).username());

        // Multi-word username
        ClientCommand multiWord = ProtocolCodec.decodeCommand("JOIN Alice The Great");
        assertEquals("Alice The Great", ((ClientCommand.Join) multiWord).username());
    }

    @Test
    @DisplayName("Should throw ProtocolException when JOIN lacks username")
    void shouldThrowWhenJoinLacksUsername() {
        assertThrows(ProtocolException.class, () -> ProtocolCodec.decodeCommand("JOIN"));
        assertThrows(ProtocolException.class, () -> ProtocolCodec.decodeCommand("JOIN   "));
    }

    @Test
    @DisplayName("Should decode PLAY command with standard card")
    void shouldDecodePlayStandardCard() throws ProtocolException {
        // Name-based
        ClientCommand cmdWord = ProtocolCodec.decodeCommand("PLAY RED_FIVE");
        assertInstanceOf(ClientCommand.Play.class, cmdWord);
        ClientCommand.Play playWord = (ClientCommand.Play) cmdWord;
        assertEquals(new Card(CardColor.RED, CardValue.FIVE), playWord.card());
        assertNull(playWord.chosenWildColor());

        // Digit-based
        ClientCommand cmdDigit = ProtocolCodec.decodeCommand("PLAY BLUE_5");
        assertInstanceOf(ClientCommand.Play.class, cmdDigit);
        ClientCommand.Play playDigit = (ClientCommand.Play) cmdDigit;
        assertEquals(new Card(CardColor.BLUE, CardValue.FIVE), playDigit.card());

        // Action card
        ClientCommand cmdSkip = ProtocolCodec.decodeCommand("PLAY GREEN_SKIP");
        ClientCommand.Play playSkip = (ClientCommand.Play) cmdSkip;
        assertEquals(new Card(CardColor.GREEN, CardValue.SKIP), playSkip.card());

        // Draw two with alternate notation
        ClientCommand cmdDraw2 = ProtocolCodec.decodeCommand("PLAY YELLOW_DRAW2");
        ClientCommand.Play playDraw2 = (ClientCommand.Play) cmdDraw2;
        assertEquals(new Card(CardColor.YELLOW, CardValue.DRAW_TWO), playDraw2.card());
    }

    @Test
    @DisplayName("Should decode PLAY command with Wild card and declared color")
    void shouldDecodePlayWildCard() throws ProtocolException {
        ClientCommand cmdWild = ProtocolCodec.decodeCommand("PLAY WILD BLUE");
        assertInstanceOf(ClientCommand.Play.class, cmdWild);
        ClientCommand.Play playWild = (ClientCommand.Play) cmdWild;
        assertEquals(new Card(CardColor.WILD, CardValue.WILD), playWild.card());
        assertEquals(CardColor.BLUE, playWild.chosenWildColor());

        ClientCommand cmdWildDraw4 = ProtocolCodec.decodeCommand("PLAY WILD_DRAW4 RED");
        ClientCommand.Play playWildDraw4 = (ClientCommand.Play) cmdWildDraw4;
        assertEquals(new Card(CardColor.WILD, CardValue.WILD_DRAW_FOUR), playWildDraw4.card());
        assertEquals(CardColor.RED, playWildDraw4.chosenWildColor());
    }

    @Test
    @DisplayName("Should throw ProtocolException when Wild card played without declaring color")
    void shouldThrowWhenWildPlayedWithoutColor() {
        assertThrows(ProtocolException.class, () -> ProtocolCodec.decodeCommand("PLAY WILD"));
        assertThrows(ProtocolException.class, () -> ProtocolCodec.decodeCommand("PLAY WILD_DRAW_FOUR"));
    }

    @Test
    @DisplayName("Should decode DRAW, PASS, PING, and START commands")
    void shouldDecodeSimpleCommands() throws ProtocolException {
        assertInstanceOf(ClientCommand.Draw.class, ProtocolCodec.decodeCommand("DRAW"));
        assertInstanceOf(ClientCommand.Draw.class, ProtocolCodec.decodeCommand("  draw  \r\n"));

        assertInstanceOf(ClientCommand.Pass.class, ProtocolCodec.decodeCommand("PASS"));
        assertInstanceOf(ClientCommand.Ping.class, ProtocolCodec.decodeCommand("PING"));
        assertInstanceOf(ClientCommand.Start.class, ProtocolCodec.decodeCommand("START"));
        assertInstanceOf(ClientCommand.Start.class, ProtocolCodec.decodeCommand("  start  \n"));
    }

    @Test
    @DisplayName("Should decode CHAT command with full message payload")
    void shouldDecodeChatCommand() throws ProtocolException {
        ClientCommand cmd = ProtocolCodec.decodeCommand("CHAT Hello all, good luck!");
        assertInstanceOf(ClientCommand.Chat.class, cmd);
        assertEquals("Hello all, good luck!", ((ClientCommand.Chat) cmd).message());
    }

    @Test
    @DisplayName("Should throw ProtocolException for empty line or unknown command")
    void shouldThrowOnMalformedInput() {
        assertThrows(ProtocolException.class, () -> ProtocolCodec.decodeCommand(""));
        assertThrows(ProtocolException.class, () -> ProtocolCodec.decodeCommand("   \n"));
        assertThrows(ProtocolException.class, () -> ProtocolCodec.decodeCommand(null));
        assertThrows(ProtocolException.class, () -> ProtocolCodec.decodeCommand("DANCE"));
    }

    @Test
    @DisplayName("Should encode ClientCommands to wire format")
    void shouldEncodeClientCommands() {
        assertEquals("JOIN Bob\n", ProtocolCodec.encodeCommand(new ClientCommand.Join("Bob")));
        assertEquals("PLAY RED_SEVEN\n", ProtocolCodec.encodeCommand(new ClientCommand.Play(new Card(CardColor.RED, CardValue.SEVEN), null)));
        assertEquals("PLAY WILD YELLOW\n", ProtocolCodec.encodeCommand(new ClientCommand.Play(new Card(CardColor.WILD, CardValue.WILD), CardColor.YELLOW)));
        assertEquals("DRAW\n", ProtocolCodec.encodeCommand(new ClientCommand.Draw()));
        assertEquals("PASS\n", ProtocolCodec.encodeCommand(new ClientCommand.Pass()));
        assertEquals("PING\n", ProtocolCodec.encodeCommand(new ClientCommand.Ping()));
        assertEquals("START\n", ProtocolCodec.encodeCommand(new ClientCommand.Start()));
        assertEquals("CHAT Hi!\n", ProtocolCodec.encodeCommand(new ClientCommand.Chat("Hi!")));
    }

    @Test
    @DisplayName("Should encode ServerEvents to wire format")
    void shouldEncodeServerEvents() {
        assertEquals("WELCOME id-1 Hello!\n", ProtocolCodec.encodeEvent(new ServerEvent.Welcome("id-1", "Hello!")));
        assertEquals("ROOM_STATUS r-1 2 4 Alice,Bob\n", ProtocolCodec.encodeEvent(new ServerEvent.RoomStatus("r-1", 2, 4, List.of("Alice", "Bob"))));
        assertEquals("TOP_CARD RED_FIVE RED\n", ProtocolCodec.encodeEvent(new ServerEvent.TopCard(new Card(CardColor.RED, CardValue.FIVE), CardColor.RED)));
        assertEquals("TURN_ANNOUNCED Alice\n", ProtocolCodec.encodeEvent(new ServerEvent.TurnAnnounced("Alice")));
        assertEquals("OPPONENT_UPDATE Bob 4\n", ProtocolCodec.encodeEvent(new ServerEvent.OpponentUpdate("Bob", 4)));
        assertEquals("ERROR Bad move\n", ProtocolCodec.encodeEvent(new ServerEvent.ErrorEvent("Bad move")));
        assertEquals("PONG\n", ProtocolCodec.encodeEvent(new ServerEvent.Pong()));
        assertEquals("SERVER_SHUTDOWN 5\n", ProtocolCodec.encodeEvent(new ServerEvent.ServerShutdown(5)));
    }

    @Test
    @DisplayName("Should perform lossless round-trip serialization of ServerEvents")
    void shouldRoundTripServerEvents() throws ProtocolException {
        List<ServerEvent> testEvents = List.of(
                new ServerEvent.Welcome("player-1", "Welcome to Ultimo server"),
                new ServerEvent.RoomStatus("room-42", 3, 4, List.of("Alice", "Bob", "Charlie")),
                new ServerEvent.HandUpdate(List.of(
                        new Card(CardColor.RED, CardValue.ZERO),
                        new Card(CardColor.BLUE, CardValue.SKIP),
                        new Card(CardColor.WILD, CardValue.WILD)
                )),
                new ServerEvent.TopCard(new Card(CardColor.GREEN, CardValue.DRAW_TWO), CardColor.GREEN),
                new ServerEvent.TurnAnnounced("Charlie"),
                new ServerEvent.OpponentUpdate("Alice", 6),
                new ServerEvent.DrawResult(new Card(CardColor.YELLOW, CardValue.NINE)),
                new ServerEvent.ErrorEvent("Not your turn to play!"),
                new ServerEvent.ChatBroadcast("Alice", "Well played Bob"),
                new ServerEvent.GameOver("Bob"),
                new ServerEvent.Pong(),
                new ServerEvent.ServerShutdown(5)
        );

        for (ServerEvent event : testEvents) {
            String wire = ProtocolCodec.encodeEvent(event);
            ServerEvent decoded = ProtocolCodec.decodeEvent(wire);
            assertEquals(event, decoded, "Failed round-trip for event: " + event.type());
        }
    }

    @Test
    @DisplayName("Card parsing should validate token format")
    void cardParsingValidatesTokenFormat() {
        assertThrows(ProtocolException.class, () -> ProtocolCodec.parseCard(""));
        assertThrows(ProtocolException.class, () -> ProtocolCodec.parseCard("INVALID"));
        assertThrows(ProtocolException.class, () -> ProtocolCodec.parseCard("PURPLE_FIVE"));
        assertThrows(ProtocolException.class, () -> ProtocolCodec.parseCard("RED_ELEVEN"));
    }

    @Test
    @DisplayName("Should decode multi-card PLAY command with comma-separated cards")
    void shouldDecodeMultiCardCommaSeparated() throws ProtocolException {
        ClientCommand cmd = ProtocolCodec.decodeCommand("PLAY RED_FIVE,BLUE_FIVE");
        assertInstanceOf(ClientCommand.Play.class, cmd);
        ClientCommand.Play play = (ClientCommand.Play) cmd;
        assertEquals(2, play.cards().size());
        assertEquals(new Card(CardColor.RED, CardValue.FIVE), play.cards().get(0));
        assertEquals(new Card(CardColor.BLUE, CardValue.FIVE), play.cards().get(1));
        assertNull(play.chosenWildColor());
    }

    @Test
    @DisplayName("Should decode multi-card PLAY command with space-separated cards")
    void shouldDecodeMultiCardSpaceSeparated() throws ProtocolException {
        ClientCommand cmd = ProtocolCodec.decodeCommand("PLAY RED_FIVE BLUE_FIVE");
        assertInstanceOf(ClientCommand.Play.class, cmd);
        ClientCommand.Play play = (ClientCommand.Play) cmd;
        assertEquals(2, play.cards().size());
        assertEquals(new Card(CardColor.RED, CardValue.FIVE), play.cards().get(0));
        assertEquals(new Card(CardColor.BLUE, CardValue.FIVE), play.cards().get(1));
        assertNull(play.chosenWildColor());
    }

    @Test
    @DisplayName("Should decode multi-card PLAY command with Wild cards and declared color")
    void shouldDecodeMultiCardWithWildCards() throws ProtocolException {
        ClientCommand cmd = ProtocolCodec.decodeCommand("PLAY WILD,WILD GREEN");
        assertInstanceOf(ClientCommand.Play.class, cmd);
        ClientCommand.Play play = (ClientCommand.Play) cmd;
        assertEquals(2, play.cards().size());
        assertEquals(new Card(CardColor.WILD, CardValue.WILD), play.cards().get(0));
        assertEquals(new Card(CardColor.WILD, CardValue.WILD), play.cards().get(1));
        assertEquals(CardColor.GREEN, play.chosenWildColor());
    }

    @Test
    @DisplayName("Should encode multi-card PLAY command to wire format")
    void shouldEncodeMultiCardPlayCommand() {
        ClientCommand.Play play = new ClientCommand.Play(
                List.of(new Card(CardColor.RED, CardValue.FIVE), new Card(CardColor.BLUE, CardValue.FIVE)),
                null
        );
        assertEquals("PLAY RED_FIVE,BLUE_FIVE\n", ProtocolCodec.encodeCommand(play));
    }
}
