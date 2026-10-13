package pl.ultimo.server.net;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("WebSocketFrameCodec Unit Tests (RFC 6455)")
class WebSocketFrameCodecTest {

    @Test
    @DisplayName("Should correctly calculate Sec-WebSocket-Accept according to RFC 6455 official test vector")
    void shouldComputeOfficialRfcAcceptKey() {
        // Official RFC 6455 Section 4.2.2 test vector
        String clientKey = "dGhlIHNhbXBsZSBub25jZQ==";
        String expectedAccept = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";

        String actualAccept = WebSocketFrameCodec.computeAcceptKey(clientKey);

        assertEquals(expectedAccept, actualAccept);
    }

    @Test
    @DisplayName("Should encode and decode unmasked text frame")
    void shouldEncodeAndDecodeTextFrame() throws Exception {
        String message = "JOIN Alice";
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        WebSocketFrameCodec.writeTextFrame(out, message);
        byte[] frameBytes = out.toByteArray();

        // Server frame: FIN=1, Opcode=1 (0x81), MASK=0, len=10
        assertEquals((byte) 0x81, frameBytes[0]);
        assertEquals((byte) 10, frameBytes[1]);

        ByteArrayInputStream in = new ByteArrayInputStream(frameBytes);
        WebSocketFrameCodec.Frame frame = WebSocketFrameCodec.readFrame(in);

        assertNotNull(frame);
        assertTrue(frame.isText());
        assertEquals(message, frame.text());
    }

    @Test
    @DisplayName("Should correctly decode masked client text frame")
    void shouldDecodeMaskedClientFrame() throws Exception {
        String payloadText = "PLAY RED_7";
        byte[] payloadBytes = payloadText.getBytes(StandardCharsets.UTF_8);
        byte[] mask = new byte[] {0x12, 0x34, 0x56, 0x78};

        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        raw.write(0x81); // FIN + text opcode
        raw.write(0x80 | payloadBytes.length); // MASK bit = 1 + length
        raw.write(mask); // 4-byte mask

        for (int i = 0; i < payloadBytes.length; i++) {
            raw.write(payloadBytes[i] ^ mask[i % 4]);
        }

        ByteArrayInputStream in = new ByteArrayInputStream(raw.toByteArray());
        WebSocketFrameCodec.Frame frame = WebSocketFrameCodec.readFrame(in);

        assertNotNull(frame);
        assertTrue(frame.isText());
        assertEquals("PLAY RED_7", frame.text());
    }

    @Test
    @DisplayName("Should encode medium text frame with 16-bit extended length (126 <= len <= 65535)")
    void shouldEncodeMediumTextFrame() throws Exception {
        String mediumText = "A".repeat(300);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        WebSocketFrameCodec.writeTextFrame(out, mediumText);
        byte[] frameBytes = out.toByteArray();

        // Byte 0: 0x81, Byte 1: 126, Byte 2-3: length in big endian (300 = 0x012C)
        assertEquals((byte) 0x81, frameBytes[0]);
        assertEquals((byte) 126, frameBytes[1]);
        assertEquals((byte) 0x01, frameBytes[2]);
        assertEquals((byte) 0x2C, frameBytes[3]);

        ByteArrayInputStream in = new ByteArrayInputStream(frameBytes);
        WebSocketFrameCodec.Frame frame = WebSocketFrameCodec.readFrame(in);

        assertNotNull(frame);
        assertEquals(300, frame.text().length());
    }

    @Test
    @DisplayName("Should encode and decode close frame")
    void shouldHandleCloseFrame() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WebSocketFrameCodec.writeCloseFrame(out);

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        WebSocketFrameCodec.Frame frame = WebSocketFrameCodec.readFrame(in);

        assertNotNull(frame);
        assertTrue(frame.isClose());
    }
}
