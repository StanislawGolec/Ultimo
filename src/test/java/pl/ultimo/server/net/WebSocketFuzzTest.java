package pl.ultimo.server.net;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pl.ultimo.server.ServerApp;
import pl.ultimo.server.config.ServerConfig;
import pl.ultimo.server.metrics.MetricsRegistry;
import pl.ultimo.server.net.WebSocketFrameCodec.WebSocketProtocolException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RFC 6455 WebSocket Engine Security & Fuzz-Proof Hardening Tests")
class WebSocketFuzzTest {

    private ServerApp serverApp;
    private int port;

    @BeforeEach
    void setUp() {
        MetricsRegistry.getInstance().reset();
    }

    @AfterEach
    void tearDown() {
        if (serverApp != null) {
            serverApp.stop();
        }
        MetricsRegistry.getInstance().reset();
    }

    private void startServer() throws InterruptedException {
        ServerConfig config = new ServerConfig(0, 4, 5000, 30, 1);
        serverApp = new ServerApp(config);
        serverApp.start();

        int attempts = 0;
        while ((port = serverApp.getBoundPort()) <= 0 && attempts++ < 50) {
            Thread.sleep(20);
        }
        assertTrue(port > 0, "Server failed to bind to a random free port");
    }

    // =========================================================================
    // 1. Client Frame Masking Enforcement (RFC 6455 §5.1)
    // =========================================================================

    @ParameterizedTest(name = "Unmasked opcode 0x{0} must be rejected with 1002")
    @ValueSource(ints = {
            WebSocketFrameCodec.OPCODE_TEXT,
            WebSocketFrameCodec.OPCODE_BINARY,
            WebSocketFrameCodec.OPCODE_PING,
            WebSocketFrameCodec.OPCODE_CLOSE
    })
    @DisplayName("Client sending unmasked frames must be rejected with status code 1002")
    void shouldRejectUnmaskedClientFrames(int opcode) {
        byte[] payload = "Hello".getBytes(StandardCharsets.UTF_8);
        byte[] frameBytes = createFrame(true, 0, opcode, false, payload);

        ByteArrayInputStream in = new ByteArrayInputStream(frameBytes);
        WebSocketProtocolException ex = assertThrows(WebSocketProtocolException.class,
                () -> WebSocketFrameCodec.readFrame(in, true));

        assertEquals(WebSocketFrameCodec.CLOSE_PROTOCOL_ERROR, ex.getStatusCode());
        assertTrue(ex.getMessage().contains("unmasked"));
    }

    // =========================================================================
    // 2. Invalid & Reserved Opcode Validation (RFC 6455 §5.2)
    // =========================================================================

    @ParameterizedTest(name = "Invalid reserved opcode 0x{0} must trigger 1002")
    @ValueSource(ints = {0x3, 0x4, 0x5, 0x6, 0x7, 0xB, 0xC, 0xD, 0xE, 0xF})
    @DisplayName("Frames with reserved opcodes must throw status code 1002")
    void shouldRejectReservedOpcodes(int reservedOpcode) {
        byte[] payload = new byte[]{0x01, 0x02};
        byte[] frameBytes = createFrame(true, 0, reservedOpcode, true, payload);

        ByteArrayInputStream in = new ByteArrayInputStream(frameBytes);
        WebSocketProtocolException ex = assertThrows(WebSocketProtocolException.class,
                () -> WebSocketFrameCodec.readFrame(in, true));

        assertEquals(WebSocketFrameCodec.CLOSE_PROTOCOL_ERROR, ex.getStatusCode());
        assertTrue(ex.getMessage().contains("reserved opcode") || ex.getMessage().contains("Invalid"));
    }

    // =========================================================================
    // 3. RSV Bits Enforcement (RFC 6455 §5.2)
    // =========================================================================

    @ParameterizedTest(name = "Non-zero RSV {0} must trigger 1002")
    @ValueSource(ints = {1, 2, 4, 7})
    @DisplayName("Frames with non-zero RSV bits without negotiated extension must throw status code 1002")
    void shouldRejectNonZeroRsvBits(int rsv) {
        byte[] payload = "test".getBytes(StandardCharsets.UTF_8);
        byte[] frameBytes = createFrame(true, rsv, WebSocketFrameCodec.OPCODE_TEXT, true, payload);

        ByteArrayInputStream in = new ByteArrayInputStream(frameBytes);
        WebSocketProtocolException ex = assertThrows(WebSocketProtocolException.class,
                () -> WebSocketFrameCodec.readFrame(in, true));

        assertEquals(WebSocketFrameCodec.CLOSE_PROTOCOL_ERROR, ex.getStatusCode());
        assertTrue(ex.getMessage().contains("RSV bits must be 0"));
    }

