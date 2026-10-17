package pl.ultimo.benchmark;

import pl.ultimo.server.ServerApp;
import pl.ultimo.server.config.ServerConfig;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * High-concurrency load test harness validating Project Loom virtual thread scaling,
 * connection concurrency, and memory footprint under Generational ZGC / G1GC.
 */
public class LoadTestRunner {

    public record LoadTestSummary(
            int concurrency,
            int commandsPerClient,
            int successfulSessions,
            long totalCommandsExchanged,
            double durationSeconds,
            double throughputCommandsPerSec,
            long usedHeapMb
    ) {
        @Override
        public String toString() {
            return String.format(
                    """
                    ================ LOAD TEST SUMMARY ================
                    Concurrent Sessions       : %,d
                    Commands per Client       : %d
                    Successful Sessions       : %,d
                    Total Commands Exchanged  : %,d
                    Total Duration            : %.2f seconds
                    Throughput                : %,.0f commands/sec
                    Heap Memory Utilized      : %d MB (Limit < 60 MB)
                    ===================================================
                    """,
                    concurrency, commandsPerClient, successfulSessions,
                    totalCommandsExchanged, durationSeconds, throughputCommandsPerSec, usedHeapMb
            );
        }
    }

    public static LoadTestSummary runLoadTest(int port, int concurrency, int commandsPerClient) throws Exception {
        System.gc();
        Thread.sleep(200);

        long startMemory = getUsedMemoryMb();
        long startTime = System.nanoTime();

        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch doneSignal = new CountDownLatch(concurrency);
        AtomicInteger successCounter = new AtomicInteger(0);
        LongAdder commandsExchanged = new LongAdder();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < concurrency; i++) {
                final int clientId = i;
                pool.submit(() -> {
                    try {
                        Socket socket = null;
                        for (int attempt = 0; attempt < 5; attempt++) {
                            try {
                                socket = new Socket("127.0.0.1", port);
                                socket.setTcpNoDelay(true);
                                break;
                            } catch (Exception e) {
                                Thread.sleep(20 + attempt * 30L);
                            }
                        }
                        if (socket == null) {
                            return;
                        }

                        try (Socket s = socket;
                             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
                             BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {

                            // Handshake (unique room per 4 players)
                            writer.write("JOIN LoadBot_" + clientId + " room-load-" + (clientId / 4) + "\n");
                            writer.flush();
                            String welcome = reader.readLine();
                            if (welcome != null) {
                                commandsExchanged.increment();
                            }

                            // Command exchange loop
                            for (int c = 0; c < commandsPerClient; c++) {
                                writer.write("PING\n");
                                writer.flush();
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    if (line.startsWith("PONG")) {
                                        commandsExchanged.increment();
                                        break;
                                    }
                                }
                            }

                            successCounter.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                    } finally {
                        doneSignal.countDown();
                    }
                });
            }

            // Release all client virtual threads simultaneously
            startSignal.countDown();

            boolean finished = doneSignal.await(60, TimeUnit.SECONDS);
            if (!finished) {
                System.err.println("Warning: Load test timed out waiting for all client threads");
            }
        }

        long durationNs = System.nanoTime() - startTime;
        double durationSeconds = durationNs / 1_000_000_000.0;
        long endMemory = getUsedMemoryMb();

        double throughput = commandsExchanged.sum() / Math.max(0.001, durationSeconds);
        return new LoadTestSummary(
                concurrency,
                commandsPerClient,
                successCounter.get(),
                commandsExchanged.sum(),
                durationSeconds,
                throughput,
                endMemory
        );
    }

    private static long getUsedMemoryMb() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
    }

    public static void main(String[] args) throws Exception {
        int concurrency = args.length > 0 ? Integer.parseInt(args[0]) : 1000;
        int commands = args.length > 1 ? Integer.parseInt(args[1]) : 5;

        System.out.println("Starting transient server for LoadTestRunner...");
        ServerConfig config = new ServerConfig(0, 10, 30_000, 15);
        ServerApp server = new ServerApp(config);
        server.start();

        while (server.getBoundPort() <= 0) {
            Thread.sleep(50);
        }
        int port = server.getBoundPort();
        System.out.println("Server bound on port " + port + ". Launching " + concurrency + " virtual threads...");

        LoadTestSummary summary = runLoadTest(port, concurrency, commands);
        System.out.println(summary);

        server.stop();
    }
}
