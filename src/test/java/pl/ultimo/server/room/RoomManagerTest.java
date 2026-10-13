package pl.ultimo.server.room;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.ultimo.protocol.ClientCommand;
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
}
