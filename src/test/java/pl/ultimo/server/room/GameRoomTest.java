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
        public void drainEvents() {
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
        TestClient alice = createTestClient("s-1", "Alice");
        TestClient bob = createTestClient("s-2", "Bob");

        room.addPlayer(alice.session, "Alice");
        room.addPlayer(bob.session, "Bob"); // Starts match
        assertEquals(RoomState.IN_PROGRESS, room.getState());

        // Bob disconnects
        room.handleDisconnect(bob.session);

        Thread.sleep(100);
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
}
