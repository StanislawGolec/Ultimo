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
import java.util.concurrent.locks.ReentrantLock;

/**
 * Manages active rooms, handles matchmaking for incoming players,
 * and lifecycle cleanup of completed rooms.
 * Uses ReentrantLock and Virtual Threads for zero-pinning high scalability.
 */
public class RoomManager {

    private final ServerConfig config;
    private final Map<String, GameRoom> activeRooms = new ConcurrentHashMap<>();
    private final Map<String, GameRoom> sessionRoomMap = new ConcurrentHashMap<>();
    private final AtomicInteger roomCounter = new AtomicInteger(1);
    private final ExecutorService roomExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ReentrantLock matchmakeLock = new ReentrantLock();

    public RoomManager(ServerConfig config) {
        this.config = config;
        MetricsRegistry.getInstance().setActiveRoomsSupplier(this::getActiveRoomCount);
    }

    /**
     * Finds an open room with capacity or creates a new one, enrolling the player.
     * Uses ReentrantLock to prevent Project Loom carrier thread pinning.
     */
    public GameRoom matchmake(ClientSession session, String username) {
        matchmakeLock.lock();
        try {
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

            // Run the room on its own dedicated virtual thread event loop
            roomExecutor.submit(newRoom);

            return newRoom;
        } finally {
            matchmakeLock.unlock();
        }
    }

    /**
     * Enrolls player in a targeted room by ID, creating the room if it doesn't already exist.
     */
    public GameRoom joinRoom(ClientSession session, String username, String roomId) {
        if (roomId == null || roomId.isBlank()) {
            return matchmake(session, username);
        }

        matchmakeLock.lock();
        try {
            GameRoom room = activeRooms.get(roomId);
            if (room == null) {
                room = new GameRoom(roomId, config.defaultRoomCapacity());
                activeRooms.put(roomId, room);
                roomExecutor.submit(room);
            }

            if (room.isFull()) {
                session.sendEvent(new pl.ultimo.protocol.ServerEvent.ErrorEvent("Targeted room " + roomId + " is full"));
                return room;
            }

            boolean added = room.addPlayer(session, username);
            if (added) {
                sessionRoomMap.put(session.getSessionId(), room);
            }
            return room;
        } finally {
            matchmakeLock.unlock();
        }
    }

    public Optional<GameRoom> getRoomForSession(String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(sessionRoomMap.get(sessionId));
    }

    public Optional<GameRoom> getRoom(String roomId) {
        if (roomId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(activeRooms.get(roomId));
    }

    /**
     * Handles player disconnect, notifying the corresponding room and cleaning up empty rooms.
     * Purges all session and room references to eliminate memory leaks.
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
                sessionRoomMap.entrySet().removeIf(entry -> entry.getValue().getRoomId().equals(room.getRoomId()));
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
