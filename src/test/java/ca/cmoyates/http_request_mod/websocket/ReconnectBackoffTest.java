package ca.cmoyates.http_request_mod.websocket;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReconnectBackoffTest {
    @Test
    void growsExponentiallyAndRemainsBounded() {
        assertEquals(1, ReconnectBackoff.delaySeconds(1));
        assertEquals(2, ReconnectBackoff.delaySeconds(2));
        assertEquals(4, ReconnectBackoff.delaySeconds(3));
        assertEquals(8, ReconnectBackoff.delaySeconds(4));
        assertEquals(16, ReconnectBackoff.delaySeconds(5));
        assertEquals(30, ReconnectBackoff.delaySeconds(6));
        assertEquals(30, ReconnectBackoff.delaySeconds(100));
    }
}
