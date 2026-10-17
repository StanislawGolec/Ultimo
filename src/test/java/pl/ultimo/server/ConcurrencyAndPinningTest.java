package pl.ultimo.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.ultimo.server.config.ServerConfig;
import pl.ultimo.server.metrics.MetricsRegistry;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Virtual Thread Pinning Elimination & Concurrency Hardening Tests")
class ConcurrencyAndPinningTest {

    private ServerApp serverApp;
    private int port;

    @BeforeEach
    void setUp() throws InterruptedException {
        MetricsRegistry.getInstance().reset();
    }

    @AfterEach
    void tearDown() {
        if (serverApp != null) {
            serverApp.stop();
        }
        MetricsRegistry.getInstance().reset();
    }

    private void startServer(int readTimeoutMillis) throws InterruptedException {
        ServerConfig config = new ServerConfig(0, 4, readTimeoutMillis, 30, 1);
        serverApp = new ServerApp(config);
        serverApp.start();

        int attempts = 0;
        while ((port = serverApp.getBoundPort()) <= 0 && attempts++ < 50) {
            Thread.sleep(20);
        }
        assertTrue(port > 0, "Server failed to bind to a random free port");
    }

    @Test
    @DisplayName("Dedicated outbound queue ensures slow network clients do not delay fast clients")
    void shouldNotBlockFastClientsWhenSlowClientHasLatency() throws Exception {
        startServer(60_000);

        // Connect 3 clients
        Socket fastSocket1 = new Socket("127.0.0.1", port);
        Socket fastSocket2 = new Socket("127.0.0.1", port);
        Socket slowSocket = new Socket("127.0.0.1", port);

        BufferedWriter fastOut1 = new BufferedWriter(new OutputStreamWriter(fastSocket1.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader fastIn1 = new BufferedReader(new InputStreamReader(fastSocket1.getInputStream(), StandardCharsets.UTF_8));

        BufferedWriter fastOut2 = new BufferedWriter(new OutputStreamWriter(fastSocket2.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader fastIn2 = new BufferedReader(new InputStreamReader(fastSocket2.getInputStream(), StandardCharsets.UTF_8));

        BufferedWriter slowOut = new BufferedWriter(new OutputStreamWriter(slowSocket.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader slowIn = new BufferedReader(new InputStreamReader(slowSocket.getInputStream(), StandardCharsets.UTF_8));

        // Join fast clients
        fastOut1.write("JOIN FastPlayer1\n");
        fastOut1.flush();
        fastIn1.readLine(); // WELCOME
        fastIn1.readLine(); // ROOM_STATUS

        fastOut2.write("JOIN FastPlayer2\n");
        fastOut2.flush();
        fastIn2.readLine(); // WELCOME
        fastIn2.readLine(); // ROOM_STATUS
        fastIn1.readLine(); // ROOM_STATUS (player 2 joined)

        // Join slow client
        slowOut.write("JOIN SlowPlayer\n");
        slowOut.flush();
        fastIn1.readLine(); // ROOM_STATUS (player 3 joined)
        fastIn2.readLine(); // ROOM_STATUS (player 3 joined)

        // Slow client does NOT read anything right away - simulates high latency / stalled consumer
        // Now FastPlayer1 sends a CHAT message to the room
        long startNanos = System.nanoTime();
        fastOut1.write("CHAT Hello room\n");
        fastOut1.flush();

        // FastPlayer2 should receive the chat message nearly instantaneously (< 200ms)
        // regardless of whether SlowPlayer is reading from their socket buffer
        String chatMsg = fastIn2.readLine();
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        assertNotNull(chatMsg);
        assertTrue(chatMsg.contains("CHAT_BROADCAST FastPlayer1 Hello room"),
                "Fast client did not receive expected chat, got: " + chatMsg);
        assertTrue(elapsedMillis < 300, "Fast client was delayed by slow client: " + elapsedMillis + "ms");

        // Now SlowPlayer finally reads their buffered frames
        String slowWelcome = slowIn.readLine();
        assertNotNull(slowWelcome);
        assertTrue(slowWelcome.startsWith("WELCOME"));

        fastSocket1.close();
        fastSocket2.close();
        slowSocket.close();
    }

    @Test
    @DisplayName("Idle socket watchdog reaps dead connections when heartbeat goes unacknowledged")
    void shouldActivelyReapIdleConnectionsWhenHeartbeatUnacknowledged() throws Exception {
        // Configure short idle timeout of 400ms for testing watchdog (checkInterval = 200ms)
        startServer(400);

        Socket idleSocket = new Socket("127.0.0.1", port);
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(idleSocket.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader reader = new BufferedReader(new InputStreamReader(idleSocket.getInputStream(), StandardCharsets.UTF_8));

        writer.write("JOIN IdlePlayer\n");
        writer.flush();

        String welcome = reader.readLine();
        assertNotNull(welcome);
        assertTrue(welcome.startsWith("WELCOME"));
        reader.readLine(); // ROOM_STATUS

        // Verify session is tracked in activeSessions
        assertEquals(1, serverApp.getActiveSessionCount(), "Client session was not tracked");

        // Client now goes completely silent without sending any commands or replying
        // Server will timeout after 200ms (sends PONG probe) and after another 200ms will close the connection
        long startTime = System.currentTimeMillis();
        boolean socketClosedByServer = false;

        try {
            String line;
            while ((line = reader.readLine()) != null) {
                // Client may observe PONG probe
            }
            socketClosedByServer = true;
        } catch (IOException e) {
            socketClosedByServer = true;
        }

        long elapsed = System.currentTimeMillis() - startTime;
        assertTrue(socketClosedByServer, "Server did not close the idle socket");
        assertTrue(elapsed >= 250, "Watchdog closed too early: " + elapsed + "ms");

        // Verify active session count dropped to 0
        int waitAttempts = 0;
        while (serverApp.getActiveSessionCount() > 0 && waitAttempts++ < 30) {
            Thread.sleep(30);
        }
        assertEquals(0, serverApp.getActiveSessionCount(), "Dead session was not removed from active sessions");

        idleSocket.close();
    }

    @Test
    @DisplayName("Server responds to PING immediately without requiring JOIN")
    void shouldRespondToClientPingsWithoutRequiringJoin() throws Exception {
        startServer(60_000);

        try (Socket socket = new Socket("127.0.0.1", port);
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {

            out.write("PING\n");
            out.flush();

            String response = in.readLine();
            assertNotNull(response);
            assertEquals("PONG", response.trim());
        }
    }

    @Test
    @DisplayName("Verify zero synchronized monitor locks in pl.ultimo.server package")
    void shouldHaveZeroSynchronizedKeywordsInServerPackage() throws IOException {
        Path serverPackagePath = Path.of("src", "main", "java", "pl", "ultimo", "server");
        assertTrue(Files.exists(serverPackagePath), "Server package directory must exist");

        List<String> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(serverPackagePath)) {
            paths.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".java"))
                 .forEach(path -> {
                     try {
                         List<String> lines = Files.readAllLines(path);
                         for (int i = 0; i < lines.size(); i++) {
                             String line = lines.get(i);
                             String trimmed = line.trim();
                             if (trimmed.startsWith("//") || trimmed.startsWith("*")) {
                                 continue;
                             }
                             if (line.contains("synchronized")) {
                                 violations.add(path.getFileName() + ":" + (i + 1) + " -> " + trimmed);
                             }
                         }
                     } catch (IOException e) {
                         throw new RuntimeException(e);
                     }
                 });
        }

        assertTrue(violations.isEmpty(),
                "Found synchronized keyword in server package (violates Virtual Thread carrier non-pinning requirement):\n"
                        + String.join("\n", violations));
    }
}
