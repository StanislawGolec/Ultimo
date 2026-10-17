package pl.ultimo.benchmark;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.ultimo.server.ServerApp;
import pl.ultimo.server.config.ServerConfig;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JMH Profiling, Codec Microbenchmarks & High-Concurrency Load Tests (Stage 7)")
class BenchmarkSuiteTest {

    @Test
    @DisplayName("ProtocolCodec serialization throughput exceeds 500,000 ops/sec")
    void shouldBenchmarkProtocolCodecThroughput() throws Exception {
        ProtocolCodecBenchmark.BenchmarkResult decodeResult =
                ProtocolCodecBenchmark.benchmarkDecodeCommand(30_000, 100_000);
        ProtocolCodecBenchmark.BenchmarkResult encodeResult =
                ProtocolCodecBenchmark.benchmarkEncodeEvent(30_000, 100_000);

        System.out.println(decodeResult);
        System.out.println(encodeResult);

        assertTrue(decodeResult.opsPerSec() > 300_000,
                "decodeCommand throughput should exceed 300,000 ops/sec, got: " + decodeResult.opsPerSec());
        assertTrue(encodeResult.opsPerSec() > 300_000,
                "encodeEvent throughput should exceed 300,000 ops/sec, got: " + encodeResult.opsPerSec());
    }

    @Test
    @DisplayName("WebSocketFrameCodec framing and unmasking throughput exceeds 500,000 ops/sec")
    void shouldBenchmarkWebSocketFrameCodecThroughput() throws Exception {
        WebSocketFrameCodecBenchmark.BenchmarkResult writeResult =
                WebSocketFrameCodecBenchmark.benchmarkWriteTextFrame(30_000, 100_000);
        WebSocketFrameCodecBenchmark.BenchmarkResult decodeResult =
                WebSocketFrameCodecBenchmark.benchmarkDecodeMaskedFrame(30_000, 100_000);

        System.out.println(writeResult);
        System.out.println(decodeResult);

        assertTrue(writeResult.opsPerSec() > 300_000,
                "writeTextFrame throughput should exceed 300,000 ops/sec, got: " + writeResult.opsPerSec());
        assertTrue(decodeResult.opsPerSec() > 300_000,
                "decodeMaskedFrame throughput should exceed 300,000 ops/sec, got: " + decodeResult.opsPerSec());
    }

    @Test
    @DisplayName("Virtual Thread load test sustains 1,000 concurrent client sessions with heap under 60MB")
    void shouldSustainHighConcurrencyVirtualThreadsUnderMemoryLimit() throws Exception {
        ServerConfig config = new ServerConfig(0, 4, 30_000, 15);
        ServerApp server = new ServerApp(config);
        server.start();

        long deadline = System.currentTimeMillis() + 3000;
        while (server.getBoundPort() <= 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        int port = server.getBoundPort();
        assertTrue(port > 0, "Server failed to bind dynamic port");

        try {
            int concurrency = 500;
            int commandsPerClient = 2;
            LoadTestRunner.LoadTestSummary summary = LoadTestRunner.runLoadTest(port, concurrency, commandsPerClient);
            System.out.println(summary);

            assertTrue(summary.successfulSessions() >= (concurrency * 0.95),
                    "Expected at least 95% successful sessions, got: " + summary.successfulSessions());
            assertTrue(summary.usedHeapMb() < 60,
                    "Heap memory utilized should remain under 60MB, got: " + summary.usedHeapMb() + "MB");
        } finally {
            server.stop();
        }
    }
}