    // =========================================================================
    // 4. Control Frame Constraints (RFC 6455 §5.5)
    // =========================================================================

    @ParameterizedTest(name = "Fragmented control frame (FIN=0) opcode 0x{0} must trigger 1002")
    @ValueSource(ints = {
            WebSocketFrameCodec.OPCODE_PING,
            WebSocketFrameCodec.OPCODE_PONG,
            WebSocketFrameCodec.OPCODE_CLOSE
    })
    @DisplayName("Fragmented control frames (FIN=0) must be rejected with status code 1002")
    void shouldRejectFragmentedControlFrames(int controlOpcode) {
        byte[] payload = new byte[]{0x01};
        // FIN = false
        byte[] frameBytes = createFrame(false, 0, controlOpcode, true, payload);

        ByteArrayInputStream in = new ByteArrayInputStream(frameBytes);
        WebSocketProtocolException ex = assertThrows(WebSocketProtocolException.class,
                () -> WebSocketFrameCodec.readFrame(in, true));

        assertEquals(WebSocketFrameCodec.CLOSE_PROTOCOL_ERROR, ex.getStatusCode());
        assertTrue(ex.getMessage().contains("Control frames must not be fragmented"));
    }

    @ParameterizedTest(name = "Control frame payload > 125 (len={0}) must trigger 1002")
    @ValueSource(ints = {126, 150, 200})
    @DisplayName("Control frames with payload > 125 bytes must be rejected with status code 1002")
    void shouldRejectControlFramesWithExcessivePayload(int payloadLength) {
        byte[] payload = new byte[payloadLength];
        byte[] frameBytes = createFrame(true, 0, WebSocketFrameCodec.OPCODE_PING, true, payload);

        ByteArrayInputStream in = new ByteArrayInputStream(frameBytes);
        WebSocketProtocolException ex = assertThrows(WebSocketProtocolException.class,
                () -> WebSocketFrameCodec.readFrame(in, true));

        assertEquals(WebSocketFrameCodec.CLOSE_PROTOCOL_ERROR, ex.getStatusCode());
        assertTrue(ex.getMessage().contains("exceeds 125 bytes"));
    }

    // =========================================================================
    // 5. Oversized Payload DOS & OOM Protection (RFC 6455 §7.4.1 / 1009)
    // =========================================================================

    @Test
    @DisplayName("Payload exceeding MAX_PAYLOAD_SIZE (64KB + 1) must be rejected with status code 1009")
    void shouldRejectPayloadExceeding64Kb() {
        // Create frame header indicating 65537 bytes length
        byte[] headerBytes = createFrameHeader(true, WebSocketFrameCodec.OPCODE_TEXT, true, 65537L);

        ByteArrayInputStream in = new ByteArrayInputStream(headerBytes);
        WebSocketProtocolException ex = assertThrows(WebSocketProtocolException.class,
                () -> WebSocketFrameCodec.readFrame(in, true));

        assertEquals(WebSocketFrameCodec.CLOSE_MESSAGE_TOO_BIG, ex.getStatusCode());
        assertTrue(ex.getMessage().contains("exceeds 64KB limit"));
    }

    @Test
    @DisplayName("Payload header with 100,000 bytes must be rejected with status code 1009")
    void shouldRejectPayloadWith100000Bytes() {
        byte[] headerBytes = createFrameHeader(true, WebSocketFrameCodec.OPCODE_BINARY, true, 100_000L);

        ByteArrayInputStream in = new ByteArrayInputStream(headerBytes);
        WebSocketProtocolException ex = assertThrows(WebSocketProtocolException.class,
                () -> WebSocketFrameCodec.readFrame(in, true));

        assertEquals(WebSocketFrameCodec.CLOSE_MESSAGE_TOO_BIG, ex.getStatusCode());
    }

