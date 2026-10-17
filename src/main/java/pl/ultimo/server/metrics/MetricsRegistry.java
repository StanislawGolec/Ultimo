package pl.ultimo.server.metrics;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * Thread-safe, zero-dependency in-memory metrics registry supporting Prometheus 
 * text exposition format (version 0.0.4).
 *
 * Implemented using lock-free standard Java concurrency primitives (LongAdder, DoubleAdder, AtomicLong)
 * to guarantee zero contention on high-throughput game loops.
 */
public class MetricsRegistry {

    public static final double[] BUCKET_THRESHOLDS = {0.001, 0.005, 0.01, 0.05, 0.1};

    private static final MetricsRegistry INSTANCE = new MetricsRegistry();

    private final AtomicLong activeTcpConnections = new AtomicLong(0);
    private final AtomicLong activeWebSocketConnections = new AtomicLong(0);
    private final AtomicLong fallbackActiveRooms = new AtomicLong(0);
    private volatile Supplier<Integer> activeRoomsSupplier = null;

    private final Map<String, LongAdder> commandsProcessed = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> eventsEmitted = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> errorsTotal = new ConcurrentHashMap<>();

    private final LongAdder[] latencyBuckets = new LongAdder[BUCKET_THRESHOLDS.length];
    private final LongAdder latencyCount = new LongAdder();
    private final DoubleAdder latencySum = new DoubleAdder();

    private final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
    private final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

    public MetricsRegistry() {
        for (int i = 0; i < BUCKET_THRESHOLDS.length; i++) {
            latencyBuckets[i] = new LongAdder();
        }
    }

    public static MetricsRegistry getInstance() {
        return INSTANCE;
    }

    public void recordConnectionOpened(boolean isWebSocket) {
        if (isWebSocket) {
            activeWebSocketConnections.incrementAndGet();
        } else {
            activeTcpConnections.incrementAndGet();
        }
    }

    public void recordWebSocketUpgrade() {
        activeTcpConnections.updateAndGet(val -> Math.max(0, val - 1));
        activeWebSocketConnections.incrementAndGet();
    }

    public void recordConnectionClosed(boolean isWebSocket) {
        if (isWebSocket) {
            activeWebSocketConnections.updateAndGet(val -> Math.max(0, val - 1));
        } else {
            activeTcpConnections.updateAndGet(val -> Math.max(0, val - 1));
        }
    }

    public void setActiveRoomsSupplier(Supplier<Integer> supplier) {
        this.activeRoomsSupplier = supplier;
    }

    public void setActiveRooms(long count) {
        fallbackActiveRooms.set(Math.max(0, count));
    }

    public long getActiveRooms() {
        Supplier<Integer> supplier = activeRoomsSupplier;
        if (supplier != null) {
            try {
                return Math.max(0, supplier.get());
            } catch (Exception e) {
                return fallbackActiveRooms.get();
            }
        }
        return fallbackActiveRooms.get();
    }

    public void recordCommandProcessed(String commandType, double durationSeconds) {
        String key = (commandType != null && !commandType.isBlank()) ? commandType.toUpperCase(Locale.ROOT) : "UNKNOWN";
        commandsProcessed.computeIfAbsent(key, k -> new LongAdder()).increment();

        double safeDuration = Math.max(0.0, durationSeconds);
        latencyCount.increment();
        latencySum.add(safeDuration);

        for (int i = 0; i < BUCKET_THRESHOLDS.length; i++) {
            if (safeDuration <= BUCKET_THRESHOLDS[i]) {
                latencyBuckets[i].increment();
            }
        }
    }

    public void recordEventEmitted(String eventType) {
        String key = (eventType != null && !eventType.isBlank()) ? eventType.toUpperCase(Locale.ROOT) : "UNKNOWN";
        eventsEmitted.computeIfAbsent(key, k -> new LongAdder()).increment();
    }

    public void recordError(String errorCode) {
        String key = (errorCode != null && !errorCode.isBlank()) ? errorCode.toUpperCase(Locale.ROOT) : "UNKNOWN";
        errorsTotal.computeIfAbsent(key, k -> new LongAdder()).increment();
    }

    public long getActiveTcpConnections() {
        return activeTcpConnections.get();
    }

    public long getActiveWebSocketConnections() {
        return activeWebSocketConnections.get();
    }

    public long getCommandCount(String type) {
        LongAdder adder = commandsProcessed.get(type.toUpperCase(Locale.ROOT));
        return adder != null ? adder.sum() : 0;
    }

    public long getEventCount(String type) {
        LongAdder adder = eventsEmitted.get(type.toUpperCase(Locale.ROOT));
        return adder != null ? adder.sum() : 0;
    }

    public long getErrorCount(String errorCode) {
        LongAdder adder = errorsTotal.get(errorCode.toUpperCase(Locale.ROOT));
        return adder != null ? adder.sum() : 0;
    }

    public long getLatencyBucketCount(int index) {
        if (index >= 0 && index < latencyBuckets.length) {
            return latencyBuckets[index].sum();
        }
        return 0;
    }

    public long getLatencyTotalCount() {
        return latencyCount.sum();
    }

    public double getLatencyTotalSum() {
        return latencySum.sum();
    }

