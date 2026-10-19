package pl.ultimo.server.room;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.ultimo.core.engine.UltimoEngine;
import pl.ultimo.core.model.Card;
import pl.ultimo.core.model.CardColor;
import pl.ultimo.core.model.CardValue;
import pl.ultimo.core.model.Player;
import pl.ultimo.protocol.ClientCommand;
import pl.ultimo.protocol.ProtocolCodec;
import pl.ultimo.protocol.RoomVisibility;
import pl.ultimo.protocol.ServerEvent;
import pl.ultimo.server.net.ClientSession;
import pl.ultimo.server.net.ConnectionListener;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("GameRoom Unit and Concurrency Tests")
class GameRoomTest {

    private ExecutorService executor;
    private final List<Socket> socketsToClose = new ArrayList<>();
    private final List<ServerSocket> serverSocketsToClose = new ArrayList<>();

    private record TestClient(ClientSession session, Socket clientSideSocket, BufferedReader reader, List<ServerEvent> receivedEvents) {
        public List<ServerEvent> drainEvents() {
            try {
                while (reader.ready()) {
                    String line = reader.readLine();
                    if (line != null && !line.isBlank()) {
                        receivedEvents.add(ProtocolCodec.decodeEvent(line));
                    }
                }
            } catch (Exception e) {
                // Ignore for drain
            }
            return receivedEvents;
        }
    }

