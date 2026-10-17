package pl.ultimo.benchmark;

import pl.ultimo.server.net.WebSocketFrameCodec;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Microbenchmark harness for {@link WebSocketFrameCodec} encoding, decoding,
 * and bitwise payload unmasking.
 */
public class WebSocketFrameCodecBenchmark {

    public record BenchmarkResult(String benchmarkName, long iterations, double opsPerSec, double nsPerOp) {
        @Override
        public String toString() {
            return String.format("%-32s : %,12.0f ops/sec (%.2f ns/op)", benchmarkName, opsPerSec, nsPerOp);
        }
    }

    private static class ReusableOutputStream extends OutputStream {
        private final byte[] buf;
        private int count;

        public ReusableOutputStream(int capacity) {
            this.buf = new byte[capacity];
        }

        @Override
        public void write(int b) {
            if (count < buf.length) {
                buf[count++] = (byte) b;
            }
        }

        @Override
        public void write(byte[] b, int off, int len) {
            int toCopy = Math.min(len, buf.length - count);
            System.arraycopy(b, off, buf, count, toCopy);
            count += toCopy;
        }

        public void reset() {
            count = 0;
        }

        public int getCount() {
            return count;
        }
    }

    public static BenchmarkResult benchmarkWriteTextFrame(int warmupIters, int measureIters) throws IOException {
        Blackhole bh = new Blackhole();
        ReusableOutputStream out = new ReusableOutputStream(1024);
        String[] payloads = new String[] {
                "PONG",
                "TOP_CARD RED_7 RED",
                "HAND_UPDATE RED_7,BLUE_DRAW2,GREEN_5",
                "TURN_ANNOUNCED Player1 15000",
                "ROOM_STATUS room-101 Player1,Player2 PLAYING"
        };

        // Warmup
        for (int i = 0; i < warmupIters; i++) {
            out.reset();
            String payload = payloads[i % payloads.length];
            WebSocketFrameCodec.writeTextFrame(out, payload);
            bh.consume(out.getCount());
        }

        // Measure
        long start = System.nanoTime();
        for (int i = 0; i < measureIters; i++) {
            out.reset();
            String payload = payloads[i % payloads.length];
            WebSocketFrameCodec.writeTextFrame(out, payload);
            bh.consume(out.getCount());
        }
        long durationNs = System.nanoTime() - start;

        double opsPerSec = ((double) measureIters / durationNs) * 1_000_000_000.0;
        double nsPerOp = (double) durationNs / measureIters;
        return new BenchmarkResult("WebSocketFrameCodec.writeTextFrame", measureIters, opsPerSec, nsPerOp);
    }

    public static BenchmarkResult benchmarkDecodeMaskedFrame(int warmupIters, int measureIters) throws IOException {
        Blackhole bh = new Blackhole();

        // Prepare pre-masked client frame for "PLAY RED_7\n"
        byte[] payloadBytes = "PLAY RED_7\n".getBytes(StandardCharsets.UTF_8);
        byte[] maskKey = new byte[] { (byte) 0x1A, (byte) 0x2B, (byte) 0x3C, (byte) 0x4D };
        byte[] maskedPayload = new byte[payloadBytes.length];
        for (int i = 0; i < payloadBytes.length; i++) {
            maskedPayload[i] = (byte) (payloadBytes[i] ^ maskKey[i % 4]);
        }

        ByteArrayOutputStream frameBuffer = new ByteArrayOutputStream();
        frameBuffer.write(0x81); // FIN=1, Opcode=1 (Text)
        frameBuffer.write(0x80 | payloadBytes.length); // MASK=1, len
        frameBuffer.write(maskKey);
        frameBuffer.write(maskedPayload);
        byte[] rawFrame = frameBuffer.toByteArray();

        // Warmup
        for (int i = 0; i < warmupIters; i++) {
            ByteArrayInputStream in = new ByteArrayInputStream(rawFrame);
            WebSocketFrameCodec.Frame frame = WebSocketFrameCodec.readFrame(in, true);
            bh.consume(frame);
        }

        // Measure
        long start = System.nanoTime();
        for (int i = 0; i < measureIters; i++) {
            ByteArrayInputStream in = new ByteArrayInputStream(rawFrame);
            WebSocketFrameCodec.Frame frame = WebSocketFrameCodec.readFrame(in, true);
            bh.consume(frame);
        }
        long durationNs = System.nanoTime() - start;

        double opsPerSec = ((double) measureIters / durationNs) * 1_000_000_000.0;
        double nsPerOp = (double) durationNs / measureIters;
        return new BenchmarkResult("WebSocketFrameCodec.decodeFrame", measureIters, opsPerSec, nsPerOp);
    }

    public static void main(String[] args) throws IOException {
        System.out.println("Running WebSocketFrameCodec Microbenchmarks...");
        BenchmarkResult r1 = benchmarkWriteTextFrame(100_000, 500_000);
        System.out.println(r1);

        BenchmarkResult r2 = benchmarkDecodeMaskedFrame(100_000, 500_000);
        System.out.println(r2);
    }
}