    public void reset() {
        activeTcpConnections.set(0);
        activeWebSocketConnections.set(0);
        fallbackActiveRooms.set(0);
        activeRoomsSupplier = null;

        commandsProcessed.clear();
        eventsEmitted.clear();
        errorsTotal.clear();

        for (LongAdder bucket : latencyBuckets) {
            bucket.reset();
        }
        latencyCount.reset();
        latencySum.reset();
    }

    /**
     * Serializes all gathered metrics into the standard Prometheus Text Exposition Format (version 0.0.4).
     */
    public String toPrometheusText() {
        StringBuilder sb = new StringBuilder(2048);

        // 1. ultimo_active_connections
        sb.append("# HELP ultimo_active_connections Current active client connections by transport type.\n");
        sb.append("# TYPE ultimo_active_connections gauge\n");
        sb.append("ultimo_active_connections{transport=\"tcp\"} ").append(activeTcpConnections.get()).append("\n");
        sb.append("ultimo_active_connections{transport=\"websocket\"} ").append(activeWebSocketConnections.get()).append("\n\n");

        // 2. ultimo_active_rooms
        sb.append("# HELP ultimo_active_rooms Current number of active game rooms.\n");
        sb.append("# TYPE ultimo_active_rooms gauge\n");
        sb.append("ultimo_active_rooms ").append(getActiveRooms()).append("\n\n");

        // 3. ultimo_commands_processed_total
        sb.append("# HELP ultimo_commands_processed_total Total number of client commands processed.\n");
        sb.append("# TYPE ultimo_commands_processed_total counter\n");
        if (commandsProcessed.isEmpty()) {
            sb.append("ultimo_commands_processed_total{type=\"NONE\"} 0\n");
        } else {
            commandsProcessed.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> sb.append("ultimo_commands_processed_total{type=\"")
                            .append(escapeLabelValue(e.getKey()))
                            .append("\"} ")
                            .append(e.getValue().sum())
                            .append("\n"));
        }
        sb.append("\n");

        // 4. ultimo_command_processing_duration_seconds
        sb.append("# HELP ultimo_command_processing_duration_seconds Latency of command processing in seconds.\n");
        sb.append("# TYPE ultimo_command_processing_duration_seconds histogram\n");
        for (int i = 0; i < BUCKET_THRESHOLDS.length; i++) {
            sb.append("ultimo_command_processing_duration_seconds_bucket{le=\"")
                    .append(formatBucketThreshold(BUCKET_THRESHOLDS[i]))
                    .append("\"} ")
                    .append(latencyBuckets[i].sum())
                    .append("\n");
        }
        sb.append("ultimo_command_processing_duration_seconds_bucket{le=\"+Inf\"} ")
                .append(latencyCount.sum())
                .append("\n");
        sb.append("ultimo_command_processing_duration_seconds_sum ")
                .append(String.format(Locale.ROOT, "%.6f", latencySum.sum()))
                .append("\n");
        sb.append("ultimo_command_processing_duration_seconds_count ")
                .append(latencyCount.sum())
                .append("\n\n");

        // 5. ultimo_events_emitted_total
        if (!eventsEmitted.isEmpty()) {
            sb.append("# HELP ultimo_events_emitted_total Total number of server events emitted to clients.\n");
            sb.append("# TYPE ultimo_events_emitted_total counter\n");
            eventsEmitted.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> sb.append("ultimo_events_emitted_total{type=\"")
                            .append(escapeLabelValue(e.getKey()))
                            .append("\"} ")
                            .append(e.getValue().sum())
                            .append("\n"));
            sb.append("\n");
        }

        // 6. ultimo_errors_total
        if (!errorsTotal.isEmpty()) {
            sb.append("# HELP ultimo_errors_total Total number of errors encountered.\n");
            sb.append("# TYPE ultimo_errors_total counter\n");
            errorsTotal.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> sb.append("ultimo_errors_total{code=\"")
                            .append(escapeLabelValue(e.getKey()))
                            .append("\"} ")
                            .append(e.getValue().sum())
                            .append("\n"));
            sb.append("\n");
        }

        // 7. ultimo_jvm_memory_used_bytes
        long heapUsed = memoryMXBean.getHeapMemoryUsage().getUsed();
        sb.append("# HELP ultimo_jvm_memory_used_bytes Current JVM heap memory used in bytes.\n");
        sb.append("# TYPE ultimo_jvm_memory_used_bytes gauge\n");
        sb.append("ultimo_jvm_memory_used_bytes ").append(heapUsed).append("\n\n");

        // 8. ultimo_jvm_threads_current
        int threadCount = threadMXBean.getThreadCount();
        sb.append("# HELP ultimo_jvm_threads_current Current number of live JVM threads.\n");
        sb.append("# TYPE ultimo_jvm_threads_current gauge\n");
        sb.append("ultimo_jvm_threads_current ").append(threadCount).append("\n");

        return sb.toString();
    }

    private static String formatBucketThreshold(double val) {
        if (val == (long) val) {
            return String.format(Locale.ROOT, "%d", (long) val);
        }
        return String.valueOf(val);
    }

    private static String escapeLabelValue(String val) {
        return val.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }
}
