package pl.ultimo.benchmark;

import pl.ultimo.core.model.Card;
import pl.ultimo.core.model.CardColor;
import pl.ultimo.core.model.CardValue;
import pl.ultimo.core.model.Player;
import pl.ultimo.protocol.ClientCommand;
import pl.ultimo.protocol.ProtocolCodec;
import pl.ultimo.protocol.ServerEvent;

import java.util.List;

/**
 * Microbenchmark harness for {@link ProtocolCodec} decoding and encoding throughput.
 * Evaluates parsing and serialization speed under high-throughput conditions.
 */
public class ProtocolCodecBenchmark {

    public record BenchmarkResult(String benchmarkName, long iterations, double opsPerSec, double nsPerOp) {
        @Override
        public String toString() {
            return String.format("%-32s : %,12.0f ops/sec (%.2f ns/op)", benchmarkName, opsPerSec, nsPerOp);
        }
    }

    public static BenchmarkResult benchmarkDecodeCommand(int warmupIters, int measureIters) throws Exception {
        Blackhole bh = new Blackhole();
        String[] samples = new String[] {
                "JOIN Alice room-101",
                "PLAY RED_7",
                "PLAY BLUE_DRAW2,GREEN_DRAW2",
                "DRAW",
                "PASS",
                "ULTIMO",
                "CATCH_ULTIMO Bob",
                "CHAT Good luck have fun everyone!"
        };

        // Warmup phase for C2 JIT compilation
        for (int i = 0; i < warmupIters; i++) {
            String line = samples[i % samples.length];
            ClientCommand cmd = ProtocolCodec.decodeCommand(line);
            bh.consume(cmd);
        }

        // Measurement phase
        long start = System.nanoTime();
        for (int i = 0; i < measureIters; i++) {
            String line = samples[i % samples.length];
            ClientCommand cmd = ProtocolCodec.decodeCommand(line);
            bh.consume(cmd);
        }
        long durationNs = System.nanoTime() - start;

        double opsPerSec = ((double) measureIters / durationNs) * 1_000_000_000.0;
        double nsPerOp = (double) durationNs / measureIters;
        return new BenchmarkResult("ProtocolCodec.decodeCommand", measureIters, opsPerSec, nsPerOp);
    }

    public static BenchmarkResult benchmarkEncodeEvent(int warmupIters, int measureIters) throws Exception {
        Blackhole bh = new Blackhole();
        List<Card> cards = List.of(
                new Card(CardColor.RED, CardValue.SEVEN),
                new Card(CardColor.BLUE, CardValue.DRAW_TWO),
                new Card(CardColor.WILD, CardValue.WILD_DRAW_FOUR)
        );
        ServerEvent[] events = new ServerEvent[] {
                new ServerEvent.TopCard(new Card(CardColor.RED, CardValue.SEVEN), CardColor.RED),
                new ServerEvent.HandUpdate(cards),
                new ServerEvent.TurnAnnounced("Player1"),
                new ServerEvent.RoomStatus("room-101", 2, 4, List.of("Alice", "Bob")),
                new ServerEvent.Pong()
        };

        // Warmup phase
        for (int i = 0; i < warmupIters; i++) {
            ServerEvent ev = events[i % events.length];
            String encoded = ProtocolCodec.encodeEvent(ev);
            bh.consume(encoded);
        }

        // Measurement phase
        long start = System.nanoTime();
        for (int i = 0; i < measureIters; i++) {
            ServerEvent ev = events[i % events.length];
            String encoded = ProtocolCodec.encodeEvent(ev);
            bh.consume(encoded);
        }
        long durationNs = System.nanoTime() - start;

        double opsPerSec = ((double) measureIters / durationNs) * 1_000_000_000.0;
        double nsPerOp = (double) durationNs / measureIters;
        return new BenchmarkResult("ProtocolCodec.encodeEvent", measureIters, opsPerSec, nsPerOp);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Running ProtocolCodec Microbenchmarks...");
        BenchmarkResult r1 = benchmarkDecodeCommand(100_000, 500_000);
        System.out.println(r1);

        BenchmarkResult r2 = benchmarkEncodeEvent(100_000, 500_000);
        System.out.println(r2);
    }
}