    @Test
    @DisplayName("64-bit excessive payload length (1GB) must be rejected with status code 1009 without allocating memory")
    void shouldRejectExcessive64BitPayloadLength() {
        byte[] headerBytes = createFrameHeader(true, WebSocketFrameCodec.OPCODE_TEXT, true, 1024L * 1024L * 1024L);

        ByteArrayInputStream in = new ByteArrayInputStream(headerBytes);
        WebSocketProtocolException ex = assertThrows(WebSocketProtocolException.class,
                () -> WebSocketFrameCodec.readFrame(in, true));

        assertEquals(WebSocketFrameCodec.CLOSE_MESSAGE_TOO_BIG, ex.getStatusCode());
    }

    // =========================================================================
    // 6. Truncated Streams & Incomplete Frame Headers
    // =========================================================================

    @Test
    @DisplayName("Clean EOF on first byte returns null without exception")
    void shouldReturnNullOnCleanEof() throws Exception {
        ByteArrayInputStream in = new ByteArrayInputStream(new byte[0]);
        WebSocketFrameCodec.Frame frame = WebSocketFrameCodec.readFrame(in, true);
        assertNull(frame);
    }

    @Test
    @DisplayName("Truncated 1-byte stream throws EOFException")
    void shouldThrowEofOn1ByteHeader() {
        ByteArrayInputStream in = new ByteArrayInputStream(new byte[]{(byte) 0x81});
        assertThrows(EOFException.class, () -> WebSocketFrameCodec.readFrame(in, true));
    }

    @Test
    @DisplayName("Truncated 16-bit extended length throws EOFException")
    void shouldThrowEofOnTruncated16BitLength() {
        // Header says 16-bit length (126), but only 1 byte of length follows
        byte[] truncated = new byte[]{(byte) 0x81, (byte) (0x80 | 126), 0x01};
        ByteArrayInputStream in = new ByteArrayInputStream(truncated);
        assertThrows(EOFException.class, () -> WebSocketFrameCodec.readFrame(in, true));
    }

    @Test
    @DisplayName("Truncated 64-bit extended length throws EOFException")
    void shouldThrowEofOnTruncated64BitLength() {
        // Header says 64-bit length (127), but only 4 bytes of length follow
        byte[] truncated = new byte[]{(byte) 0x81, (byte) (0x80 | 127), 0x00, 0x00, 0x00, 0x01};
        ByteArrayInputStream in = new ByteArrayInputStream(truncated);
        assertThrows(EOFException.class, () -> WebSocketFrameCodec.readFrame(in, true));
    }

    @Test
    @DisplayName("Truncated mask key (only 2 bytes) throws EOFException")
    void shouldThrowEofOnTruncatedMaskKey() {
        // Mask bit set, length 5, but only 2 mask bytes follow
        byte[] truncated = new byte[]{(byte) 0x81, (byte) (0x80 | 5), 0x11, 0x22};
        ByteArrayInputStream in = new ByteArrayInputStream(truncated);
        assertThrows(EOFException.class, () -> WebSocketFrameCodec.readFrame(in, true));
    }

    @Test
    @DisplayName("Truncated payload bytes throws EOFException")
    void shouldThrowEofOnTruncatedPayload() {
        // Masked, length 10, valid 4-byte mask, but only 3 payload bytes follow
        byte[] truncated = new byte[]{
                (byte) 0x81, (byte) (0x80 | 10),
                0x11, 0x22, 0x33, 0x44,
                0x01, 0x02, 0x03
        };
        ByteArrayInputStream in = new ByteArrayInputStream(truncated);
        assertThrows(EOFException.class, () -> WebSocketFrameCodec.readFrame(in, true));
    }

    // =========================================================================
    // 7. Legitimate Frame Parsing & Close Code Decoders
    // =========================================================================

    @Test
    @DisplayName("Valid masked Ping frame with 0 payload is accepted")
    void shouldAcceptValidMaskedPingWithZeroPayload() throws Exception {
        byte[] pingBytes = createFrame(true, 0, WebSocketFrameCodec.OPCODE_PING, true, new byte[0]);
        ByteArrayInputStream in = new ByteArrayInputStream(pingBytes);

        WebSocketFrameCodec.Frame frame = WebSocketFrameCodec.readFrame(in, true);
        assertNotNull(frame);
        assertTrue(frame.isPing());
        assertEquals(0, frame.payload().length);
    }

