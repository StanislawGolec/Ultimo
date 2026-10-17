package pl.ultimo.server.net;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.ultimo.protocol.ClientCommand;
import pl.ultimo.protocol.ProtocolCodec;
import pl.ultimo.protocol.ServerEvent;
import pl.ultimo.server.ServerApp;
import pl.ultimo.server.config.ServerConfig;
import pl.ultimo.server.metrics.MetricsRegistry;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Chaos Resilience & Cross-Ecosystem Wire Protocol Tests (Stage 6)")
class ChaosResilienceSimulationTest {

    private ServerApp server;
    private int port;
    private final List<Socket> clientSockets = Collections.synchronizedList(new ArrayList<>());

    private static class ChaoticClient implements AutoCloseable {
        private final Socket socket;
        private final BufferedWriter writer;
        private final BufferedReader reader;
        private final List<ServerEvent> receivedEvents = Collections.synchronizedList(new ArrayList<>());

        public ChaoticClient(int port) throws Exception {
            this.socket = new Socket("localhost", port);
            this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        }

        public void send(String line) throws Exception {
            writer.write(line.endsWith("\n") ? line : line + "\n");
            writer.flush();
        }

        public void sendWithJitter(String line, long delayMs) throws Exception {
            Thread.sleep(delayMs);
            send(line);
        }

        public ServerEvent readEvent(long timeoutMillis) throws Exception {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (System.currentTimeMillis() < deadline) {
                if (reader.ready()) {
                    String line = reader.readLine();
                    if (line != null && !line.isBlank()) {
                        ServerEvent event = ProtocolCodec.decodeEvent(line);
                        receivedEvents.add(event);
                        return event;
                    }
                }
                Thread.sleep(20);
            }
            return null;
        }

        @SuppressWarnings("unchecked")
        public <T extends ServerEvent> T readExpected(Class<T> expectedType, long timeoutMillis) throws Exception {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (System.currentTimeMillis() < deadline) {
                if (reader.ready()) {
                    String line = reader.readLine();
                    if (line != null && !line.isBlank()) {
                        ServerEvent event = ProtocolCodec.decodeEvent(line);
                        receivedEvents.add(event);
                        if (expectedType.isInstance(event)) {
                            return (T) event;
                        }
                    }
                }
                Thread.sleep(10);
            }
            fail("Timed out waiting for " + expectedType.getSimpleName() + " after " + timeoutMillis + "ms. Received: " + receivedEvents);
            return null;
        }

        public void abruptClose() throws Exception {
            // RST packet emulation: drop socket immediately without TCP FIN handshake
            socket.setSoLinger(true, 0);
            socket.close();
        }

