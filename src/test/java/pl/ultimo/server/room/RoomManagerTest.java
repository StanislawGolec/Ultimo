package pl.ultimo.server.room;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.ultimo.protocol.ClientCommand;
import pl.ultimo.protocol.RoomVisibility;
import pl.ultimo.protocol.ServerEvent;
import pl.ultimo.server.config.ServerConfig;
import pl.ultimo.server.net.ClientSession;
import pl.ultimo.server.net.ConnectionListener;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RoomManager Matchmaking Unit Tests")
class RoomManagerTest {

    private RoomManager roomManager;
    private final List<Socket> socketsToClose = new ArrayList<>();
    private final List<ServerSocket> serverSocketsToClose = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // Room capacity of 2 for fast testing
        ServerConfig config = new ServerConfig(0, 2, 10_000, 10);
        roomManager = new RoomManager(config);
    }

    @AfterEach
    void tearDown() {
        roomManager.shutdown();
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

    private ClientSession createDummySession(String sessionId, String username) throws Exception {
        ServerSocket ss = new ServerSocket(0);
        serverSocketsToClose.add(ss);
        Socket client = new Socket("localhost", ss.getLocalPort());
        Socket server = ss.accept();
        socketsToClose.add(client);
        socketsToClose.add(server);

        ClientSession session = new ClientSession(sessionId, server, new ConnectionListener() {
            @Override
            public void onCommandReceived(ClientSession s, ClientCommand c) {}
            @Override
            public void onSessionDisconnected(ClientSession s) {}
        });
        session.setUsername(username);
        return session;
    }

    @Test
    @DisplayName("Matchmaking places consecutive arriving players into the same open room")
    void matchmakingPlacesPlayersInSameRoom() throws Exception {
        ClientSession alice = createDummySession("s-1", "Alice");
        ClientSession bob = createDummySession("s-2", "Bob");

        GameRoom room1 = roomManager.matchmake(alice, "Alice");
        GameRoom room2 = roomManager.matchmake(bob, "Bob");

        assertSame(room1, room2, "Both players should have been matched into the exact same room");
        assertEquals(2, room1.getPlayerCount());
        assertTrue(room1.isFull());
    }

    @Test
    @DisplayName("When a room is full, matchmaking automatically spins up a new room")
    void matchmakingCreatesNewRoomWhenFull() throws Exception {
        ClientSession alice = createDummySession("s-1", "Alice");
        ClientSession bob = createDummySession("s-2", "Bob");
        ClientSession charlie = createDummySession("s-3", "Charlie");

        GameRoom room1 = roomManager.matchmake(alice, "Alice");
        GameRoom room2 = roomManager.matchmake(bob, "Bob");
        GameRoom room3 = roomManager.matchmake(charlie, "Charlie");

        assertSame(room1, room2);
        assertNotSame(room1, room3, "Charlie should have been matched into a new room because the first is full");

        assertEquals(2, roomManager.getActiveRoomCount());
        assertEquals("room-1", room1.getRoomId());
        assertEquals("room-2", room3.getRoomId());
    }

    @Test
    @DisplayName("Disconnecting removes mapping and cleans up empty room")
    void disconnectingCleansUpRoom() throws Exception {
        ClientSession alice = createDummySession("s-1", "Alice");
        GameRoom room = roomManager.matchmake(alice, "Alice");

        assertTrue(roomManager.getRoomForSession("s-1").isPresent());
        assertEquals(1, roomManager.getActiveRoomCount());

        roomManager.handleDisconnect(alice);

        assertTrue(roomManager.getRoomForSession("s-1").isEmpty());
        assertEquals(0, roomManager.getActiveRoomCount(), "Room should be removed when empty");
    }

    @Test
    @DisplayName("Targeted room joining places players into specifically requested room")
    void targetedRoomJoiningPlacesPlayersInRequestedRoom() throws Exception {
        ClientSession alice = createDummySession("s-1", "Alice");
        ClientSession bob = createDummySession("s-2", "Bob");

        GameRoom r1 = roomManager.joinRoom(alice, "Alice", "custom-room-99");
        GameRoom r2 = roomManager.joinRoom(bob, "Bob", "custom-room-99");

        assertSame(r1, r2);
        assertEquals("custom-room-99", r1.getRoomId());
        assertEquals(2, r1.getPlayerCount());
    }

    @Test
    @DisplayName("Targeted room joining rejects player when requested room is full")
    void targetedRoomJoiningRejectsWhenFull() throws Exception {
        ClientSession alice = createDummySession("s-1", "Alice");
        ClientSession bob = createDummySession("s-2", "Bob");
        ClientSession charlie = createDummySession("s-3", "Charlie");

        roomManager.joinRoom(alice, "Alice", "full-room-1");
        roomManager.joinRoom(bob, "Bob", "full-room-1");

        // Charlie tries to join full room (capacity 2)
        GameRoom r3 = roomManager.joinRoom(charlie, "Charlie", "full-room-1");
        assertEquals(2, r3.getPlayerCount());
        assertTrue(roomManager.getRoomForSession("s-3").isEmpty(), "Charlie should not be registered in sessionRoomMap");
    }

    @Test
    @DisplayName("Targeted room joining rejects duplicate player username in requested room")
    void targetedRoomJoiningRejectsDuplicateUsername() throws Exception {
        ClientSession alice1 = createDummySession("s-1", "Alice");
        ClientSession alice2 = createDummySession("s-2", "Alice");

        roomManager.joinRoom(alice1, "Alice", "custom-room-dup");
        roomManager.joinRoom(alice2, "Alice", "custom-room-dup");

        assertEquals(1, roomManager.getRoom("custom-room-dup").get().getPlayerCount());
        assertTrue(roomManager.getRoomForSession("s-1").isPresent());
        assertTrue(roomManager.getRoomForSession("s-2").isEmpty(), "Duplicate Alice should not be mapped to room");
    }

    @Test
    @DisplayName("Matchmaking creates new room when open room already contains same username")
    void matchmakingAvoidsDuplicateUsernameInSameRoom() throws Exception {
        ClientSession alice1 = createDummySession("s-1", "Alice");
        ClientSession alice2 = createDummySession("s-2", "Alice");

        GameRoom r1 = roomManager.matchmake(alice1, "Alice");
        GameRoom r2 = roomManager.matchmake(alice2, "Alice");

        assertNotSame(r1, r2, "Second Alice should be matched into a separate room to prevent duplicate usernames");
        assertEquals(1, r1.getPlayerCount());
        assertEquals(1, r2.getPlayerCount());
    }

    @Test
    @DisplayName("Matchmaking and targeted joining support custom capacity between 2 and 16 players")
    void customCapacityRoomCreation() throws Exception {
        // First matchmake creates room-1 with custom capacity 12 on empty server
        ClientSession player = createDummySession("s-p", "Player");
        GameRoom mmRoom = roomManager.matchmake(player, "Player", 12);
        assertEquals(12, mmRoom.getMaxPlayers());
        assertEquals(1, mmRoom.getPlayerCount());

        // Targeted joining creates big-table with capacity 16
        ClientSession host = createDummySession("s-host", "Host");
        GameRoom room = roomManager.joinRoom(host, "Host", "big-table", 16);
        assertEquals(16, room.getMaxPlayers());
        assertEquals("big-table", room.getRoomId());
        assertEquals(1, room.getPlayerCount());
    }

    @Test
    @DisplayName("createRoom supports Public and Private rooms, and listPublicRooms excludes private rooms")
    void createRoomAndListPublicRooms() throws Exception {
        ClientSession alice = createDummySession("s-1", "Alice");
        ClientSession bob = createDummySession("s-2", "Bob");

        // Alice creates a public room
        GameRoom pubRoom = roomManager.createRoom(alice, "Alice", "Pub Table", RoomVisibility.PUBLIC, null, 4);
        assertEquals("Pub Table", pubRoom.getRoomName());
        assertEquals(RoomVisibility.PUBLIC, pubRoom.getVisibility());
        assertFalse(pubRoom.isPrivate());

        // Bob creates a private room with code
        GameRoom privRoom = roomManager.createRoom(bob, "Bob", "VIP Table", RoomVisibility.PRIVATE, "secret", 6);
        assertEquals("VIP Table", privRoom.getRoomName());
        assertEquals(RoomVisibility.PRIVATE, privRoom.getVisibility());
        assertTrue(privRoom.isPrivate());

        // listPublicRooms must contain Alice's public room and MUST NOT contain Bob's private room
        List<ServerEvent.RoomEntry> publicRooms = roomManager.listPublicRooms();
        assertEquals(1, publicRooms.size());
        assertEquals(pubRoom.getRoomId(), publicRooms.get(0).roomId());
        assertEquals("Pub Table", publicRooms.get(0).name());
    }

    @Test
    @DisplayName("Private room join requires correct access code and matchmaking never matches into private rooms")
    void privateRoomAccessAndMatchmakingExclusion() throws Exception {
        ClientSession host = createDummySession("s-host", "Host");
        GameRoom privRoom = roomManager.createRoom(host, "Host", "Priv Room", RoomVisibility.PRIVATE, "pass123", 4);

        // Charlie tries to matchmake - should NOT enter private room
        ClientSession charlie = createDummySession("s-charlie", "Charlie");
        GameRoom mmRoom = roomManager.matchmake(charlie, "Charlie");
        assertNotSame(privRoom, mmRoom);

        // Dave tries to join private room with wrong access code
        ClientSession dave = createDummySession("s-dave", "Dave");
        roomManager.joinRoom(dave, "Dave", privRoom.getRoomId(), 4, "wrongCode");
        assertTrue(roomManager.getRoomForSession("s-dave").isEmpty(), "Dave should not be enrolled with wrong code");
        assertEquals(1, privRoom.getPlayerCount());

        // Dave joins private room with correct access code
        roomManager.joinRoom(dave, "Dave", privRoom.getRoomId(), 4, "pass123");
        assertTrue(roomManager.getRoomForSession("s-dave").isPresent(), "Dave should be enrolled with correct code");
        assertEquals(2, privRoom.getPlayerCount());
    }

    @Test
    @DisplayName("Immediate empty room reclamation triggers when all player sessions disconnect")
    void immediateEmptyRoomReclamationWhenAllSessionsDisconnect() throws Exception {
        ClientSession alice = createDummySession("s-1", "Alice");
        ClientSession bob = createDummySession("s-2", "Bob");

        GameRoom room = roomManager.matchmake(alice, "Alice", 2);
        roomManager.matchmake(bob, "Bob", 2);
        assertEquals(RoomState.IN_PROGRESS, room.getState());
        assertEquals(1, roomManager.getActiveRoomCount());

        // Alice disconnects - 1 connected session remains, room stays active in grace period
        roomManager.handleDisconnect(alice);
        assertEquals(1, roomManager.getActiveRoomCount());
        assertTrue(room.isDisconnected("Alice"));

        // Bob also disconnects - 0 connected sessions remain, immediate empty room reclamation triggers
        roomManager.handleDisconnect(bob);
        assertEquals(0, roomManager.getActiveRoomCount(), "Room should be purged immediately when all sessions disconnect");
        assertFalse(room.isRunning());
    }

    @Test
    @DisplayName("Player reconnects to in-progress match via matchmake or joinRoom")
    void playerReconnectsViaMatchmakeOrJoinRoom() throws Exception {
        ClientSession alice1 = createDummySession("s-alice-1", "Alice");
        ClientSession bob = createDummySession("s-bob", "Bob");

        GameRoom room = roomManager.joinRoom(alice1, "Alice", "custom-reconnect-room", 2, null);
        roomManager.joinRoom(bob, "Bob", "custom-reconnect-room", 2, null);
        assertEquals(RoomState.IN_PROGRESS, room.getState());

        // Alice disconnects
        roomManager.handleDisconnect(alice1);
        assertTrue(room.isDisconnected("Alice"));
        assertTrue(roomManager.getRoomForSession("s-alice-1").isEmpty());

        // Alice reconnects via joinRoom
        ClientSession alice2 = createDummySession("s-alice-2", "Alice");
        GameRoom rejoinedRoom = roomManager.joinRoom(alice2, "Alice", "custom-reconnect-room", 2, null);
        assertSame(room, rejoinedRoom);
        assertFalse(room.isDisconnected("Alice"));
        assertTrue(roomManager.getRoomForSession("s-alice-2").isPresent());

        // Alice disconnects again
        roomManager.handleDisconnect(alice2);
        assertTrue(room.isDisconnected("Alice"));

        // Alice reconnects via matchmake without specifying room ID
        ClientSession alice3 = createDummySession("s-alice-3", "Alice");
        GameRoom mmRoom = roomManager.matchmake(alice3, "Alice");
        assertSame(room, mmRoom);
        assertFalse(room.isDisconnected("Alice"));
        assertTrue(roomManager.getRoomForSession("s-alice-3").isPresent());
    }

    @Test
    @DisplayName("RoomManager validates cryptographic reconnectToken via reconnectPlayer and supports addBotToRoom")
    void reconnectPlayerWithCryptographicTokenAndAddBot() throws Exception {
        ClientSession alice = createDummySession("s-alice", "Alice");
        GameRoom room = roomManager.joinRoom(alice, "Alice", "bot-and-token-room", 2, null);

        assertTrue(roomManager.addBotToRoom("bot-and-token-room", "BotOpponent", "HARD"));
        assertEquals(2, room.getPlayerCount());
        assertEquals(RoomState.IN_PROGRESS, room.getState());

        String aliceToken = room.getReconnectToken("Alice");
        assertNotNull(aliceToken);

        roomManager.handleDisconnect(alice);
        assertTrue(room.isDisconnected("Alice"));

        ClientSession attacker = createDummySession("s-attacker", "Alice");
        assertFalse(roomManager.reconnectPlayer(attacker, "Alice", "bot-and-token-room", "invalid-token"));
        assertTrue(room.isDisconnected("Alice"));

        ClientSession aliceRejoined = createDummySession("s-alice-rejoined", "Alice");
        assertTrue(roomManager.reconnectPlayer(aliceRejoined, "Alice", "bot-and-token-room", aliceToken));
        assertFalse(room.isDisconnected("Alice"));
        assertTrue(roomManager.getRoomForSession("s-alice-rejoined").isPresent());
    }
}
