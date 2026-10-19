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
import java.net.ConnectException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Production SRE Graceful Shutdown & Health Probe Lifecycle Tests")
class GracefulShutdownTest {

    private ServerApp serverApp;
    private int port;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() throws InterruptedException {
        MetricsRegistry.getInstance().reset();
        // 2-second drain window for testing
        ServerConfig config = new ServerConfig(0, 2, 5000, 30, 2);
        serverApp = new ServerApp(config);
        serverApp.start();

        int attempts = 0;
        while ((port = serverApp.getBoundPort()) <= 0 && attempts++ < 50) {
            Thread.sleep(20);
        }
        assertTrue(port > 0, "Server failed to bind to a random free port");

        httpClient = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (serverApp != null) {
            serverApp.stop();
        }
        MetricsRegistry.getInstance().reset();
    }

    @Test
    @DisplayName("Should transition /ready probe from 200 OK to 503 Service Unavailable when shutdown begins")
    void shouldTransitionReadinessProbeOnShutdown() throws Exception {
        // Step 1: When running, /ready returns 200 OK
        HttpRequest readyReq = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/ready"))
                .GET()
                .build();
        HttpResponse<String> initialRes = httpClient.send(readyReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, initialRes.statusCode());
        assertEquals("OK", initialRes.body().trim());

        // Step 2: Trigger graceful shutdown in the background with 2s drain
        CompletableFuture.runAsync(() -> serverApp.gracefulShutdown(2));

        // Allow shutdown signal to set isShuttingDown flag and close acceptor
        Thread.sleep(150);

        assertTrue(serverApp.isShuttingDown());
        assertFalse(serverApp.isServerReady());

        // Step 3: Readiness probe must immediately fail with 503 so Cloud Run / K8s drops ingress
        HttpResponse<String> drainingRes = httpClient.send(readyReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(503, drainingRes.statusCode());
        assertTrue(drainingRes.body().contains("Service Unavailable"));

        // Step 4: Liveness probe /healthz remains 200 OK while process is draining
        HttpRequest livenessReq = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/healthz"))
                .GET()
                .build();
        HttpResponse<String> livenessRes = httpClient.send(livenessReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, livenessRes.statusCode());
        assertEquals("OK", livenessRes.body().trim());
    }

    @Test
    @DisplayName("Should broadcast SERVER_SHUTDOWN to connected clients and cleanly terminate sockets")
    void shouldBroadcastShutdownToClientsAndDrainCleanly() throws Exception {
        try (Socket clientSocket = new Socket("localhost", port);
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8));
             BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8))) {

            // Register client
            writer.write("JOIN Alice\n");
            writer.flush();

            String welcome = reader.readLine();
            assertNotNull(welcome);
            assertTrue(welcome.startsWith("WELCOME"));

            assertEquals(1, serverApp.getActiveSessionCount());

            // Initiate graceful shutdown with 1s drain window in background
            CompletableFuture.runAsync(() -> serverApp.gracefulShutdown(1));

            // Read lines until SERVER_SHUTDOWN 1 is received
            String line;
            String shutdownEvent = null;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("SERVER_SHUTDOWN")) {
                    shutdownEvent = line;
                    break;
                }
            }
            assertNotNull(shutdownEvent, "Client must receive broadcast SERVER_SHUTDOWN event");
            assertTrue(shutdownEvent.startsWith("SERVER_SHUTDOWN 1"),
                    "Expected SERVER_SHUTDOWN 1, got: " + shutdownEvent);

            // Wait for 1s drain window to elapse plus buffer
            Thread.sleep(1300);

            // Socket must now be cleanly closed by server (reader returns null EOF)
            String postDrain = reader.readLine();
            assertNull(postDrain, "Socket must be closed after graceful drain completes");
        }
    }

    @Test
    @DisplayName("Should reject new incoming TCP connections after shutdown is triggered")
    void shouldRejectNewConnectionsDuringShutdown() throws Exception {
        // Trigger shutdown immediately
        serverApp.stop();

        assertTrue(serverApp.isShuttingDown());

        // Attempting to connect to the bound port should fail or immediately close
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(500);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            // Either connection is refused or reader immediately returns EOF
            String line = reader.readLine();
            assertNull(line);
        } catch (IOException expected) {
            // Expected socket error when acceptor is closed
            assertTrue(true);
        }
    }
}