    @BeforeEach
    void setUp() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        for (Socket s : socketsToClose) {
            try {
                s.close();
            } catch (Exception ignored) {}
        }
        for (ServerSocket ss : serverSocketsToClose) {
            try {
                ss.close();
            } catch (Exception ignored) {}
        }
    }

    private TestClient createTestClient(String sessionId, String username) throws Exception {
        ServerSocket ss = new ServerSocket(0);
        serverSocketsToClose.add(ss);

        Socket clientSocket = new Socket("localhost", ss.getLocalPort());
        Socket serverSocket = ss.accept();
        socketsToClose.add(clientSocket);
        socketsToClose.add(serverSocket);

        List<ServerEvent> received = Collections.synchronizedList(new ArrayList<>());
        ConnectionListener dummyListener = new ConnectionListener() {
            @Override
            public void onCommandReceived(ClientSession s, ClientCommand c) {}
            @Override
            public void onSessionDisconnected(ClientSession s) {}
        };

        ClientSession session = new ClientSession(sessionId, serverSocket, dummyListener);
        session.setUsername(username);
        BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));

        return new TestClient(session, clientSocket, reader, received);
    }

    @Test
    @DisplayName("Players can join a room and receive ROOM_STATUS updates")
    void playersCanJoinRoom() throws Exception {
        GameRoom room = new GameRoom("room-1", 2);
        TestClient client1 = createTestClient("s-1", "Alice");
        TestClient client2 = createTestClient("s-2", "Bob");

        assertTrue(room.addPlayer(client1.session, "Alice"));
        assertEquals(1, room.getPlayerCount());
        assertEquals(RoomState.WAITING_FOR_PLAYERS, room.getState());

        // Second player joins room; capacity of 2 reached -> auto-starts!
        assertTrue(room.addPlayer(client2.session, "Bob"));
        assertEquals(2, room.getPlayerCount());
        assertEquals(RoomState.IN_PROGRESS, room.getState());
        assertTrue(room.isFull());
    }

    @Test
    @DisplayName("Room enforces Fog of War: players receive only their own hand")
    void roomEnforcesFogOfWar() throws Exception {
        GameRoom room = new GameRoom("room-fow", 2);
        TestClient alice = createTestClient("s-alice", "Alice");
        TestClient bob = createTestClient("s-bob", "Bob");

        room.addPlayer(alice.session, "Alice");
        room.addPlayer(bob.session, "Bob"); // Auto starts

        Thread.sleep(100);
        alice.drainEvents();
        bob.drainEvents();

        // Alice receives HAND_UPDATE with 7 cards
        List<ServerEvent.HandUpdate> aliceHands = alice.receivedEvents.stream()
                .filter(e -> e instanceof ServerEvent.HandUpdate)
                .map(e -> (ServerEvent.HandUpdate) e)
                .toList();
        assertFalse(aliceHands.isEmpty());
        assertEquals(7, aliceHands.getFirst().cards().size());

        // Bob receives HAND_UPDATE with 7 cards
        List<ServerEvent.HandUpdate> bobHands = bob.receivedEvents.stream()
                .filter(e -> e instanceof ServerEvent.HandUpdate)
                .map(e -> (ServerEvent.HandUpdate) e)
                .toList();
        assertFalse(bobHands.isEmpty());
        assertEquals(7, bobHands.getFirst().cards().size());

        // Verify Bob's hand is distinct from Alice's hand
        assertNotEquals(aliceHands.getFirst().cards(), bobHands.getFirst().cards());

        // Both received OPPONENT_UPDATE
        boolean aliceGotBobCount = alice.receivedEvents.stream()
                .anyMatch(e -> e instanceof ServerEvent.OpponentUpdate opp && opp.opponentName().equals("Bob") && opp.cardCount() == 7);
        assertTrue(aliceGotBobCount);
    }

    @Test
    @DisplayName("Chat messages are broadcast to all players in the room")
    void chatMessagesAreBroadcast() throws Exception {
        GameRoom room = new GameRoom("room-chat", 2);
        executor.submit(room);

        TestClient alice = createTestClient("s-1", "Alice");
        TestClient bob = createTestClient("s-2", "Bob");

        room.addPlayer(alice.session, "Alice");
        room.addPlayer(bob.session, "Bob");

        // Alice sends chat message
        room.enqueueCommand(alice.session, new ClientCommand.Chat("Hello Bob!"));

        Thread.sleep(200);
        alice.drainEvents();
        bob.drainEvents();

        boolean bobReceivedChat = bob.receivedEvents.stream()
                .anyMatch(e -> e instanceof ServerEvent.ChatBroadcast chat && chat.sender().equals("Alice") && chat.message().equals("Hello Bob!"));
        assertTrue(bobReceivedChat);
    }

    @Test
    @DisplayName("If all opponents leave in an active match, remaining player wins by forfeit")
    void remainingPlayerWinsByForfeit() throws Exception {
        GameRoom room = new GameRoom("room-forfeit", 2);
        room.setReconnectGracePeriodMs(50);
        room.setWatchdogCheckIntervalMs(20);
        executor.submit(room);

        TestClient alice = createTestClient("s-1", "Alice");
        TestClient bob = createTestClient("s-2", "Bob");

        room.addPlayer(alice.session, "Alice");
        room.addPlayer(bob.session, "Bob"); // Starts match
        assertEquals(RoomState.IN_PROGRESS, room.getState());

        // Bob disconnects
        room.handleDisconnect(bob.session);

        // Immediately after disconnect, Bob is in grace period
        assertTrue(room.isDisconnected("Bob"));
        assertEquals(RoomState.IN_PROGRESS, room.getState());

        Thread.sleep(160);
        alice.drainEvents();

        assertEquals(RoomState.FINISHED, room.getState());
        boolean aliceReceivedWin = alice.receivedEvents.stream()
                .anyMatch(e -> e instanceof ServerEvent.GameOver go && go.winnerName().equals("Alice"));
        assertTrue(aliceReceivedWin);
    }

    @Test
    @DisplayName("START command starts match manually when at least two players are in the room")
    void shouldStartMatchManuallyWithStartCommand() throws Exception {
        GameRoom room = new GameRoom("room-manual-start", 4);
        executor.submit(room);

        TestClient alice = createTestClient("s-1", "Alice");
        TestClient bob = createTestClient("s-2", "Bob");

        room.addPlayer(alice.session, "Alice");
        room.addPlayer(bob.session, "Bob");

        assertEquals(2, room.getPlayerCount());
        assertEquals(RoomState.WAITING_FOR_PLAYERS, room.getState());

        room.enqueueCommand(alice.session, new ClientCommand.Start());

        Thread.sleep(200);
        alice.drainEvents();
        bob.drainEvents();

        assertEquals(RoomState.IN_PROGRESS, room.getState());
        assertTrue(alice.receivedEvents.stream().anyMatch(e -> e instanceof ServerEvent.HandUpdate));
        assertTrue(bob.receivedEvents.stream().anyMatch(e -> e instanceof ServerEvent.HandUpdate));
    }

    @Test
    @DisplayName("START command is rejected with error when fewer than two players in room")
    void shouldRejectStartCommandWithSinglePlayer() throws Exception {
        GameRoom room = new GameRoom("room-single", 4);
        executor.submit(room);

        TestClient alice = createTestClient("s-1", "Alice");
        room.addPlayer(alice.session, "Alice");

        assertEquals(1, room.getPlayerCount());
        assertEquals(RoomState.WAITING_FOR_PLAYERS, room.getState());

        room.enqueueCommand(alice.session, new ClientCommand.Start());

        Thread.sleep(200);
        alice.drainEvents();

        assertEquals(RoomState.WAITING_FOR_PLAYERS, room.getState());
        boolean gotError = alice.receivedEvents.stream()
                .anyMatch(e -> e instanceof ServerEvent.ErrorEvent err && err.errorMessage().contains("Need at least 2 players"));
        assertTrue(gotError);
    }

    @Test
    @DisplayName("Player can draw a card, and if they have legal moves, manually pass their turn")
    void shouldAllowPlayerToDrawAndManuallyPassTurn() throws Exception {
        GameRoom room = new GameRoom("room-draw-pass", 2);
        executor.submit(room);

        TestClient alice = createTestClient("s-1", "Alice");
        TestClient bob = createTestClient("s-2", "Bob");

        room.addPlayer(alice.session, "Alice");
        room.addPlayer(bob.session, "Bob"); // Starts match, Alice's turn

        Thread.sleep(150);
        alice.drainEvents();
        bob.drainEvents();

        // Alice sends DRAW
        room.enqueueCommand(alice.session, new ClientCommand.Draw());
        Thread.sleep(150);
        alice.drainEvents();
        bob.drainEvents();

        // Verify Alice received DrawResult
        assertTrue(alice.receivedEvents.stream().anyMatch(e -> e instanceof ServerEvent.DrawResult));

        // If Alice still has the turn (i.e. has legal moves), test manual pass
        boolean aliceStillActive = alice.receivedEvents.stream()
                .filter(e -> e instanceof ServerEvent.TurnAnnounced)
                .map(e -> ((ServerEvent.TurnAnnounced) e).activePlayerName())
                .reduce((first, second) -> second)
                .map(name -> name.equals("Alice"))
                .orElse(false);

        if (aliceStillActive) {
            room.enqueueCommand(alice.session, new ClientCommand.Pass());
            Thread.sleep(150);
            alice.drainEvents();
            bob.drainEvents();

            boolean turnMovedToBob = alice.receivedEvents.stream()
                    .filter(e -> e instanceof ServerEvent.TurnAnnounced)
                    .map(e -> ((ServerEvent.TurnAnnounced) e).activePlayerName())
                    .reduce((first, second) -> second)
                    .map(name -> name.equals("Bob"))
                    .orElse(false);
            assertTrue(turnMovedToBob);
        }
    }

    @Test
    @DisplayName("Auto-pass is triggered when player draws and has zero legal moves in hand")
    void shouldAutoPassWhenPlayerDrawsWithNoPlayableMoves() throws Exception {
        GameRoom room = new GameRoom("room-autopass", 2);
        executor.submit(room);

        TestClient alice = createTestClient("s-1", "Alice");
        TestClient bob = createTestClient("s-2", "Bob");

        room.addPlayer(alice.session, "Alice");
        room.addPlayer(bob.session, "Bob"); // Starts match, Alice's turn

        Thread.sleep(150);
        alice.drainEvents();
        bob.drainEvents();

        UltimoEngine engine = room.getEngine();
        assertNotNull(engine);

        // Make discard top card RED FIVE
        engine.getDiscardPile().playCard(new Card(CardColor.RED, CardValue.FIVE), CardColor.RED);

        // Clear Alice's hand and replace with single unplayable card: BLUE ZERO
        Player current = engine.getTurnManager().getCurrentPlayer();
        assertEquals("Alice", current.getUsername());
        current.getHand().clear();
        current.getHand().addCard(new Card(CardColor.BLUE, CardValue.ZERO));

        // Drain existing deck and set only unplayable card on RED FIVE (GREEN SEVEN)
        while (!engine.getDeck().isEmpty()) {
            engine.getDeck().draw();
        }
        engine.getDeck().replenishFromDiscard(List.of(new Card(CardColor.GREEN, CardValue.SEVEN)));

        // Alice draws
        room.enqueueCommand(alice.session, new ClientCommand.Draw());
        Thread.sleep(200);
        alice.drainEvents();
        bob.drainEvents();

        // Alice received DRAW_RESULT
        assertTrue(alice.receivedEvents.stream().anyMatch(e -> e instanceof ServerEvent.DrawResult));

        // Auto-pass broadcast was emitted to chat
        boolean gotAutoPassChat = alice.receivedEvents.stream()
                .anyMatch(e -> e instanceof ServerEvent.ChatBroadcast chat && chat.message().contains("no playable moves. Turn passed."));
        assertTrue(gotAutoPassChat);

        // Turn automatically advanced to Bob
        assertEquals("Bob", engine.getTurnManager().getCurrentPlayer().getUsername());
    }

    @Test
    @DisplayName("GameRoom handles ULTIMO declaration and CATCH_ULTIMO penalties over network room")
    void shouldHandleUltimoAndCatchInGameRoom() throws Exception {
        GameRoom room = new GameRoom("room-ultimo", 2);
        executor.submit(room);

        TestClient alice = createTestClient("s-1", "Alice");
        TestClient bob = createTestClient("s-2", "Bob");

        room.addPlayer(alice.session, "Alice");
        room.addPlayer(bob.session, "Bob"); // Starts match, Alice's turn

        Thread.sleep(150);
        alice.drainEvents();
        bob.drainEvents();

        UltimoEngine engine = room.getEngine();
        assertNotNull(engine);

        // Set Alice's hand to 2 cards
        Player pAlice = engine.getTurnManager().getCurrentPlayer();
        assertEquals("Alice", pAlice.getUsername());
        pAlice.getHand().clear();
        Card playCard = new Card(engine.getDiscardPile().getActiveColor(), CardValue.FOUR);
        Card keepCard = new Card(CardColor.YELLOW, CardValue.SEVEN);
        pAlice.getHand().addCard(playCard);
        pAlice.getHand().addCard(keepCard);

        // Alice declares ULTIMO
        room.enqueueCommand(alice.session, new ClientCommand.Ultimo());
        Thread.sleep(150);
        alice.drainEvents();
        bob.drainEvents();

        boolean gotUltimoEvent = bob.receivedEvents.stream()
                .anyMatch(e -> e instanceof ServerEvent.UltimoDeclared decl && decl.playerName().equals("Alice"));
        assertTrue(gotUltimoEvent, "Bob should receive ULTIMO_DECLARED event for Alice");

        // Alice plays card leaving 1 card
        room.enqueueCommand(alice.session, new ClientCommand.Play(playCard, null));
        Thread.sleep(150);
        alice.drainEvents();
        bob.drainEvents();

        assertEquals(1, pAlice.getHand().size());

        // Bob tries to catch Alice who declared ULTIMO -> should fail with error
        room.enqueueCommand(bob.session, new ClientCommand.CatchUltimo("Alice"));
        Thread.sleep(150);
        bob.drainEvents();

        boolean gotCatchError = bob.receivedEvents.stream()
                .anyMatch(e -> e instanceof ServerEvent.ErrorEvent err && err.errorMessage().contains("already declared ULTIMO"));
        assertTrue(gotCatchError, "Bob should receive error that Alice already declared ULTIMO");
    }

    @Test
    @DisplayName("GameRoom handles CHALLENGE_WILD_FOUR over network room")
    void shouldHandleWildFourChallengeInGameRoom() throws Exception {
        GameRoom room = new GameRoom("room-wild-challenge", 2);
        executor.submit(room);

        TestClient alice = createTestClient("s-1", "Alice");
        TestClient bob = createTestClient("s-2", "Bob");

        room.addPlayer(alice.session, "Alice");
        room.addPlayer(bob.session, "Bob");

        Thread.sleep(150);
        alice.drainEvents();
        bob.drainEvents();

        UltimoEngine engine = room.getEngine();
        assertNotNull(engine);

        // Alice plays Wild Draw Four illegally (holding matching card)
        Player pAlice = engine.getTurnManager().getCurrentPlayer();
        pAlice.getHand().clear();
        Card matching = new Card(engine.getDiscardPile().getActiveColor(), CardValue.ONE);
        Card wildFour = new Card(CardColor.WILD, CardValue.WILD_DRAW_FOUR);
        Card other = new Card(CardColor.BLUE, CardValue.NINE);
        pAlice.getHand().addCard(matching);
        pAlice.getHand().addCard(wildFour);
        pAlice.getHand().addCard(other);

        room.enqueueCommand(alice.session, new ClientCommand.Play(wildFour, CardColor.RED));
        Thread.sleep(150);
        alice.drainEvents();
        bob.drainEvents();

        // Bob challenges Alice
        room.enqueueCommand(bob.session, new ClientCommand.ChallengeWildFour());
        Thread.sleep(150);
        alice.drainEvents();
        bob.drainEvents();

        boolean gotChallengeResult = bob.receivedEvents.stream()
                .anyMatch(e -> e instanceof ServerEvent.ChallengeResult res && res.isGuilty() && res.penalizedPlayer().equals("Alice") && res.penaltyCards() == 4);
        assertTrue(gotChallengeResult, "Bob should receive CHALLENGE_RESULT event stating Alice was GUILTY and drew 4 cards");
    }

    @Test
    @DisplayName("Reject duplicate player names in the same room (case-insensitive)")
    void shouldRejectDuplicatePlayerNamesInSameRoom() throws Exception {
        GameRoom room = new GameRoom("room-dup", 4);
        executor.submit(room);

        TestClient alice1 = createTestClient("s-1", "Alice");
        TestClient alice2 = createTestClient("s-2", "Alice");
        TestClient aliceLower = createTestClient("s-3", "alice");

        assertTrue(room.addPlayer(alice1.session, "Alice"));
        assertTrue(room.hasPlayer("Alice"));
        assertTrue(room.hasPlayer("alice"));
        assertFalse(room.hasPlayer("Bob"));

        // Exact match duplicate
        assertFalse(room.addPlayer(alice2.session, "Alice"));
        assertEquals(1, room.getPlayerCount());
        alice2.drainEvents();
        boolean gotNameTakenError1 = alice2.receivedEvents.stream()
                .anyMatch(e -> e instanceof ServerEvent.ErrorEvent err && err.errorMessage().contains("NAME_TAKEN"));
        assertTrue(gotNameTakenError1);

        // Case-insensitive duplicate
        assertFalse(room.addPlayer(aliceLower.session, "alice"));
        assertEquals(1, room.getPlayerCount());
        aliceLower.drainEvents();
        boolean gotNameTakenError2 = aliceLower.receivedEvents.stream()
                .anyMatch(e -> e instanceof ServerEvent.ErrorEvent err && err.errorMessage().contains("NAME_TAKEN"));
        assertTrue(gotNameTakenError2);
    }

    @Test
    @DisplayName("Support 16 players in a single room with 216-card combined deck and turn progression")
    void shouldSupportSixteenPlayersInSingleRoom() throws Exception {
        GameRoom room = new GameRoom("room-16p", 16);
        executor.submit(room);

        List<TestClient> clients = new ArrayList<>();
        for (int i = 1; i <= 16; i++) {
            TestClient client = createTestClient("s-" + i, "Player" + i);
            clients.add(client);
            assertTrue(room.addPlayer(client.session, "Player" + i));
        }

        assertEquals(16, room.getPlayerCount());
        assertTrue(room.isFull());
        assertEquals(RoomState.IN_PROGRESS, room.getState(), "Room should auto-start when 16th player joins");

        Thread.sleep(300);
        for (TestClient tc : clients) {
            tc.drainEvents();
            boolean gotHandUpdate = tc.receivedEvents.stream()
                    .anyMatch(e -> e instanceof ServerEvent.HandUpdate hu && hu.cards().size() == 7);
            assertTrue(gotHandUpdate, "All 16 players should receive initial 7 cards");
        }

        assertNotNull(room.getEngine());
        assertEquals(16, room.getEngine().getPlayers().size());
        assertEquals(103, room.getEngine().getDeck().size());

        // The current turn player draws a card
        String activePlayerName = room.getEngine().getTurnManager().getCurrentPlayer().getUsername();
        TestClient activeClient = clients.stream()
                .filter(c -> c.session.getUsername().equals(activePlayerName))
                .findFirst().orElseThrow();

        room.enqueueCommand(activeClient.session, new ClientCommand.Draw());
        Thread.sleep(150);
        activeClient.drainEvents();

        boolean gotDrawResult = activeClient.receivedEvents.stream()
                .anyMatch(e -> e instanceof ServerEvent.DrawResult);
        assertTrue(gotDrawResult, "Active player should receive DRAW_RESULT in 16-player game");
    }

    @Test
    @DisplayName("First player becomes room owner and only owner can start match")
    void firstPlayerBecomesOwnerAndOnlyOwnerCanStart() throws Exception {
        GameRoom room = new GameRoom("room-owner", 4);
        executor.submit(room);

        TestClient alice = createTestClient("s-1", "Alice");
        TestClient bob = createTestClient("s-2", "Bob");

        room.addPlayer(alice.session, "Alice");
        assertEquals("Alice", room.getOwnerUsername());

        room.addPlayer(bob.session, "Bob");
        assertEquals("Alice", room.getOwnerUsername());

        Thread.sleep(150);
        alice.drainEvents();
        bob.drainEvents();

        assertEquals("Alice", room.getOwnerUsername());

        // Bob (non-owner) tries to START
        room.enqueueCommand(bob.session, new ClientCommand.Start());
        Thread.sleep(150);
        bob.drainEvents();

        assertEquals(RoomState.WAITING_FOR_PLAYERS, room.getState());
        boolean bobGotOwnerError = bob.receivedEvents.stream().anyMatch(e -> e instanceof ServerEvent.ErrorEvent err && err.errorMessage().contains("Only room owner"));
        assertTrue(bobGotOwnerError);

        // Alice (owner) starts match
        room.enqueueCommand(alice.session, new ClientCommand.Start());
        Thread.sleep(150);
        alice.drainEvents();
        assertEquals(RoomState.IN_PROGRESS, room.getState());
    }

    @Test
    @DisplayName("Host can kick player from room and non-owner cannot kick")
    void hostCanKickPlayerAndNonOwnerCannotKick() throws Exception {
        GameRoom room = new GameRoom("room-kick", 4);
        executor.submit(room);

        TestClient alice = createTestClient("s-1", "Alice");
        TestClient bob = createTestClient("s-2", "Bob");
        TestClient charlie = createTestClient("s-3", "Charlie");

        room.addPlayer(alice.session, "Alice");
        room.addPlayer(bob.session, "Bob");
        room.addPlayer(charlie.session, "Charlie");

        Thread.sleep(100);
        bob.drainEvents();

        // Bob tries to kick Charlie (not owner)
        room.enqueueCommand(bob.session, new ClientCommand.Kick("Charlie"));
        Thread.sleep(150);
        bob.drainEvents();
        charlie.drainEvents();

        assertTrue(bob.receivedEvents.stream().anyMatch(e -> e instanceof ServerEvent.ErrorEvent err && err.errorMessage().contains("Only room owner")));
        assertEquals(3, room.getPlayerCount());

        // Alice (owner) kicks Charlie
        room.enqueueCommand(alice.session, new ClientCommand.Kick("Charlie"));
        Thread.sleep(200);
        charlie.drainEvents();
        alice.drainEvents();

        assertEquals(2, room.getPlayerCount());
        assertFalse(room.hasPlayer("Charlie"));
        assertTrue(charlie.receivedEvents.stream().anyMatch(e -> e instanceof ServerEvent.ErrorEvent err && err.errorMessage().contains("kicked")));
        assertTrue(alice.receivedEvents.stream().anyMatch(e -> e instanceof ServerEvent.ChatBroadcast cb && cb.message().contains("kicked")));
    }

    @Test
    @DisplayName("Host can transfer ownership to another player")
    void hostCanTransferOwnership() throws Exception {
        GameRoom room = new GameRoom("room-transfer", 4);
        executor.submit(room);

        TestClient alice = createTestClient("s-1", "Alice");
        TestClient bob = createTestClient("s-2", "Bob");

        room.addPlayer(alice.session, "Alice");
        room.addPlayer(bob.session, "Bob");
        assertEquals("Alice", room.getOwnerUsername());

        room.enqueueCommand(alice.session, new ClientCommand.TransferOwnership("Bob"));
        Thread.sleep(150);
        alice.drainEvents();
        bob.drainEvents();

        assertEquals("Bob", room.getOwnerUsername());
        assertTrue(bob.receivedEvents.stream().anyMatch(e -> e instanceof ServerEvent.RoomOwnerChanged roc && roc.newOwner().equals("Bob")));
    }

    @Test
    @DisplayName("Host disconnect automatically transfers ownership to next player")
    void hostDisconnectTransfersOwnership() throws Exception {
        GameRoom room = new GameRoom("room-succession", 4);
        executor.submit(room);

        TestClient alice = createTestClient("s-1", "Alice");
        TestClient bob = createTestClient("s-2", "Bob");

        room.addPlayer(alice.session, "Alice");
        room.addPlayer(bob.session, "Bob");
        assertEquals("Alice", room.getOwnerUsername());

        // Alice disconnects
        room.handleDisconnect(alice.session);
        Thread.sleep(150);
        bob.drainEvents();

        assertEquals(1, room.getPlayerCount());
        assertEquals("Bob", room.getOwnerUsername());
        assertTrue(bob.receivedEvents.stream().anyMatch(e -> e instanceof ServerEvent.RoomOwnerChanged roc && roc.newOwner().equals("Bob")));
    }

    @Test
    @DisplayName("Private room rejects join with invalid access code")
    void privateRoomValidatesAccessCode() throws Exception {
        GameRoom privateRoom = new GameRoom("room-priv", "VIP Club", RoomVisibility.PRIVATE, "secret123", 4);
        executor.submit(privateRoom);

        TestClient alice = createTestClient("s-1", "Alice");
        TestClient bob = createTestClient("s-2", "Bob");

        // Alice provides invalid access code
        boolean addedInvalid = privateRoom.addPlayer(alice.session, "Alice", "wrongCode");
        assertFalse(addedInvalid);
        alice.drainEvents();
        assertTrue(alice.receivedEvents.stream().anyMatch(e -> e instanceof ServerEvent.ErrorEvent err && err.errorMessage().contains("INVALID_ACCESS_CODE")));

        // Alice provides valid access code
        boolean addedValid = privateRoom.addPlayer(alice.session, "Alice", "secret123");
        assertTrue(addedValid);
        assertEquals(1, privateRoom.getPlayerCount());
        assertEquals("Alice", privateRoom.getOwnerUsername());
    }

    @Test
    @DisplayName("GameRoom broadcasts PLAYER_FINISHED and MATCH_STANDINGS across multi-tier finishes")
    void shouldBroadcastPodiumAndMatchStandingsWhenPlayersFinish() throws Exception {
        GameRoom room = new GameRoom("room-podium", 3);
        executor.submit(room);

        TestClient alice = createTestClient("s-1", "Alice");
        TestClient bob = createTestClient("s-2", "Bob");
        TestClient charlie = createTestClient("s-3", "Charlie");

        room.addPlayer(alice.session, "Alice");
        room.addPlayer(bob.session, "Bob");
        room.addPlayer(charlie.session, "Charlie");

        Thread.sleep(150);
        alice.drainEvents();
        bob.drainEvents();
        charlie.drainEvents();

        UltimoEngine engine = room.getEngine();
        assertNotNull(engine);

        // Turn order: Alice -> Bob -> Charlie
        Player pAlice = engine.getTurnManager().getCurrentPlayer();
        CardColor color = engine.getDiscardPile().getActiveColor();

        // Alice plays winning card
        pAlice.getHand().clear();
        Card aliceWin = new Card(color, CardValue.ONE);
        pAlice.getHand().addCard(aliceWin);

        room.enqueueCommand(alice.session, new ClientCommand.Play(aliceWin, null));
        Thread.sleep(150);
        bob.drainEvents();

        assertTrue(bob.receivedEvents.stream().anyMatch(e -> e instanceof ServerEvent.PlayerFinished pf && pf.playerName().equals("Alice") && pf.rank() == 1));
        assertEquals(RoomState.IN_PROGRESS, room.getState(), "Room should still be IN_PROGRESS");

        // Bob plays winning card
        Player pBob = engine.getTurnManager().getCurrentPlayer();
        pBob.getHand().clear();
        Card bobWin = new Card(aliceWin.color(), CardValue.TWO);
        pBob.getHand().addCard(bobWin);

        room.enqueueCommand(bob.session, new ClientCommand.Play(bobWin, null));
        Thread.sleep(150);
        charlie.drainEvents();

        assertEquals(RoomState.FINISHED, room.getState());
        assertTrue(charlie.receivedEvents.stream().anyMatch(e -> e instanceof ServerEvent.PlayerFinished pf && pf.playerName().equals("Bob") && pf.rank() == 2));
        assertTrue(charlie.receivedEvents.stream().anyMatch(e -> e instanceof ServerEvent.PlayerFinished pf && pf.playerName().equals("Charlie") && pf.rank() == 3));
        assertTrue(charlie.receivedEvents.stream().anyMatch(e -> e instanceof ServerEvent.MatchStandings ms && ms.placements().size() == 3));
        assertTrue(charlie.receivedEvents.stream().anyMatch(e -> e instanceof ServerEvent.GameOver go && go.winnerName().equals("Alice")));
    }

    @Test
    @DisplayName("Player disconnect and reconnect within grace period restores secret hand and game state")
    void testPlayerDisconnectAndReconnectWithinTwoMinutes() throws Exception {
        GameRoom room = new GameRoom("room-reconnect", 2);
        executor.submit(room);

        TestClient alice = createTestClient("s-1", "Alice");
        TestClient bob = createTestClient("s-2", "Bob");

        room.addPlayer(alice.session, "Alice");
        room.addPlayer(bob.session, "Bob"); // Starts match
        assertEquals(RoomState.IN_PROGRESS, room.getState());

        Thread.sleep(100);
        alice.drainEvents();
        bob.drainEvents();

        // Find Alice's hand cards
        ServerEvent.HandUpdate aliceHandBefore = alice.receivedEvents.stream()
                .filter(e -> e instanceof ServerEvent.HandUpdate)
                .map(e -> (ServerEvent.HandUpdate) e)
                .reduce((first, second) -> second)
                .orElseThrow();
        assertFalse(aliceHandBefore.cards().isEmpty());

        // Alice's socket disconnects
        room.handleDisconnect(alice.session);

        // Room remains IN_PROGRESS during grace period
        assertEquals(RoomState.IN_PROGRESS, room.getState());
        assertTrue(room.isDisconnected("Alice"));
        assertEquals(1, room.getConnectedSessionCount());

        Thread.sleep(100);
        bob.drainEvents();
        assertTrue(bob.receivedEvents.stream().anyMatch(e -> e instanceof ServerEvent.ChatBroadcast cb && cb.message().contains("Alice disconnected. They have 2 minutes to rejoin.")));

        // Alice reconnects with new session and same username
        TestClient aliceReconnected = createTestClient("s-3", "Alice");
        boolean reconnected = room.addPlayer(aliceReconnected.session, "Alice");
        assertTrue(reconnected);
        assertFalse(room.isDisconnected("Alice"));
        assertEquals(2, room.getConnectedSessionCount());

        Thread.sleep(100);
        aliceReconnected.drainEvents();
        bob.drainEvents();

        // Alice received state recovery snapshot
        ServerEvent.HandUpdate restoredHand = aliceReconnected.receivedEvents.stream()
                .filter(e -> e instanceof ServerEvent.HandUpdate)
                .map(e -> (ServerEvent.HandUpdate) e)
                .findFirst()
                .orElseThrow();
        assertEquals(aliceHandBefore.cards().size(), restoredHand.cards().size());
        assertEquals(aliceHandBefore.cards(), restoredHand.cards());

        assertTrue(aliceReconnected.receivedEvents.stream().anyMatch(e -> e instanceof ServerEvent.TopCard));
        assertTrue(aliceReconnected.receivedEvents.stream().anyMatch(e -> e instanceof ServerEvent.TurnAnnounced));
        assertTrue(aliceReconnected.receivedEvents.stream().anyMatch(e -> e instanceof ServerEvent.OpponentUpdate opp && opp.opponentName().equals("Bob")));

        // Bob was notified of reconnection
        assertTrue(bob.receivedEvents.stream().anyMatch(e -> e instanceof ServerEvent.ChatBroadcast cb && cb.message().contains("Alice has reconnected!")));
    }

    @Test
    @DisplayName("Player disconnect expiration removes player and awards forfeit victory to remaining player")
    void testPlayerDisconnectExpiresAfterTwoMinutes() throws Exception {
        GameRoom room = new GameRoom("room-expire", 2);
        room.setReconnectGracePeriodMs(60);
        room.setWatchdogCheckIntervalMs(20);
        executor.submit(room);

        TestClient alice = createTestClient("s-1", "Alice");
        TestClient bob = createTestClient("s-2", "Bob");

        room.addPlayer(alice.session, "Alice");
        room.addPlayer(bob.session, "Bob"); // Starts match
        assertEquals(RoomState.IN_PROGRESS, room.getState());

        Thread.sleep(100);
        alice.drainEvents();
        bob.drainEvents();

        // Bob disconnects
        room.handleDisconnect(bob.session);
        assertEquals(RoomState.IN_PROGRESS, room.getState());
        assertTrue(room.isDisconnected("Bob"));

        // Wait for watchdog to expire Bob's grace period
        Thread.sleep(180);
        alice.drainEvents();

        assertEquals(RoomState.FINISHED, room.getState());
        assertFalse(room.isDisconnected("Bob"));
        assertTrue(alice.receivedEvents.stream().anyMatch(e -> e instanceof ServerEvent.ChatBroadcast cb && cb.message().contains("Bob failed to reconnect within 2 minutes and was removed.")));
        assertTrue(alice.receivedEvents.stream().anyMatch(e -> e instanceof ServerEvent.GameOver go && go.winnerName().equals("Alice")));
        assertTrue(alice.receivedEvents.stream().anyMatch(e -> e instanceof ServerEvent.ChatBroadcast cb && cb.message().contains("Alice wins by forfeit!")));
    }

    @Test
    @DisplayName("When current player disconnect expires, turn advances to next active player in 3-player match")
    void testTurnAdvancesWhenCurrentPlayerDisconnectExpires() throws Exception {
        GameRoom room = new GameRoom("room-turn-advance", 3);
        room.setReconnectGracePeriodMs(60);
        room.setWatchdogCheckIntervalMs(20);
        executor.submit(room);

        TestClient alice = createTestClient("s-1", "Alice");
        TestClient bob = createTestClient("s-2", "Bob");
        TestClient charlie = createTestClient("s-3", "Charlie");

        room.addPlayer(alice.session, "Alice");
        room.addPlayer(bob.session, "Bob");
        room.addPlayer(charlie.session, "Charlie"); // Starts match
        assertEquals(RoomState.IN_PROGRESS, room.getState());

        Thread.sleep(100);
        alice.drainEvents();
        bob.drainEvents();
        charlie.drainEvents();

        // Determine who has the first turn
        String currentTurn = room.getEngine().getTurnManager().getCurrentPlayer().getUsername();
        TestClient currentClient = currentTurn.equals("Alice") ? alice : currentTurn.equals("Bob") ? bob : charlie;

        // Current player disconnects
        room.handleDisconnect(currentClient.session);
        assertTrue(room.isDisconnected(currentTurn));

        // Wait for expiration
        Thread.sleep(180);

        assertEquals(RoomState.IN_PROGRESS, room.getState());
        assertEquals(2, room.getPlayerCount());
        assertFalse(room.isDisconnected(currentTurn));

        String nextTurn = room.getEngine().getTurnManager().getCurrentPlayer().getUsername();
        assertNotEquals(currentTurn, nextTurn, "Turn must advance away from expired player");
    }

    @Test
    @DisplayName("Connected player going AFK on their turn triggers auto-draw and auto-pass via checkTurnInactivity")
    void testConnectedPlayerAfkTurnTimeout() throws Exception {
        GameRoom room = new GameRoom("room-afk-1", 2);
        long t0 = 1_000_000L;

        TestClient alice = createTestClient("s-1", "Alice");
        TestClient bob = createTestClient("s-2", "Bob");

        room.addPlayer(alice.session, "Alice");
        room.addPlayer(bob.session, "Bob"); // Starts match
        assertEquals(RoomState.IN_PROGRESS, room.getState());

        String activeUser = room.getEngine().getTurnManager().getCurrentPlayer().getUsername();
        room.setTurnStartedTimestamp(t0);

        // Before timeout (t0 + 60s) -> no timeout
        room.checkTurnInactivity(t0 + 60_000L);
        assertEquals(activeUser, room.getEngine().getTurnManager().getCurrentPlayer().getUsername());
        assertEquals(0, room.getConsecutiveAfkTurns(activeUser));

        // After timeout (t0 + 121s) -> auto-draw & auto-pass
        room.checkTurnInactivity(t0 + 121_000L);

        String newActiveUser = room.getEngine().getTurnManager().getCurrentPlayer().getUsername();
        assertNotEquals(activeUser, newActiveUser, "Turn must advance after AFK turn timeout");
        assertEquals(1, room.getConsecutiveAfkTurns(activeUser));

        alice.drainEvents();
        bob.drainEvents();
        assertTrue(alice.receivedEvents.stream().anyMatch(e -> e instanceof ServerEvent.ChatBroadcast cb &&
                cb.message().contains(activeUser + " was inactive for 2 minutes; turn auto-passed.")));
        assertTrue(bob.receivedEvents.stream().anyMatch(e -> e instanceof ServerEvent.ChatBroadcast cb &&
                cb.message().contains(activeUser + " was inactive for 2 minutes; turn auto-passed.")));
    }

    @Test
    @DisplayName("Two consecutive AFK turn timeouts kicks player and awards forfeit victory to remaining player")
    void testTwoConsecutiveAfkTimeoutsKicksPlayer() throws Exception {
        GameRoom room = new GameRoom("room-afk-kick", 2);
        long t0 = 1_000_000L;

        TestClient alice = createTestClient("s-1", "Alice");
        TestClient bob = createTestClient("s-2", "Bob");

        room.addPlayer(alice.session, "Alice");
        room.addPlayer(bob.session, "Bob"); // Starts match
        assertEquals(RoomState.IN_PROGRESS, room.getState());

        String firstPlayer = room.getEngine().getTurnManager().getCurrentPlayer().getUsername();
        String secondPlayer = firstPlayer.equals("Alice") ? "Bob" : "Alice";
        room.setTurnStartedTimestamp(t0);

        // 1st AFK timeout for firstPlayer
        room.checkTurnInactivity(t0 + 121_000L);
        assertEquals(1, room.getConsecutiveAfkTurns(firstPlayer));
        assertEquals(secondPlayer, room.getEngine().getTurnManager().getCurrentPlayer().getUsername());

        // Second player passes back to first player
        Player p2 = room.getEngine().getTurnManager().getCurrentPlayer();
        UltimoEngine.DrawResult dr = room.getEngine().drawCardWithResult(p2);
        if (!dr.autoPassed()) {
            room.getEngine().passTurn(p2);
        }
        assertEquals(firstPlayer, room.getEngine().getTurnManager().getCurrentPlayer().getUsername());

        long t1 = t0 + 200_000L;
        room.setTurnStartedTimestamp(t1);

        // 2nd AFK timeout for firstPlayer -> kicks player
        room.checkTurnInactivity(t1 + 121_000L);

        assertEquals(RoomState.FINISHED, room.getState());
        assertEquals(1, room.getPlayerCount());
        assertEquals(secondPlayer, room.getPlayerNames().getFirst());

        TestClient secondClient = secondPlayer.equals("Alice") ? alice : bob;
        secondClient.drainEvents();
        assertTrue(secondClient.receivedEvents.stream().anyMatch(e -> e instanceof ServerEvent.ChatBroadcast cb &&
                cb.message().contains(firstPlayer + " was removed from match due to repeated inactivity.")));
        assertTrue(secondClient.receivedEvents.stream().anyMatch(e -> e instanceof ServerEvent.GameOver go &&
                go.winnerName().equals(secondPlayer)));
        assertTrue(secondClient.receivedEvents.stream().anyMatch(e -> e instanceof ServerEvent.ChatBroadcast cb &&
                cb.message().contains(secondPlayer + " wins by forfeit!")));
    }

    @Test
    @DisplayName("Conscious player move resets consecutive AFK counter")
    void testConsciousMoveResetsAfkCounter() throws Exception {
        GameRoom room = new GameRoom("room-afk-reset", 2);
        long t0 = 1_000_000L;

        TestClient alice = createTestClient("s-1", "Alice");
        TestClient bob = createTestClient("s-2", "Bob");

        room.addPlayer(alice.session, "Alice");
        room.addPlayer(bob.session, "Bob"); // Starts match
        assertEquals(RoomState.IN_PROGRESS, room.getState());

        String firstPlayer = room.getEngine().getTurnManager().getCurrentPlayer().getUsername();
        TestClient firstClient = firstPlayer.equals("Alice") ? alice : bob;
        room.setTurnStartedTimestamp(t0);

        // 1st AFK timeout
        room.checkTurnInactivity(t0 + 121_000L);
        assertEquals(1, room.getConsecutiveAfkTurns(firstPlayer));

        // Pass turn back to first player
        Player p2 = room.getEngine().getTurnManager().getCurrentPlayer();
        UltimoEngine.DrawResult dr = room.getEngine().drawCardWithResult(p2);
        if (!dr.autoPassed()) {
            room.getEngine().passTurn(p2);
        }
        assertEquals(firstPlayer, room.getEngine().getTurnManager().getCurrentPlayer().getUsername());

        // When player draws or passes, consecutiveAfkTurns resets
        executor.submit(room);
        room.enqueueCommand(firstClient.session, new ClientCommand.Draw());
        Thread.sleep(100);
        assertEquals(0, room.getConsecutiveAfkTurns(firstPlayer), "Conscious draw command must reset AFK counter");
    }

    @Test
    @DisplayName("Virtual Thread watchdog automatically detects idle player and auto-passes turn")
    void testWatchdogLiveExecution() throws Exception {
        GameRoom room = new GameRoom("room-watchdog-live", 2);
        room.setAfkTurnTimeoutMs(150);
        room.setWatchdogCheckIntervalMs(30);
        executor.submit(room);

        TestClient alice = createTestClient("s-1", "Alice");
        TestClient bob = createTestClient("s-2", "Bob");

        room.addPlayer(alice.session, "Alice");
        room.addPlayer(bob.session, "Bob"); // Starts match
        assertEquals(RoomState.IN_PROGRESS, room.getState());

        String activeBefore = room.getEngine().getTurnManager().getCurrentPlayer().getUsername();
        Thread.sleep(50);
        assertEquals(activeBefore, room.getEngine().getTurnManager().getCurrentPlayer().getUsername());

        // Wait past 150ms timeout
        Thread.sleep(220);

        String activeAfter = room.getEngine().getTurnManager().getCurrentPlayer().getUsername();
        assertNotEquals(activeBefore, activeAfter, "Watchdog must automatically advance turn after timeout");
    }
}
