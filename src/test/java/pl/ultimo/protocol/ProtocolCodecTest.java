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

        // Single-token compound username
        ClientCommand compoundWord = ProtocolCodec.decodeCommand("JOIN Alice_The_Great");
        assertEquals("Alice_The_Great", ((ClientCommand.Join) compoundWord).username());

        // Join with room and capacity
        ClientCommand joinRoomCap = ProtocolCodec.decodeCommand("JOIN Alice room-1 16");
        assertInstanceOf(ClientCommand.Join.class, joinRoomCap);
        ClientCommand.Join jrc = (ClientCommand.Join) joinRoomCap;
        assertEquals("Alice", jrc.username());
        assertEquals("room-1", jrc.roomId());
        assertEquals(16, jrc.maxPlayers());

        // Join with capacity only (matchmaking)
        ClientCommand joinCap = ProtocolCodec.decodeCommand("JOIN Bob 8");
        assertInstanceOf(ClientCommand.Join.class, joinCap);
        ClientCommand.Join jc = (ClientCommand.Join) joinCap;
        assertEquals("Bob", jc.username());
        assertNull(jc.roomId());
        assertEquals(8, jc.maxPlayers());

        // Roundtrip serialization
        assertEquals("JOIN Alice room-1 16\n", ProtocolCodec.encodeCommand(jrc));
        assertEquals("JOIN Bob 8\n", ProtocolCodec.encodeCommand(jc));
    }

    @Test
    @DisplayName("Should throw ProtocolException when JOIN lacks username")
    void shouldThrowWhenJoinLacksUsername() {
        assertThrows(ProtocolException.class, () -> ProtocolCodec.decodeCommand("JOIN"));
        assertThrows(ProtocolException.class, () -> ProtocolCodec.decodeCommand("JOIN   "));
    }

    @Test
    @DisplayName("Should validate usernames correctly according to sanitization rules")
    void shouldValidateUsernamesCorrectly() {
        // Valid usernames
        assertTrue(ProtocolCodec.isValidUsername("Alice"));
        assertTrue(ProtocolCodec.isValidUsername("Bob123"));
        assertTrue(ProtocolCodec.isValidUsername("User_99"));
        assertTrue(ProtocolCodec.isValidUsername("Player#1"));
        assertTrue(ProtocolCodec.isValidUsername("!@#$%^&*"));
        assertTrue(ProtocolCodec.isValidUsername("A-B_C.D!E"));

        // Invalid usernames (including spaces)
        assertFalse(ProtocolCodec.isValidUsername("John Doe"));
        assertFalse(ProtocolCodec.isValidUsername(null));
        assertFalse(ProtocolCodec.isValidUsername(""));
        assertFalse(ProtocolCodec.isValidUsername("A")); // too short (< 2)
        assertFalse(ProtocolCodec.isValidUsername("ThisUsernameIsWayTooLongToPass")); // > 24
        assertFalse(ProtocolCodec.isValidUsername("User\nName")); // newline
        assertFalse(ProtocolCodec.isValidUsername("User\tName")); // tab
        assertFalse(ProtocolCodec.isValidUsername("User\rName")); // carriage return
        assertFalse(ProtocolCodec.isValidUsername("Emoji😎")); // emoji
        assertFalse(ProtocolCodec.isValidUsername(" LeadingSpace"));
        assertFalse(ProtocolCodec.isValidUsername("TrailingSpace "));
    }

    @Test
    @DisplayName("Should throw ProtocolException when JOIN command has invalid username")
    void shouldThrowWhenJoinHasInvalidUsername() {
        assertThrows(ProtocolException.class, () -> ProtocolCodec.decodeCommand("JOIN A"));
        assertThrows(ProtocolException.class, () -> ProtocolCodec.decodeCommand("JOIN User\nName"));
        assertThrows(ProtocolException.class, () -> ProtocolCodec.decodeCommand("JOIN " + "A".repeat(30)));
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
        assertEquals("ULTIMO\n", ProtocolCodec.encodeCommand(new ClientCommand.Ultimo()));
        assertEquals("CATCH_ULTIMO Bob\n", ProtocolCodec.encodeCommand(new ClientCommand.CatchUltimo("Bob")));
        assertEquals("CHALLENGE_WILD_FOUR\n", ProtocolCodec.encodeCommand(new ClientCommand.ChallengeWildFour()));
    }

    @Test
    @DisplayName("Should encode ServerEvents to wire format")
    void shouldEncodeServerEvents() {
        assertEquals("WELCOME id-1 tok-1 Hello!\n", ProtocolCodec.encodeEvent(new ServerEvent.Welcome("id-1", "tok-1", "Hello!")));
        assertEquals("ROOM_STATUS r-1 2 4 Alice,Bob\n", ProtocolCodec.encodeEvent(new ServerEvent.RoomStatus("r-1", 2, 4, List.of("Alice", "Bob"))));
        assertEquals("TOP_CARD RED_FIVE RED\n", ProtocolCodec.encodeEvent(new ServerEvent.TopCard(new Card(CardColor.RED, CardValue.FIVE), CardColor.RED)));
        assertEquals("TURN_ANNOUNCED Alice\n", ProtocolCodec.encodeEvent(new ServerEvent.TurnAnnounced("Alice")));
        assertEquals("OPPONENT_UPDATE Bob 4\n", ProtocolCodec.encodeEvent(new ServerEvent.OpponentUpdate("Bob", 4)));
        assertEquals("ERROR Bad move\n", ProtocolCodec.encodeEvent(new ServerEvent.ErrorEvent("Bad move")));
        assertEquals("PONG\n", ProtocolCodec.encodeEvent(new ServerEvent.Pong()));
        assertEquals("SERVER_SHUTDOWN 5\n", ProtocolCodec.encodeEvent(new ServerEvent.ServerShutdown(5)));
        assertEquals("ULTIMO_DECLARED Alice\n", ProtocolCodec.encodeEvent(new ServerEvent.UltimoDeclared("Alice")));
        assertEquals("ULTIMO_PENALTY Bob Alice 2\n", ProtocolCodec.encodeEvent(new ServerEvent.UltimoPenalty("Bob", "Alice", 2)));
        assertEquals("CHALLENGE_RESULT Alice Bob GUILTY Bob 4\n", ProtocolCodec.encodeEvent(new ServerEvent.ChallengeResult("Alice", "Bob", true, "Bob", 4)));
        assertEquals("ROUND_OVER Alice 85 IN_PROGRESS Alice:85,Bob:0\n", ProtocolCodec.encodeEvent(new ServerEvent.RoundOver("Alice", 85, java.util.Map.of("Alice", 85, "Bob", 0), false)));
        assertEquals("DEFENSE_STATE DRAW_TWO 4 Bob\n", ProtocolCodec.encodeEvent(new ServerEvent.ActionDefenseState(CardValue.DRAW_TWO, 4, "Bob")));
        assertEquals("DEFENSE_STATE NONE 0 Alice\n", ProtocolCodec.encodeEvent(new ServerEvent.ActionDefenseState(null, 0, "Alice")));
        assertEquals("PLAYER_STATE Alice DISCONNECTED 120\n", ProtocolCodec.encodeEvent(new ServerEvent.PlayerState("Alice", "DISCONNECTED", 120)));
        assertEquals("PLAYER_STATE Alice CONNECTED\n", ProtocolCodec.encodeEvent(new ServerEvent.PlayerState("Alice", "CONNECTED", 0)));
    }

    @Test
    @DisplayName("Should perform lossless round-trip serialization of ServerEvents")
    void shouldRoundTripServerEvents() throws ProtocolException {
        List<ServerEvent> testEvents = List.of(
                new ServerEvent.Welcome("player-1", "tok-99", "Welcome to Ultimo server"),
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
                new ServerEvent.ServerShutdown(5),
                new ServerEvent.UltimoDeclared("Alice"),
                new ServerEvent.UltimoPenalty("Bob", "Alice", 2),
                new ServerEvent.ChallengeResult("Alice", "Bob", true, "Bob", 4),
                new ServerEvent.RoundOver("Alice", 85, java.util.Map.of("Alice", 85, "Bob", 0), false),
                new ServerEvent.ActionDefenseState(CardValue.DRAW_TWO, 4, "Bob"),
                new ServerEvent.ActionDefenseState(null, 0, "Alice"),
                new ServerEvent.PlayerState("Alice", "DISCONNECTED", 120),
                new ServerEvent.PlayerState("Bob", "CONNECTED", 0)
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

    @Test
    @DisplayName("Should decode ULTIMO, CATCH_ULTIMO, and CHALLENGE_WILD_FOUR commands")
    void shouldDecodeUltimoAndChallengeCommands() throws ProtocolException {
        assertInstanceOf(ClientCommand.Ultimo.class, ProtocolCodec.decodeCommand("ULTIMO"));
        assertInstanceOf(ClientCommand.Ultimo.class, ProtocolCodec.decodeCommand("  ultimo  \r\n"));

        ClientCommand catchCmd = ProtocolCodec.decodeCommand("CATCH_ULTIMO Charlie");
        assertInstanceOf(ClientCommand.CatchUltimo.class, catchCmd);
        assertEquals("Charlie", ((ClientCommand.CatchUltimo) catchCmd).targetUsername());

        assertInstanceOf(ClientCommand.ChallengeWildFour.class, ProtocolCodec.decodeCommand("CHALLENGE_WILD_FOUR"));
        assertInstanceOf(ClientCommand.ChallengeWildFour.class, ProtocolCodec.decodeCommand("  challenge_wild_four \n"));
    }

    @Test
    @DisplayName("Should throw ProtocolException when CATCH_ULTIMO lacks target username")
    void shouldThrowWhenCatchUltimoLacksTarget() {
        assertThrows(ProtocolException.class, () -> ProtocolCodec.decodeCommand("CATCH_ULTIMO"));
        assertThrows(ProtocolException.class, () -> ProtocolCodec.decodeCommand("CATCH_ULTIMO   "));
    }

    @Test
    @DisplayName("Should decode and encode JOIN command with optional room_id")
    void shouldDecodeAndEncodeJoinWithRoomId() throws ProtocolException {
        ClientCommand cmd = ProtocolCodec.decodeCommand("JOIN Bob room_42");
        assertInstanceOf(ClientCommand.Join.class, cmd);
        ClientCommand.Join join = (ClientCommand.Join) cmd;
        assertEquals("Bob", join.username());
        assertEquals("room_42", join.roomId());

        assertEquals("JOIN Bob room_42\n", ProtocolCodec.encodeCommand(join));
        assertEquals("JOIN Alice\n", ProtocolCodec.encodeCommand(new ClientCommand.Join("Alice")));
    }

    @Test
    @DisplayName("Should parse synonyms and abbreviated card tokens symmetrically")
    void shouldParseSynonymsAndAbbreviatedCardTokens() throws ProtocolException {
        assertEquals(new Card(CardColor.WILD, CardValue.WILD), ProtocolCodec.parseCard("WILD_COLOR"));
        assertEquals(new Card(CardColor.WILD, CardValue.WILD_DRAW_FOUR), ProtocolCodec.parseCard("WILD_FOUR"));
        assertEquals(new Card(CardColor.WILD, CardValue.WILD_DRAW_FOUR), ProtocolCodec.parseCard("WILD_DRAW4"));
        assertEquals(new Card(CardColor.WILD, CardValue.WILD_DRAW_FOUR), ProtocolCodec.parseCard("WD4"));

        assertEquals(new Card(CardColor.RED, CardValue.REVERSE), ProtocolCodec.parseCard("RED_REV"));
        assertEquals(new Card(CardColor.BLUE, CardValue.DRAW_TWO), ProtocolCodec.parseCard("BLUE_D2"));
        assertEquals(new Card(CardColor.GREEN, CardValue.DRAW_TWO), ProtocolCodec.parseCard("GREEN_DRAW2"));
        assertEquals(new Card(CardColor.YELLOW, CardValue.SEVEN), ProtocolCodec.parseCard("YELLOW_7"));

        assertEquals("RED_REV", ProtocolCodec.formatCard(new Card(CardColor.RED, CardValue.REVERSE), true));
        assertEquals("BLUE_DRAW2", ProtocolCodec.formatCard(new Card(CardColor.BLUE, CardValue.DRAW_TWO), true));
        assertEquals("WILD_DRAW4", ProtocolCodec.formatCard(new Card(CardColor.WILD, CardValue.WILD_DRAW_FOUR), true));
        assertEquals("GREEN_7", ProtocolCodec.formatCard(new Card(CardColor.GREEN, CardValue.SEVEN), true));
    }

    @Test
    @DisplayName("Should tolerate optional metadata tokens in TURN_ANNOUNCED and DRAW_RESULT")
    void shouldTolerateOptionalTokensInServerEvents() throws ProtocolException {
        ServerEvent turn = ProtocolCodec.decodeEvent("TURN_ANNOUNCED Alice 25000\n");
        assertInstanceOf(ServerEvent.TurnAnnounced.class, turn);
        assertEquals("Alice", ((ServerEvent.TurnAnnounced) turn).activePlayerName());

        ServerEvent draw = ProtocolCodec.decodeEvent("DRAW_RESULT YELLOW_DRAW2 true\n");
        assertInstanceOf(ServerEvent.DrawResult.class, draw);
        assertEquals(new Card(CardColor.YELLOW, CardValue.DRAW_TWO), ((ServerEvent.DrawResult) draw).drawnCard());
    }

    @Test
    @DisplayName("Should decode and encode CREATE_ROOM for public and private rooms")
    void shouldDecodeAndEncodeCreateRoom() throws ProtocolException {
        // Public room with capacity
        ClientCommand cmd1 = ProtocolCodec.decodeCommand("CREATE_ROOM Casual PUBLIC 8");
        assertInstanceOf(ClientCommand.CreateRoom.class, cmd1);
        ClientCommand.CreateRoom cr1 = (ClientCommand.CreateRoom) cmd1;
        assertEquals("Casual", cr1.name());
        assertEquals(RoomVisibility.PUBLIC, cr1.visibility());
        assertNull(cr1.accessCode());
        assertEquals(8, cr1.maxPlayers());
        assertEquals("CREATE_ROOM Casual PUBLIC 8\n", ProtocolCodec.encodeCommand(cr1));

        // Private room with access code and capacity
        ClientCommand cmd2 = ProtocolCodec.decodeCommand("CREATE_ROOM SecretTable PRIVATE 1234 6");
        assertInstanceOf(ClientCommand.CreateRoom.class, cmd2);
        ClientCommand.CreateRoom cr2 = (ClientCommand.CreateRoom) cmd2;
        assertEquals("SecretTable", cr2.name());
        assertEquals(RoomVisibility.PRIVATE, cr2.visibility());
        assertEquals("1234", cr2.accessCode());
        assertEquals(6, cr2.maxPlayers());
        assertEquals("CREATE_ROOM SecretTable PRIVATE 1234 6\n", ProtocolCodec.encodeCommand(cr2));
    }

    @Test
    @DisplayName("Should decode and encode LIST_ROOMS, KICK, and TRANSFER_OWNERSHIP")
    void shouldDecodeAndEncodeRoomManagementCommands() throws ProtocolException {
        ClientCommand list = ProtocolCodec.decodeCommand("LIST_ROOMS");
        assertInstanceOf(ClientCommand.ListRooms.class, list);
        assertEquals("LIST_ROOMS\n", ProtocolCodec.encodeCommand(list));

        ClientCommand kick = ProtocolCodec.decodeCommand("KICK BadPlayer");
        assertInstanceOf(ClientCommand.Kick.class, kick);
        assertEquals("BadPlayer", ((ClientCommand.Kick) kick).targetPlayer());
        assertEquals("KICK BadPlayer\n", ProtocolCodec.encodeCommand(kick));

        ClientCommand to = ProtocolCodec.decodeCommand("TRANSFER_OWNERSHIP NewHost");
        assertInstanceOf(ClientCommand.TransferOwnership.class, to);
        assertEquals("NewHost", ((ClientCommand.TransferOwnership) to).targetPlayer());
        assertEquals("TRANSFER_OWNERSHIP NewHost\n", ProtocolCodec.encodeCommand(to));
    }

    @Test
    @DisplayName("Should decode and encode ROOM_OWNER_CHANGED and ROOM_LIST events")
    void shouldDecodeAndEncodeRoomEvents() throws ProtocolException {
        ServerEvent roc = ProtocolCodec.decodeEvent("ROOM_OWNER_CHANGED Alice\n");
        assertInstanceOf(ServerEvent.RoomOwnerChanged.class, roc);
        assertEquals("Alice", ((ServerEvent.RoomOwnerChanged) roc).newOwner());
        assertEquals("ROOM_OWNER_CHANGED Alice\n", ProtocolCodec.encodeEvent(roc));

        // ROOM_LIST with rooms
        ServerEvent rl = ProtocolCodec.decodeEvent("ROOM_LIST room-1:Lobby_One:2/4:WAITING room-2:HighStakes:5/16:IN_PROGRESS\n");
        assertInstanceOf(ServerEvent.RoomList.class, rl);
        ServerEvent.RoomList roomList = (ServerEvent.RoomList) rl;
        assertEquals(2, roomList.rooms().size());
        assertEquals("room-1", roomList.rooms().get(0).roomId());
        assertEquals("Lobby_One", roomList.rooms().get(0).name());
        assertEquals(2, roomList.rooms().get(0).currentPlayers());
        assertEquals(4, roomList.rooms().get(0).maxPlayers());
        assertEquals("WAITING", roomList.rooms().get(0).status());

        // Empty ROOM_LIST
        ServerEvent emptyRl = ProtocolCodec.decodeEvent("ROOM_LIST -\n");
        assertInstanceOf(ServerEvent.RoomList.class, emptyRl);
        assertTrue(((ServerEvent.RoomList) emptyRl).rooms().isEmpty());
        assertEquals("ROOM_LIST -\n", ProtocolCodec.encodeEvent(emptyRl));
    }

    @Test
    @DisplayName("Should decode and encode JOIN command with access code")
    void shouldDecodeAndEncodeJoinWithAccessCode() throws ProtocolException {
        ClientCommand cmd = ProtocolCodec.decodeCommand("JOIN Alice room-99 4321 8");
        assertInstanceOf(ClientCommand.Join.class, cmd);
        ClientCommand.Join join = (ClientCommand.Join) cmd;
        assertEquals("Alice", join.username());
        assertEquals("room-99", join.roomId());
        assertEquals("4321", join.accessCode());
        assertEquals(8, join.maxPlayers());
        assertEquals("JOIN Alice room-99 4321 8\n", ProtocolCodec.encodeCommand(join));
    }

    @Test
    @DisplayName("Should encode and decode PLAYER_FINISHED and MATCH_STANDINGS events")
    void shouldEncodeAndDecodePlayerFinishedAndMatchStandings() throws ProtocolException {
        // PLAYER_FINISHED
        ServerEvent pf = new ServerEvent.PlayerFinished("Alice", 1);
        String pfWire = ProtocolCodec.encodeEvent(pf);
        assertEquals("PLAYER_FINISHED Alice 1\n", pfWire);

        ServerEvent decodedPf = ProtocolCodec.decodeEvent(pfWire);
        assertInstanceOf(ServerEvent.PlayerFinished.class, decodedPf);
        assertEquals("Alice", ((ServerEvent.PlayerFinished) decodedPf).playerName());
        assertEquals(1, ((ServerEvent.PlayerFinished) decodedPf).rank());

        // MATCH_STANDINGS with placements
        List<ServerEvent.Placement> placements = List.of(
                new ServerEvent.Placement(1, "Alice"),
                new ServerEvent.Placement(2, "Bob"),
                new ServerEvent.Placement(3, "Charlie"),
                new ServerEvent.Placement(4, "David")
        );
        ServerEvent ms = new ServerEvent.MatchStandings(placements);
        String msWire = ProtocolCodec.encodeEvent(ms);
        assertEquals("MATCH_STANDINGS 1:Alice 2:Bob 3:Charlie 4:David\n", msWire);

        ServerEvent decodedMs = ProtocolCodec.decodeEvent(msWire);
        assertInstanceOf(ServerEvent.MatchStandings.class, decodedMs);
        ServerEvent.MatchStandings decodedStandings = (ServerEvent.MatchStandings) decodedMs;
        assertEquals(4, decodedStandings.placements().size());
        assertEquals(1, decodedStandings.placements().get(0).rank());
        assertEquals("Alice", decodedStandings.placements().get(0).playerName());
        assertEquals(4, decodedStandings.placements().get(3).rank());
        assertEquals("David", decodedStandings.placements().get(3).playerName());

        // Empty MATCH_STANDINGS
        ServerEvent emptyMs = new ServerEvent.MatchStandings(List.of());
        assertEquals("MATCH_STANDINGS -\n", ProtocolCodec.encodeEvent(emptyMs));
        ServerEvent decodedEmptyMs = ProtocolCodec.decodeEvent("MATCH_STANDINGS -\n");
        assertInstanceOf(ServerEvent.MatchStandings.class, decodedEmptyMs);
        assertTrue(((ServerEvent.MatchStandings) decodedEmptyMs).placements().isEmpty());
    }

    @Test
    @DisplayName("Should decode and encode QUIT and RECONNECT commands")
    void shouldDecodeAndEncodeQuitAndReconnectCommands() throws ProtocolException {
        ClientCommand quitCmd = ProtocolCodec.decodeCommand("QUIT\n");
        assertInstanceOf(ClientCommand.Quit.class, quitCmd);
        assertEquals(CommandType.QUIT, quitCmd.type());
        assertEquals("QUIT\n", ProtocolCodec.encodeCommand(quitCmd));

        ClientCommand recCmd = ProtocolCodec.decodeCommand("RECONNECT Alice room-42 tok-secret-99\n");
        assertInstanceOf(ClientCommand.Reconnect.class, recCmd);
        ClientCommand.Reconnect rec = (ClientCommand.Reconnect) recCmd;
        assertEquals(CommandType.RECONNECT, rec.type());
        assertEquals("Alice", rec.username());
        assertEquals("room-42", rec.roomId());
        assertEquals("tok-secret-99", rec.token());
        assertEquals("RECONNECT Alice room-42 tok-secret-99\n", ProtocolCodec.encodeCommand(rec));
    }

    @Test
    @DisplayName("Should encode and decode Protocol v2 DEFENSE_STATE, PLAYER_STATE, and WELCOME events")
    void shouldEncodeAndDecodeProtocolV2Events() throws ProtocolException {
        // DEFENSE_STATE with card value
        ServerEvent.ActionDefenseState ads1 = new ServerEvent.ActionDefenseState(CardValue.WILD_DRAW_FOUR, 8, "Charlie");
        assertEquals(EventType.ACTION_DEFENSE_STATE, ads1.type());
        String ads1Wire = ProtocolCodec.encodeEvent(ads1);
        assertEquals("DEFENSE_STATE WILD_DRAW_FOUR 8 Charlie\n", ads1Wire);
        ServerEvent decodedAds1 = ProtocolCodec.decodeEvent(ads1Wire);
        assertEquals(ads1, decodedAds1);

        // DEFENSE_STATE with NONE
        ServerEvent.ActionDefenseState adsNone = new ServerEvent.ActionDefenseState(null, 0, "Alice");
        String adsNoneWire = ProtocolCodec.encodeEvent(adsNone);
        assertEquals("DEFENSE_STATE NONE 0 Alice\n", adsNoneWire);
        assertEquals(adsNone, ProtocolCodec.decodeEvent(adsNoneWire));

        // PLAYER_STATE with grace seconds
        ServerEvent.PlayerState ps1 = new ServerEvent.PlayerState("Bob", "DISCONNECTED", 120);
        assertEquals(EventType.PLAYER_STATE, ps1.type());
        String ps1Wire = ProtocolCodec.encodeEvent(ps1);
        assertEquals("PLAYER_STATE Bob DISCONNECTED 120\n", ps1Wire);
        assertEquals(ps1, ProtocolCodec.decodeEvent(ps1Wire));

        // PLAYER_STATE without grace seconds
        ServerEvent.PlayerState ps2 = new ServerEvent.PlayerState("Bob", "RECONNECTED", 0);
        String ps2Wire = ProtocolCodec.encodeEvent(ps2);
        assertEquals("PLAYER_STATE Bob RECONNECTED\n", ps2Wire);
        assertEquals(ps2, ProtocolCodec.decodeEvent(ps2Wire));

        // WELCOME with reconnect token
        ServerEvent.Welcome welcome = new ServerEvent.Welcome("p-101", "reconn-tok-77", "Welcome back!");
        String welcomeWire = ProtocolCodec.encodeEvent(welcome);
        assertEquals("WELCOME p-101 reconn-tok-77 Welcome back!\n", welcomeWire);
        ServerEvent.Welcome decodedWelcome = (ServerEvent.Welcome) ProtocolCodec.decodeEvent(welcomeWire);
        assertEquals("p-101", decodedWelcome.assignedPlayerId());
        assertEquals("reconn-tok-77", decodedWelcome.reconnectToken());
        assertEquals("Welcome back!", decodedWelcome.message());
    }

    @Test
    @DisplayName("Should enforce bounded TCP frame reader limit of 8192 bytes and close socket")
    void shouldEnforceBoundedTcpFrameReaderLimit() throws Exception {
        try (java.net.ServerSocket serverSocket = new java.net.ServerSocket(0)) {
            int port = serverSocket.getLocalPort();
            pl.ultimo.server.net.ConnectionListener listener = new pl.ultimo.server.net.ConnectionListener() {
                @Override
                public void onCommandReceived(pl.ultimo.server.net.ClientSession session, ClientCommand command) {}
                @Override
                public void onSessionDisconnected(pl.ultimo.server.net.ClientSession session) {}
            };

            Thread serverThread = Thread.ofVirtual().start(() -> {
                try (java.net.Socket accepted = serverSocket.accept()) {
                    pl.ultimo.server.net.ClientSession session = new pl.ultimo.server.net.ClientSession("test-oom", accepted, listener, 5000);
                    session.run();
                } catch (Exception ignored) {}
            });

            try (java.net.Socket client = new java.net.Socket("127.0.0.1", port)) {
                client.setSoTimeout(3000);
                java.io.OutputStream out = client.getOutputStream();
                byte[] oversized = "A".repeat(8193).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                out.write(oversized);
                out.flush();

                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(client.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                String response = reader.readLine();
                assertEquals("ERROR FRAME_TOO_LONG: Frame length exceeds 8192 bytes", response);
                assertNull(reader.readLine());
            }
            serverThread.join(2000);
        }
    }

    @Test
    @DisplayName("Should emit proactive PING on TCP idle timeout and survive when PONG is returned")
    void shouldEmitProactivePingOnTcpIdleTimeoutAndAcceptPong() throws Exception {
        try (java.net.ServerSocket serverSocket = new java.net.ServerSocket(0)) {
            int port = serverSocket.getLocalPort();
            pl.ultimo.server.net.ConnectionListener listener = new pl.ultimo.server.net.ConnectionListener() {
                @Override
                public void onCommandReceived(pl.ultimo.server.net.ClientSession session, ClientCommand command) {
                    if (command instanceof ClientCommand.Ping) {
                        session.sendEvent(new ServerEvent.Pong());
                    }
                }
                @Override
                public void onSessionDisconnected(pl.ultimo.server.net.ClientSession session) {}
            };

            Thread serverThread = Thread.ofVirtual().start(() -> {
                try (java.net.Socket accepted = serverSocket.accept()) {
                    // 120ms idle timeout -> 60ms SO_TIMEOUT check interval
                    pl.ultimo.server.net.ClientSession session = new pl.ultimo.server.net.ClientSession("test-ping", accepted, listener, 120);
                    session.run();
                } catch (Exception ignored) {}
            });

            try (java.net.Socket client = new java.net.Socket("127.0.0.1", port)) {
                client.setSoTimeout(3000);
                java.io.OutputStream out = client.getOutputStream();
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(client.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));

                // Send initial command to enter handleTcpConnection loop
                out.write("PING\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.flush();
                assertEquals("PONG", reader.readLine());

                // Wait for server idle timeout probe -> must be PING, not PONG
                String probe = reader.readLine();
                assertEquals("PING", probe);

                // Reply with PONG and then QUIT cleanly
                out.write("PONG\nQUIT\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.flush();

                String quitAck = reader.readLine();
                assertNotNull(quitAck);
                assertTrue(quitAck.contains("QUIT_ACK"));
            }
            serverThread.join(2000);
        }
    }

    @Test
    @DisplayName("Should decode and encode ADD_BOT, LEAVE_ROOM commands and ROOM_LEFT event")
    void shouldDecodeAndEncodeAddBotLeaveRoomAndRoomLeft() throws ProtocolException {
        // ADD_BOT variations
        ClientCommand cmd1 = ProtocolCodec.decodeCommand("ADD_BOT");
        assertInstanceOf(ClientCommand.AddBot.class, cmd1);
        ClientCommand.AddBot bot1 = (ClientCommand.AddBot) cmd1;
        assertEquals("NORMAL", bot1.difficulty());
        assertNull(bot1.name());
        assertEquals("ADD_BOT\n", ProtocolCodec.encodeCommand(bot1));

        ClientCommand cmd2 = ProtocolCodec.decodeCommand("ADDBOT HARD");
        assertInstanceOf(ClientCommand.AddBot.class, cmd2);
        ClientCommand.AddBot bot2 = (ClientCommand.AddBot) cmd2;
        assertEquals("HARD", bot2.difficulty());
        assertEquals("ADD_BOT HARD\n", ProtocolCodec.encodeCommand(bot2));

        ClientCommand cmd3 = ProtocolCodec.decodeCommand("BOT EASY BotBob");
        assertInstanceOf(ClientCommand.AddBot.class, cmd3);
        ClientCommand.AddBot bot3 = (ClientCommand.AddBot) cmd3;
        assertEquals("EASY", bot3.difficulty());
        assertEquals("BotBob", bot3.name());
        assertEquals("ADD_BOT EASY BotBob\n", ProtocolCodec.encodeCommand(bot3));

        // LEAVE_ROOM variations
        ClientCommand leave1 = ProtocolCodec.decodeCommand("LEAVE_ROOM");
        assertInstanceOf(ClientCommand.LeaveRoom.class, leave1);
        assertEquals("LEAVE_ROOM\n", ProtocolCodec.encodeCommand(leave1));

        ClientCommand leave2 = ProtocolCodec.decodeCommand("LEAVE");
        assertInstanceOf(ClientCommand.LeaveRoom.class, leave2);

        ClientCommand leave3 = ProtocolCodec.decodeCommand("LEAVEROOM");
        assertInstanceOf(ClientCommand.LeaveRoom.class, leave3);

        // ROOM_LEFT event
        ServerEvent event = ProtocolCodec.decodeEvent("ROOM_LEFT room-42");
        assertInstanceOf(ServerEvent.RoomLeft.class, event);
        ServerEvent.RoomLeft rl = (ServerEvent.RoomLeft) event;
        assertEquals("room-42", rl.roomId());
        assertEquals("ROOM_LEFT room-42\n", ProtocolCodec.encodeEvent(rl));
    }
}

