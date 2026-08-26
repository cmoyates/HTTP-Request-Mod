package ca.cmoyates.http_request_mod.websocket;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingTextBufferTest {
    @Test
    void coalescesSmallChunksUntilStale() {
        StreamingTextBuffer buffer = new StreamingTextBuffer(10);

        assertTrue(buffer.append("hel", 1).isEmpty());
        assertTrue(buffer.append("lo", 2).isEmpty());
        assertTrue(buffer.flushIfStale(5, 4).isEmpty());
        assertEquals(List.of("hello"), buffer.flushIfStale(6, 4));
    }

    @Test
    void flushesCompleteLinesImmediatelyAndNormalizesCrLf() {
        StreamingTextBuffer buffer = new StreamingTextBuffer(20);

        assertEquals(List.of("first", "second"), buffer.append("first\r\nsecond\nthird", 1));
        assertEquals(List.of("third"), buffer.flush());
    }

    @Test
    void splitsLongTextByUnicodeCodePointsWithoutBreakingSurrogates() {
        StreamingTextBuffer buffer = new StreamingTextBuffer(3);

        assertEquals(List.of("a😀b", "c😀d"), buffer.append("a😀bc😀d", 1));
        assertTrue(buffer.isEmpty());
    }

    @Test
    void preservesBlankLines() {
        StreamingTextBuffer buffer = new StreamingTextBuffer(10);

        assertEquals(List.of("", ""), buffer.append("\n\nremaining", 1));
        assertEquals(List.of("remaining"), buffer.flush());
    }
}
