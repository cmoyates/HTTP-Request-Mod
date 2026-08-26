package ca.cmoyates.http_request_mod.websocket;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiProtocolTest {
    private static final UUID REQUEST_ID = UUID.fromString("d419ac9d-9383-4ef5-b5d8-ddbb7797374e");

    @Test
    void encodesHelloAndPlayerMessageAsVersionedJson() {
        JsonObject hello = JsonParser.parseString(PiProtocol.hello("Test \"World\"")).getAsJsonObject();
        assertEquals(1, hello.get("version").getAsInt());
        assertEquals("hello", hello.get("type").getAsString());
        assertEquals("Test \"World\"", hello.get("server").getAsString());
        String longMotd = "x".repeat(255) + "😀" + "tail";
        String truncatedMotd = JsonParser.parseString(PiProtocol.hello(longMotd))
                .getAsJsonObject()
                .get("server")
                .getAsString();
        assertEquals(255, truncatedMotd.length());
        assertTrue(truncatedMotd.endsWith("x"));

        UUID playerId = UUID.fromString("9bc0bb64-e46d-46b9-bb1f-a5229c3d7b67");
        JsonObject message = JsonParser.parseString(PiProtocol.playerMessage(
                REQUEST_ID,
                playerId,
                "Player One",
                "Review src/main.java\nthen test"
        )).getAsJsonObject();

        assertEquals(1, message.get("version").getAsInt());
        assertEquals("message", message.get("type").getAsString());
        assertEquals(REQUEST_ID.toString(), message.get("requestId").getAsString());
        assertEquals(playerId.toString(), message.getAsJsonObject("player").get("id").getAsString());
        assertEquals("Review src/main.java\nthen test", message.get("content").getAsString());
    }

    @Test
    void decodesEveryServerMessageTypeAndIgnoresUnknownFields() throws Exception {
        PiProtocol.Chunk chunk = assertInstanceOf(PiProtocol.Chunk.class, PiProtocol.decodeServerMessage("""
                {"version":1,"type":"chunk","requestId":"%s","sequence":2,"content":"hello","future":true}
                """.formatted(REQUEST_ID)));
        assertEquals(REQUEST_ID, chunk.requestId());
        assertEquals(2, chunk.sequence());
        assertEquals("hello", chunk.content());

        PiProtocol.Complete complete = assertInstanceOf(PiProtocol.Complete.class, PiProtocol.decodeServerMessage("""
                {"version":1,"type":"complete","requestId":"%s","sequence":3}
                """.formatted(REQUEST_ID)));
        assertEquals(3, complete.sequence());

        PiProtocol.ErrorMessage requestError = assertInstanceOf(
                PiProtocol.ErrorMessage.class,
                PiProtocol.decodeServerMessage("""
                        {"version":1,"type":"error","requestId":"%s","code":"busy","message":"session busy","retryable":true}
                        """.formatted(REQUEST_ID))
        );
        assertEquals(REQUEST_ID, requestError.requestId());
        assertTrue(requestError.retryable());

        PiProtocol.ErrorMessage sessionError = assertInstanceOf(
                PiProtocol.ErrorMessage.class,
                PiProtocol.decodeServerMessage("""
                        {"version":1,"type":"error","code":"unavailable","message":"no model"}
                        """)
        );
        assertNull(sessionError.requestId());

        PiProtocol.Status status = assertInstanceOf(PiProtocol.Status.class, PiProtocol.decodeServerMessage("""
                {"version":1,"type":"status","status":"ready","message":"session available"}
                """));
        assertEquals("ready", status.status());
    }

    @Test
    void rejectsMalformedUnsupportedAndAmbiguousMessages() {
        assertProtocolError("not json");
        assertProtocolError("[]");
        assertProtocolError("{\"version\":2,\"type\":\"status\",\"status\":\"ready\"}");
        assertProtocolError("{\"version\":1.0,\"type\":\"status\",\"status\":\"ready\"}");
        assertProtocolError("{\"version\":1,\"type\":\"unknown\"}");
        assertProtocolError("{\"version\":1,\"type\":\"chunk\",\"requestId\":\"bad\",\"sequence\":0,\"content\":\"x\"}");
        assertProtocolError("""
                {"version":1,"type":"chunk","requestId":"d419ac9d-9383-4ef5-b5d8-ddbb7797374e","sequence":-1,"content":"x"}
                """);
        assertProtocolError("{\"version\":1,\"type\":\"error\",\"code\":\"bad\"}");
    }

    @Test
    void rejectsOversizedFramesBeforeParsing() {
        String oversized = "x".repeat(PiProtocol.MAX_FRAME_BYTES + 1);
        PiProtocol.ProtocolException exception = assertThrows(
                PiProtocol.ProtocolException.class,
                () -> PiProtocol.decodeServerMessage(oversized)
        );
        assertTrue(exception.getMessage().contains("frame limit"));

        String oversizedUtf8 = "😀".repeat(PiProtocol.MAX_FRAME_BYTES / 4 + 1);
        assertThrows(
                PiProtocol.ProtocolException.class,
                () -> PiProtocol.decodeServerMessage(oversizedUtf8)
        );
    }

    private static void assertProtocolError(String json) {
        assertThrows(PiProtocol.ProtocolException.class, () -> PiProtocol.decodeServerMessage(json));
    }
}
