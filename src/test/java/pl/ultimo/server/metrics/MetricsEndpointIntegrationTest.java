package pl.ultimo.server.metrics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.ultimo.server.ServerApp;
import pl.ultimo.server.config.ServerConfig;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Prometheus Metrics & Health Probe Integration Tests")
class MetricsEndpointIntegrationTest {

    private ServerApp serverApp;
    private int port;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() throws InterruptedException {
        MetricsRegistry.getInstance().reset();
        ServerConfig config = new ServerConfig(0, 2, 5000, 30);
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
    @DisplayName("Should respond 200 OK with Prometheus exposition format on GET /metrics")
    void testGetMetricsEndpoint() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/metrics"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("text/plain; version=0.0.4; charset=utf-8",
                response.headers().firstValue("Content-Type").orElse(""));

        String body = response.body();
        assertNotNull(body);
        assertTrue(body.contains("# HELP ultimo_active_connections"), "Must declare active_connections");
        assertTrue(body.contains("# TYPE ultimo_active_connections gauge"));
        assertTrue(body.contains("# HELP ultimo_active_rooms"));
        assertTrue(body.contains("# HELP ultimo_commands_processed_total"));
        assertTrue(body.contains("# HELP ultimo_command_processing_duration_seconds"));
        assertTrue(body.contains("# HELP ultimo_jvm_memory_used_bytes"));
        assertTrue(body.contains("# HELP ultimo_jvm_threads_current"));
    }

    @Test
    @DisplayName("Should respond 200 OK on GET /healthz and GET /ready")
    void testHealthAndReadyProbes() throws Exception {
        HttpRequest livenessReq = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/healthz"))
                .GET()
                .build();
        HttpResponse<String> livenessRes = httpClient.send(livenessReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, livenessRes.statusCode());
        assertEquals("OK", livenessRes.body().trim());

        HttpRequest readinessReq = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/ready"))
                .GET()
                .build();
        HttpResponse<String> readinessRes = httpClient.send(readinessReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, readinessRes.statusCode());
        assertEquals("OK", readinessRes.body().trim());
    }

    @Test
    @DisplayName("Should reflect real gameplay commands in Prometheus metrics scrape")
    void testMetricsReflectGameplay() throws Exception {
        // Connect a TCP socket and issue JOIN Alice
        try (Socket socket = new Socket("localhost", port);
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {

            writer.write("JOIN Alice\n");
            writer.flush();

            String welcome = reader.readLine();
            assertNotNull(welcome);
            assertTrue(welcome.startsWith("WELCOME"));

            // Scrape /metrics via HTTP
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/metrics"))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());

            String body = response.body();
            assertTrue(body.contains("ultimo_commands_processed_total{type=\"JOIN\"} 1"),
                    "Should have recorded 1 JOIN command in Prometheus counter");
            assertTrue(body.contains("ultimo_events_emitted_total{type=\"WELCOME\"} 1"),
                    "Should have recorded 1 WELCOME event in Prometheus counter");
            assertTrue(body.contains("ultimo_command_processing_duration_seconds_count 1"),
                    "Should have recorded 1 observation in command duration histogram");
        }
    }
}
