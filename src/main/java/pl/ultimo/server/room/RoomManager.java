package pl.ultimo.server.room;

import pl.ultimo.protocol.RoomVisibility;
import pl.ultimo.protocol.ServerEvent;
import pl.ultimo.server.config.ServerConfig;
import pl.ultimo.server.metrics.MetricsRegistry;
import pl.ultimo.server.net.ClientSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
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
     * Finds an open public room with capacity or creates a new one, enrolling the player.
     * Uses ReentrantLock to prevent Project Loom carrier thread pinning.
     */
    public GameRoom matchmake(ClientSession session, String username) {
        return matchmake(session, username, config.defaultRoomCapacity());
    }

    /**
     * Finds an open public room with capacity or creates a new one with the target capacity, enrolling the player.
     * Uses ReentrantLock to prevent Project Loom carrier thread pinning.
     */
    public GameRoom matchmake(ClientSession session, String username, int capacity) {
        int targetCapacity = Math.clamp(capacity, GameRoom.MIN_CAPACITY, GameRoom.MAX_CAPACITY);
        matchmakeLock.lock();
        try {
            // Check if this player is currently disconnected from an active match
            for (GameRoom room : activeRooms.values()) {
                if (room.isDisconnected(username)) {
                    boolean reconnected = room.reconnectPlayer(session, username);
                    if (reconnected) {
                        sessionRoomMap.put(session.getSessionId(), room);
                        return room;
                    }
                }
            }

            // Look for an existing public room waiting for players that doesn't already contain this username
            for (GameRoom room : activeRooms.values()) {
                if (room.getState() == RoomState.WAITING_FOR_PLAYERS && !room.isFull() && !room.isPrivate() && !room.hasPlayer(username)) {
                    boolean added = room.addPlayer(session, username);
                    if (added) {
                        sessionRoomMap.put(session.getSessionId(), room);
                        return room;
                    }
                }
            }

            // If no available room, spin up a new public room
            String roomId = "room-" + roomCounter.getAndIncrement();
            GameRoom newRoom = new GameRoom(roomId, roomId, RoomVisibility.PUBLIC, null, targetCapacity, new Random(), (sess) -> sessionRoomMap.remove(sess.getSessionId()));
            newRoom.setOnRoomFinished(r -> activeRooms.remove(r.getRoomId()));
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
     * Creates a new targeted public or private room.
     */
    public GameRoom createRoom(ClientSession session, String username, String roomName, RoomVisibility visibility, String accessCode, int capacity) {
        int targetCapacity = Math.clamp(capacity, GameRoom.MIN_CAPACITY, GameRoom.MAX_CAPACITY);
        matchmakeLock.lock();
        try {
            String roomId = "room-" + roomCounter.getAndIncrement();
            String effectiveName = (roomName != null && !roomName.isBlank()) ? roomName.trim() : roomId;
            GameRoom newRoom = new GameRoom(roomId, effectiveName, visibility, accessCode, targetCapacity, new Random(), (sess) -> sessionRoomMap.remove(sess.getSessionId()));
            newRoom.setOnRoomFinished(r -> activeRooms.remove(r.getRoomId()));
            newRoom.addPlayer(session, username, accessCode);

            activeRooms.put(roomId, newRoom);
            sessionRoomMap.put(session.getSessionId(), newRoom);

            roomExecutor.submit(newRoom);
            return newRoom;
        } finally {
            matchmakeLock.unlock();
        }
    }

    /**
     * Returns a list of currently open public rooms for the lobby browser.
     * Private rooms are strictly excluded.
     */
    public List<ServerEvent.RoomEntry> listPublicRooms() {
        List<ServerEvent.RoomEntry> entries = new ArrayList<>();
        for (GameRoom room : activeRooms.values()) {
            if (room.getVisibility() == RoomVisibility.PUBLIC && room.getState() != RoomState.FINISHED) {
                String status = room.getState() == RoomState.WAITING_FOR_PLAYERS ? "WAITING" : "IN_PROGRESS";
                entries.add(new ServerEvent.RoomEntry(
                        room.getRoomId(),
                        room.getRoomName(),
                        room.getPlayerCount(),
                        room.getMaxPlayers(),
                        status
                ));
            }
        }
        return entries;
    }

    public GameRoom joinRoom(ClientSession session, String username, String roomId) {
        return joinRoom(session, username, roomId, config.defaultRoomCapacity(), null);
    }

    public GameRoom joinRoom(ClientSession session, String username, String roomId, int capacity) {
        return joinRoom(session, username, roomId, capacity, null);
    }

    /**
     * Enrolls player in a targeted room by ID, verifying access code for private rooms.
     */
    public GameRoom joinRoom(ClientSession session, String username, String roomId, int capacity, String accessCode) {
        if (roomId == null || roomId.isBlank()) {
            return matchmake(session, username, capacity);
        }

        int targetCapacity = Math.clamp(capacity, GameRoom.MIN_CAPACITY, GameRoom.MAX_CAPACITY);
        matchmakeLock.lock();
        try {
            GameRoom room = activeRooms.get(roomId);
            if (room == null && accessCode == null) {
                for (GameRoom r : activeRooms.values()) {
                    if (roomId.equalsIgnoreCase(r.getAccessCode())) {
                        room = r;
                        accessCode = roomId;
                        break;
                    }
                }
            }
            if (room == null) {
                RoomVisibility vis = (accessCode != null && !accessCode.isBlank()) ? RoomVisibility.PRIVATE : RoomVisibility.PUBLIC;
                room = new GameRoom(roomId, roomId, vis, accessCode, targetCapacity, new Random(), (sess) -> sessionRoomMap.remove(sess.getSessionId()));
                room.setOnRoomFinished(r -> activeRooms.remove(r.getRoomId()));
                activeRooms.put(roomId, room);
                roomExecutor.submit(room);
            }

            if (room.isDisconnected(username)) {
                if (!room.validateAccess(accessCode)) {
                    session.sendEvent(new ServerEvent.ErrorEvent("INVALID_ACCESS_CODE: Room is private and requires valid access code"));
                    return room;
                }
                boolean reconnected = room.reconnectPlayer(session, username);
                if (reconnected) {
                    sessionRoomMap.put(session.getSessionId(), room);
                }
                return room;
            }

            if (room.isFull()) {
                session.sendEvent(new ServerEvent.ErrorEvent("Targeted room " + roomId + " is full"));
                return room;
            }

            if (!room.validateAccess(accessCode)) {
                session.sendEvent(new ServerEvent.ErrorEvent("INVALID_ACCESS_CODE: Room is private and requires valid access code"));
                return room;
            }

            if (room.hasPlayer(username)) {
                session.sendEvent(new ServerEvent.ErrorEvent("NAME_TAKEN: Player with name '" + username + "' already exists in this room"));
                return room;
            }

            boolean added = room.addPlayer(session, username, accessCode);
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

            if (room.getState() == RoomState.FINISHED || room.getPlayerCount() == 0 || room.getConnectedSessionCount() == 0) {
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
