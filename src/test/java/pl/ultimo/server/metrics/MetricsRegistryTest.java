package pl.ultimo.server.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MetricsRegistry Unit & Concurrency Tests")
class MetricsRegistryTest {

    private MetricsRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new MetricsRegistry();
    }

    @Test
    @DisplayName("Should initialize gauges with default zero values")
    void testInitialGauges() {
        assertEquals(0, registry.getActiveTcpConnections());
        assertEquals(0, registry.getActiveWebSocketConnections());
        assertEquals(0, registry.getActiveRooms());
        assertEquals(0, registry.getLatencyTotalCount());
        assertEquals(0.0, registry.getLatencyTotalSum(), 0.0001);
    }

    @Test
    @DisplayName("Should track connection open, upgrade to WebSocket, and close")
    void testConnectionLifecycle() {
        registry.recordConnectionOpened(false);
        registry.recordConnectionOpened(false);
        assertEquals(2, registry.getActiveTcpConnections());
        assertEquals(0, registry.getActiveWebSocketConnections());

        registry.recordWebSocketUpgrade();
        assertEquals(1, registry.getActiveTcpConnections());
        assertEquals(1, registry.getActiveWebSocketConnections());

        registry.recordConnectionClosed(true);
        assertEquals(1, registry.getActiveTcpConnections());
        assertEquals(0, registry.getActiveWebSocketConnections());

        registry.recordConnectionClosed(false);
        assertEquals(0, registry.getActiveTcpConnections());
        assertEquals(0, registry.getActiveWebSocketConnections());

        // Guard against negative counts
        registry.recordConnectionClosed(false);
        assertEquals(0, registry.getActiveTcpConnections());
    }

    @Test
    @DisplayName("Should track active rooms via dynamic supplier and fallback")
    void testActiveRoomsSupplier() {
        registry.setActiveRooms(5);
        assertEquals(5, registry.getActiveRooms());

        List<String> mockRooms = new ArrayList<>(List.of("room1", "room2"));
        registry.setActiveRoomsSupplier(mockRooms::size);
        assertEquals(2, registry.getActiveRooms());

        mockRooms.add("room3");
        assertEquals(3, registry.getActiveRooms());
    }

    @Test
    @DisplayName("Should accumulate cumulative histogram buckets correctly")
    void testHistogramCumulativeBuckets() {
        // Record 1 latency of 0.0005s (should fall in <=0.001, <=0.005, <=0.01, <=0.05, <=0.1, +Inf)
        registry.recordCommandProcessed("PLAY", 0.0005);

        // Record 1 latency of 0.003s (should fall in <=0.005, <=0.01, <=0.05, <=0.1, +Inf)
        registry.recordCommandProcessed("PLAY", 0.003);

        // Record 1 latency of 0.02s (should fall in <=0.05, <=0.1, +Inf)
        registry.recordCommandProcessed("DRAW", 0.02);

        // Record 1 latency of 0.5s (should fall ONLY in +Inf)
        registry.recordCommandProcessed("PASS", 0.5);

        assertEquals(4, registry.getLatencyTotalCount());
        assertEquals(0.5235, registry.getLatencyTotalSum(), 0.0001);

        // Buckets: [0.001, 0.005, 0.01, 0.05, 0.1]
        assertEquals(1, registry.getLatencyBucketCount(0)); // <= 0.001 (0.0005)
        assertEquals(2, registry.getLatencyBucketCount(1)); // <= 0.005 (0.0005, 0.003)
        assertEquals(2, registry.getLatencyBucketCount(2)); // <= 0.01 (0.0005, 0.003)
        assertEquals(3, registry.getLatencyBucketCount(3)); // <= 0.05 (0.0005, 0.003, 0.02)
        assertEquals(3, registry.getLatencyBucketCount(4)); // <= 0.1 (0.0005, 0.003, 0.02)

        assertEquals(2, registry.getCommandCount("PLAY"));
        assertEquals(1, registry.getCommandCount("DRAW"));
        assertEquals(1, registry.getCommandCount("PASS"));
    }

    @Test
    @DisplayName("Should track event emitted and error counts")
    void testEventsAndErrors() {
        registry.recordEventEmitted("WELCOME");
        registry.recordEventEmitted("WELCOME");
        registry.recordEventEmitted("TOP_CARD");
        assertEquals(2, registry.getEventCount("WELCOME"));
        assertEquals(1, registry.getEventCount("TOP_CARD"));
        assertEquals(0, registry.getEventCount("GAME_OVER"));

        registry.recordError("PROTOCOL_ERROR");
        registry.recordError("INVALID_MOVE");
        assertEquals(1, registry.getErrorCount("PROTOCOL_ERROR"));
        assertEquals(1, registry.getErrorCount("INVALID_MOVE"));
    }

    @Test
    @DisplayName("Should export valid Prometheus Text Exposition 0.0.4 format")
    void testToPrometheusText() {
        registry.recordConnectionOpened(false);
        registry.recordConnectionOpened(true);
        registry.setActiveRooms(2);
        registry.recordCommandProcessed("JOIN", 0.002);
        registry.recordEventEmitted("WELCOME");
        registry.recordError("PROTOCOL_ERROR");

        String exposition = registry.toPrometheusText();
        assertNotNull(exposition);

        // Assert SRE required metrics are present
        assertTrue(exposition.contains("# HELP ultimo_active_connections"), "Must declare active_connections HELP");
        assertTrue(exposition.contains("# TYPE ultimo_active_connections gauge"), "Must declare active_connections TYPE");
        assertTrue(exposition.contains("ultimo_active_connections{transport=\"tcp\"} 1"));
        assertTrue(exposition.contains("ultimo_active_connections{transport=\"websocket\"} 1"));

        assertTrue(exposition.contains("# HELP ultimo_active_rooms"), "Must declare active_rooms HELP");
        assertTrue(exposition.contains("ultimo_active_rooms 2"));

        assertTrue(exposition.contains("# HELP ultimo_commands_processed_total"), "Must declare commands_processed HELP");
        assertTrue(exposition.contains("ultimo_commands_processed_total{type=\"JOIN\"} 1"));

        assertTrue(exposition.contains("# HELP ultimo_command_processing_duration_seconds"), "Must declare duration HELP");
        assertTrue(exposition.contains("ultimo_command_processing_duration_seconds_bucket{le=\"0.005\"} 1"));
        assertTrue(exposition.contains("ultimo_command_processing_duration_seconds_bucket{le=\"+Inf\"} 1"));
        assertTrue(exposition.contains("ultimo_command_processing_duration_seconds_count 1"));

        assertTrue(exposition.contains("ultimo_events_emitted_total{type=\"WELCOME\"} 1"));
        assertTrue(exposition.contains("ultimo_errors_total{code=\"PROTOCOL_ERROR\"} 1"));

        assertTrue(exposition.contains("# HELP ultimo_jvm_memory_used_bytes"), "Must include JVM memory HELP");
        assertTrue(exposition.contains("ultimo_jvm_memory_used_bytes "));
        assertTrue(exposition.contains("# HELP ultimo_jvm_threads_current"), "Must include JVM threads HELP");
        assertTrue(exposition.contains("ultimo_jvm_threads_current "));

        // Prometheus requires text to terminate with newline
        assertTrue(exposition.endsWith("\n"), "Prometheus text must terminate with trailing newline");
    }

    @Test
    @DisplayName("Should handle highly concurrent metric updates without data race")
    void testConcurrency() throws Exception {
        int threads = 20;
        int iterations = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                for (int j = 0; j < iterations; j++) {
                    registry.recordConnectionOpened(false);
                    registry.recordWebSocketUpgrade();
                    registry.recordCommandProcessed("PLAY", 0.002);
                    registry.recordEventEmitted("HAND_UPDATE");
                    registry.recordConnectionClosed(true);
                }
                return null;
            });
        }

        List<Future<Void>> futures = executor.invokeAll(tasks);
        for (Future<Void> future : futures) {
            future.get();
        }
        executor.shutdown();

        assertEquals(0, registry.getActiveTcpConnections());
        assertEquals(0, registry.getActiveWebSocketConnections());
        assertEquals(threads * iterations, registry.getCommandCount("PLAY"));
        assertEquals(threads * iterations, registry.getEventCount("HAND_UPDATE"));
        assertEquals(threads * iterations, registry.getLatencyTotalCount());
    }
}