    @Test
    @DisplayName("Valid masked Close frame with status code 1000 and reason string is decoded")
    void shouldDecodeCloseFrameWithStatusCodeAndReason() throws Exception {
        byte[] reason = "Client closing normally".getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[2 + reason.length];
        payload[0] = (byte) ((1000 >> 8) & 0xFF);
        payload[1] = (byte) (1000 & 0xFF);
        System.arraycopy(reason, 0, payload, 2, reason.length);

        byte[] closeBytes = createFrame(true, 0, WebSocketFrameCodec.OPCODE_CLOSE, true, payload);
        ByteArrayInputStream in = new ByteArrayInputStream(closeBytes);

        WebSocketFrameCodec.Frame frame = WebSocketFrameCodec.readFrame(in, true);
        assertNotNull(frame);
        assertTrue(frame.isClose());
        assertEquals(1000, frame.closeCode());
        assertEquals("Client closing normally", frame.closeReason());
    }

    @Test
    @DisplayName("writeCloseFrame formats status code in network byte order")
    void shouldFormatCloseFrameWithStatusCode() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WebSocketFrameCodec.writeCloseFrame(out, WebSocketFrameCodec.CLOSE_PROTOCOL_ERROR, "Protocol violation");

        byte[] frameBytes = out.toByteArray();
        ByteArrayInputStream in = new ByteArrayInputStream(frameBytes);