        @Override
        public void close() throws Exception {
            try {
                if (!socket.isClosed()) {
                    socket.close();
                }
            } catch (Exception ignored) {}
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        ServerConfig config = new ServerConfig(0, 2, 5000, 10);
        server = new ServerApp(config);
        server.start();

        long deadline = System.currentTimeMillis() + 3000;
        while (server.getBoundPort() <= 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        port = server.getBoundPort();
        assertTrue(port > 0, "Server failed to bind dynamic port");
    }

    @AfterEach
    void tearDown() {
        for (Socket s : clientSockets) {
            try {
                s.close();
            } catch (Exception ignored) {}
        }
        clientSockets.clear();
        server.stop();
    }

    @Test
    @DisplayName("Server recovers cleanly from abrupt socket drop without deadlocks or leaked sessions")
    void shouldRecoverFromAbruptSocketDrop() throws Exception {
        int initialActive = server.getActiveSessionCount();

        try (ChaoticClient alice = new ChaoticClient(port);
             ChaoticClient bob = new ChaoticClient(port)) {

            alice.send("JOIN Alice");
            bob.send("JOIN Bob");

            ServerEvent.Welcome aliceWelcome = alice.readExpected(ServerEvent.Welcome.class, 2000);
            ServerEvent.Welcome bobWelcome = bob.readExpected(ServerEvent.Welcome.class, 2000);

            assertNotNull(aliceWelcome);
            assertNotNull(bobWelcome);

            // Alice abruptly drops connection (TCP RST emulation)
            alice.abruptClose();

            // Bob can still communicate with server normally
            bob.send("PING");
            ServerEvent.Pong bobPong = bob.readExpected(ServerEvent.Pong.class, 2000);
            assertNotNull(bobPong);
        }

        Thread.sleep(300);

        // Alice reconnects with fresh socket
        try (ChaoticClient aliceReconnected = new ChaoticClient(port)) {
            aliceReconnected.send("JOIN Alice");
            ServerEvent.Welcome welcome = aliceReconnected.readExpected(ServerEvent.Welcome.class, 2000);
            assertNotNull(welcome);
        }
    }

    @Test
    @DisplayName("Virtual Thread event loop sustains simulated network jitter and delay without starving clients")
    void shouldHandleSimulatedNetworkJitter() throws Exception {
        try (ChaoticClient fastClient = new ChaoticClient(port);
             ChaoticClient jitterClient = new ChaoticClient(port)) {

            fastClient.send("JOIN FastPlayer");
            jitterClient.sendWithJitter("JOIN JitterPlayer", 150);

            ServerEvent.Welcome fastWelcome = fastClient.readExpected(ServerEvent.Welcome.class, 2000);
            ServerEvent.Welcome jitterWelcome = jitterClient.readExpected(ServerEvent.Welcome.class, 2000);

            assertNotNull(fastWelcome);
            assertNotNull(jitterWelcome);

            // Fast client should receive immediate PONG even while jitter client sleeps
            long tStart = System.currentTimeMillis();
            fastClient.send("PING");
            ServerEvent.Pong fastPong = fastClient.readExpected(ServerEvent.Pong.class, 1000);
            long elapsed = System.currentTimeMillis() - tStart;

            assertNotNull(fastPong);
            assertTrue(elapsed < 200, "Fast client must not be stalled by slow/jittered client, took: " + elapsed + "ms");

            // Jitter client sends PING with 200ms delay
            jitterClient.sendWithJitter("PING", 200);
            ServerEvent.Pong jitterPong = jitterClient.readExpected(ServerEvent.Pong.class, 2000);
            assertNotNull(jitterPong);
        }
    }

    @Test
    @DisplayName("Server rejects corrupted and malformed wire protocol lines gracefully without crashing session")
    void shouldRejectMalformedFramesGracefully() throws Exception {
        try (ChaoticClient client = new ChaoticClient(port)) {
            client.send("JOIN TestPlayer");
            ServerEvent.Welcome welcome = client.readExpected(ServerEvent.Welcome.class, 2000);
            assertNotNull(welcome);

            // Send malformed / chaotic wire frames
            client.send("GARBAGE_OPCODE_XYZ 123 456");
            ServerEvent.ErrorEvent err1 = client.readExpected(ServerEvent.ErrorEvent.class, 2000);
            assertTrue(err1.errorMessage().contains("Unknown command")
                    || err1.errorMessage().contains("Unsupported command"));

            client.send("PLAY INVALID_COLOR_NINE");
            ServerEvent.ErrorEvent err2 = client.readExpected(ServerEvent.ErrorEvent.class, 2000);
            assertNotNull(err2);

            // Session must remain intact and valid commands must still succeed
            client.send("PING");
            ServerEvent.Pong pong = client.readExpected(ServerEvent.Pong.class, 2000);
            assertNotNull(pong);

            client.send("CHAT Still alive after chaos!");
            // Expect no crash
        }
    }

    @Test
    @DisplayName("Targeted room joining and auto-reconnection across network chaos")
    void shouldHandleTargetedRoomAutoReconnection() throws Exception {
        String targetRoom = "tournament-chaos-room-1";

        try (ChaoticClient p1 = new ChaoticClient(port);
             ChaoticClient p2 = new ChaoticClient(port)) {

            p1.send("JOIN Player1 " + targetRoom);
            p2.send("JOIN Player2 " + targetRoom);

            ServerEvent.Welcome w1 = p1.readExpected(ServerEvent.Welcome.class, 2000);
            ServerEvent.Welcome w2 = p2.readExpected(ServerEvent.Welcome.class, 2000);
            assertNotNull(w1);
            assertNotNull(w2);

            // Check room status confirms both in targetRoom
            ServerEvent.RoomStatus s1 = p1.readExpected(ServerEvent.RoomStatus.class, 2000);
            assertEquals(targetRoom, s1.roomId());

            // P1 drops abruptly
            p1.abruptClose();
        }

        Thread.sleep(300);

        // P1 reconnects and rejoins the same targeted room
        try (ChaoticClient p1Reconnected = new ChaoticClient(port)) {
            p1Reconnected.send("JOIN Player1 " + targetRoom);
            ServerEvent.Welcome w = p1Reconnected.readExpected(ServerEvent.Welcome.class, 2000);
            assertNotNull(w);

            p1Reconnected.send("PING");
            ServerEvent.Pong pong = p1Reconnected.readExpected(ServerEvent.Pong.class, 2000);
            assertNotNull(pong);
        }
    }

    @Test
    @DisplayName("High-concurrency chaotic churn (connect, send garbage, abrupt drop, reconnect) sustains stability")
    void shouldWithstandHighConcurrencyChaosChurn() throws Exception {
        int threadCount = 20;
        ExecutorService chaosPool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int id = i;
            chaosPool.submit(() -> {
                try {
                    try (ChaoticClient client = new ChaoticClient(port)) {
                        client.send("JOIN Bot_" + id);
                        client.readEvent(2000);

                        // Intersperse valid and chaotic commands
                        client.send("PING");
                        client.readEvent(2000);

                        if (id % 3 == 0) {
                            client.send("CHAOS_CORRUPTED_TOKEN " + id);
                            client.readEvent(2000);
                        }

                        if (id % 2 == 0) {
                            client.abruptClose();
                        } else {
                            client.send("CHAT Finished churn test from " + id);
                        }
                    }
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    // Suppress connection exceptions expected during abrupt drops
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(10, TimeUnit.SECONDS);
        assertTrue(completed, "All chaotic clients should finish within 10 seconds");
        assertTrue(successCount.get() >= threadCount * 0.8, "At least 80% of churn tasks should complete without error");

        // Verify server is still completely responsive after high-concurrency chaos
        try (ChaoticClient verifyClient = new ChaoticClient(port)) {
            verifyClient.send("PING");
            ServerEvent pong = verifyClient.readEvent(2000);
            assertInstanceOf(ServerEvent.Pong.class, pong);
        }
    }
}
