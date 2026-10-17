package pl.ultimo.server.room;

import pl.ultimo.server.config.ServerConfig;
import pl.ultimo.server.metrics.MetricsRegistry;
import pl.ultimo.server.net.ClientSession;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages active rooms, handles matchmaking for incoming players,
 * and lifecycle cleanup of completed rooms.
 */
public class RoomManager {

    private final ServerConfig config;
    private final Map<String, GameRoom> activeRooms = new ConcurrentHashMap<>();
    private final Map<String, GameRoom> sessionRoomMap = new ConcurrentHashMap<>();
    private final AtomicInteger roomCounter = new AtomicInteger(1);
    private final ExecutorService roomExecutor = Executors.newCachedThreadPool();

    public RoomManager(ServerConfig config) {
        this.config = config;
        MetricsRegistry.getInstance().setActiveRoomsSupplier(this::getActiveRoomCount);
    }

    /**
     * Finds an open room with capacity or creates a new one, enrolling the player.
     */
    public synchronized GameRoom matchmake(ClientSession session, String username) {
        // Look for an existing room waiting for players
        for (GameRoom room : activeRooms.values()) {
            if (room.getState() == RoomState.WAITING_FOR_PLAYERS && !room.isFull()) {
                boolean added = room.addPlayer(session, username);
                if (added) {
                    sessionRoomMap.put(session.getSessionId(), room);
                    return room;
                }
            }
        }

        // If no available room, spin up a new room
        String roomId = "room-" + roomCounter.getAndIncrement();
        GameRoom newRoom = new GameRoom(roomId, config.defaultRoomCapacity());
        newRoom.addPlayer(session, username);

        activeRooms.put(roomId, newRoom);
        sessionRoomMap.put(session.getSessionId(), newRoom);

        // Run the room on its own dedicated event-loop worker thread
        roomExecutor.submit(newRoom);

        return newRoom;
    }

    public Optional<GameRoom> getRoomForSession(String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(sessionRoomMap.get(sessionId));
    }

    /**
     * Handles player disconnect, notifying the corresponding room and cleaning up empty rooms.
     */
    public void handleDisconnect(ClientSession session) {
        if (session == null) {
            return;
        }

        GameRoom room = sessionRoomMap.remove(session.getSessionId());
        if (room != null) {
            room.handleDisconnect(session);

            if (room.getState() == RoomState.FINISHED || room.getPlayerCount() == 0) {
                activeRooms.remove(room.getRoomId());
                room.stop();
            }
        }
    }

    public int getActiveRoomCount() {
        return activeRooms.size();
    }

    public Map<String, GameRoom> getActiveRooms() {
        return Map.copyOf(activeRooms);
    }

    public void shutdown() {
        for (GameRoom room : activeRooms.values()) {
            room.stop();
        }
        activeRooms.clear();
        sessionRoomMap.clear();
        roomExecutor.shutdownNow();
    }
}