        // Server-sent frame is unmasked
        WebSocketFrameCodec.Frame frame = WebSocketFrameCodec.readFrame(in, false);
        assertNotNull(frame);
        assertTrue(frame.isClose());
        assertEquals(WebSocketFrameCodec.CLOSE_PROTOCOL_ERROR, frame.closeCode());
        assertEquals("Protocol violation", frame.closeReason());
    }

    // =========================================================================
    // 8. End-to-End Live Socket Adversarial & Fuzzing Tests
    // =========================================================================

    @Test
    @DisplayName("Live Server: Client sending unmasked frame receives Close 1002 and socket is closed")
    void shouldCloseLiveConnectionWith1002WhenClientSendsUnmaskedFrame() throws Exception {
        startServer();

        try (Socket socket = new Socket("127.0.0.1", port);
             OutputStream out = socket.getOutputStream();
             InputStream in = socket.getInputStream()) {

            // Step 1: Perform valid WebSocket handshake
            performWebSocketHandshake(in, out);

            // Step 2: Send UNMASKED frame (illegal according to RFC 6455 §5.1)
            byte[] unmaskedFrame = createFrame(true, 0, WebSocketFrameCodec.OPCODE_TEXT, false, "JOIN Alice".getBytes(StandardCharsets.UTF_8));
            out.write(unmaskedFrame);
            out.flush();

            // Step 3: Server must respond with Close frame carrying status 1002
            WebSocketFrameCodec.Frame responseFrame = WebSocketFrameCodec.readFrame(in, false);
            assertNotNull(responseFrame, "Server did not reply with Close frame");
            assertTrue(responseFrame.isClose(), "Expected Close frame from server");
            assertEquals(WebSocketFrameCodec.CLOSE_PROTOCOL_ERROR, responseFrame.closeCode());

            // Server terminates socket
            int nextByte = in.read();
            assertEquals(-1, nextByte, "Server did not close the connection after protocol error");
        }
    }

    @Test
    @DisplayName("Live Server: Client sending fragmented control frame receives Close 1002 and socket is closed")
    void shouldCloseLiveConnectionWith1002WhenClientSendsFragmentedControlFrame() throws Exception {
        startServer();

        try (Socket socket = new Socket("127.0.0.1", port);
             OutputStream out = socket.getOutputStream();
             InputStream in = socket.getInputStream()) {

            performWebSocketHandshake(in, out);

            // Send fragmented Ping (FIN = false)
            byte[] fragmentedPing = createFrame(false, 0, WebSocketFrameCodec.OPCODE_PING, true, new byte[0]);
            out.write(fragmentedPing);
            out.flush();

            WebSocketFrameCodec.Frame responseFrame = WebSocketFrameCodec.readFrame(in, false);
            assertNotNull(responseFrame);
            assertTrue(responseFrame.isClose());
            assertEquals(WebSocketFrameCodec.CLOSE_PROTOCOL_ERROR, responseFrame.closeCode());

            assertEquals(-1, in.read());
        }
    }

    @Test
    @DisplayName("Live Server: Client sending frame header > 64KB receives Close 1009 and socket is closed")
    void shouldCloseLiveConnectionWith1009WhenClientSendsOversizedFrameHeader() throws Exception {
        startServer();

        try (Socket socket = new Socket("127.0.0.1", port);
             OutputStream out = socket.getOutputStream();
             InputStream in = socket.getInputStream()) {

            performWebSocketHandshake(in, out);

            // Send frame header indicating 100KB payload
            byte[] oversizedHeader = createFrameHeader(true, WebSocketFrameCodec.OPCODE_TEXT, true, 100_000L);
            out.write(oversizedHeader);
            out.flush();

            WebSocketFrameCodec.Frame responseFrame = WebSocketFrameCodec.readFrame(in, false);
            assertNotNull(responseFrame);
            assertTrue(responseFrame.isClose());
            assertEquals(WebSocketFrameCodec.CLOSE_MESSAGE_TOO_BIG, responseFrame.closeCode());

            assertEquals(-1, in.read());
        }
    }

    @Test
    @DisplayName("Live Server: Random byte fuzzing stream terminates cleanly without server crash")
    void shouldHandleRandomByteFuzzingWithoutCrashingServer() throws Exception {
        startServer();

        Random random = new Random(42);
        for (int i = 0; i < 5; i++) {
            try (Socket socket = new Socket("127.0.0.1", port);
                 OutputStream out = socket.getOutputStream();
                 InputStream in = socket.getInputStream()) {

                performWebSocketHandshake(in, out);

                // Feed 256 bytes of pure pseudorandom garbage
                byte[] garbage = new byte[256];
                random.nextBytes(garbage);

                out.write(garbage);
                out.flush();

                // Consume whatever the server sends until EOF
                byte[] buf = new byte[512];
                while (in.read(buf) != -1) {
                    // Drain
                }
            }
        }

        // Assert server is still healthy and operational after fuzz attacks
        assertTrue(serverApp.isServerReady());
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    private void performWebSocketHandshake(InputStream in, OutputStream out) throws Exception {
        String handshake = "GET / HTTP/1.1\r\n"
                + "Host: localhost:" + port + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";

        out.write(handshake.getBytes(StandardCharsets.UTF_8));
        out.flush();

        // Read HTTP 101 Switching Protocols
        ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            headerBuf.write(b);
            String current = headerBuf.toString(StandardCharsets.UTF_8);
            if (current.endsWith("\r\n\r\n")) {
                break;
            }
        }
        String response = headerBuf.toString(StandardCharsets.UTF_8);
        assertTrue(response.startsWith("HTTP/1.1 101 Switching Protocols"), "Handshake failed: " + response);
    }

    private static byte[] createFrame(boolean fin, int rsv, int opcode, boolean masked, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int b0 = (fin ? 0x80 : 0x00) | ((rsv & 0x07) << 4) | (opcode & 0x0F);
        out.write(b0);

        int len = payload != null ? payload.length : 0;
        int maskBit = masked ? 0x80 : 0x00;

        if (len < 126) {
            out.write(maskBit | len);
        } else if (len <= 0xFFFF) {
            out.write(maskBit | 126);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(maskBit | 127);
            for (int i = 7; i >= 0; i--) {
                out.write((int) ((len >>> (i * 8)) & 0xFF));
            }
        }

        if (masked) {
            byte[] mask = new byte[]{0x11, 0x22, 0x33, 0x44};
            out.write(mask, 0, 4);
            if (payload != null) {
                for (int i = 0; i < payload.length; i++) {
                    out.write(payload[i] ^ mask[i % 4]);
                }
            }
        } else {
            if (payload != null) {
                out.write(payload, 0, payload.length);
            }
        }

        return out.toByteArray();
    }

    private static byte[] createFrameHeader(boolean fin, int opcode, boolean masked, long payloadLength) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int b0 = (fin ? 0x80 : 0x00) | (opcode & 0x0F);
        out.write(b0);

        int maskBit = masked ? 0x80 : 0x00;
        if (payloadLength < 126) {
            out.write(maskBit | (int) payloadLength);
        } else if (payloadLength <= 0xFFFF) {
            out.write(maskBit | 126);
            out.write((int) ((payloadLength >> 8) & 0xFF));
            out.write((int) (payloadLength & 0xFF));
        } else {
            out.write(maskBit | 127);
            for (int i = 7; i >= 0; i--) {
                out.write((int) ((payloadLength >>> (i * 8)) & 0xFF));
            }
        }
        if (masked) {
            out.write(new byte[]{0x01, 0x02, 0x03, 0x04}, 0, 4);
        }
        return out.toByteArray();
    }
}
