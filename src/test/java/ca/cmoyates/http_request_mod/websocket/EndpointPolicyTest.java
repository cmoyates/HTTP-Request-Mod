package ca.cmoyates.http_request_mod.websocket;

import ca.cmoyates.http_request_mod.config.WebSocketSettings;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndpointPolicyTest {
    private static final WebSocketSettings LOCAL_SETTINGS = WebSocketSettings.defaults();
    private static final WebSocketSettings AUTHENTICATED = new WebSocketSettings(
            "wss://agent.example.test/socket",
            false,
            "top-secret",
            120
    );

    @Test
    void acceptsLiteralLoopbackEndpointsWithoutAuthentication() {
        assertDoesNotThrow(() -> EndpointPolicy.validate("ws://127.0.0.1:8765", LOCAL_SETTINGS));
        assertDoesNotThrow(() -> EndpointPolicy.validate("ws://127.99.2.3/socket", LOCAL_SETTINGS));
        assertDoesNotThrow(() -> EndpointPolicy.validate("ws://[::1]:8765/socket", LOCAL_SETTINGS));
        assertDoesNotThrow(() -> EndpointPolicy.validate("wss://localhost/socket", LOCAL_SETTINGS));
        assertDoesNotThrow(() -> EndpointPolicy.validate("ws://pi.localhost/socket", LOCAL_SETTINGS));
    }

    @Test
    void requiresTlsAndTokenForNonLoopbackEndpoints() {
        IllegalArgumentException insecure = assertThrows(
                IllegalArgumentException.class,
                () -> EndpointPolicy.validate("ws://agent.example.test/socket", AUTHENTICATED)
        );
        assertTrue(insecure.getMessage().contains("wss://"));

        IllegalArgumentException unauthenticated = assertThrows(
                IllegalArgumentException.class,
                () -> EndpointPolicy.validate("wss://agent.example.test/socket", LOCAL_SETTINGS)
        );
        assertTrue(unauthenticated.getMessage().contains("sharedToken"));

        assertDoesNotThrow(() -> EndpointPolicy.validate(
                "wss://agent.example.test/socket",
                AUTHENTICATED
        ));
    }

    @Test
    void rejectsCredentialBearingOrUnsupportedUris() {
        assertThrows(IllegalArgumentException.class, () -> EndpointPolicy.validate(
                "ws://user:password@localhost/socket",
                LOCAL_SETTINGS
        ));
        assertThrows(IllegalArgumentException.class, () -> EndpointPolicy.validate(
                "ws://localhost/socket#secret",
                LOCAL_SETTINGS
        ));
        assertThrows(IllegalArgumentException.class, () -> EndpointPolicy.validate(
                "https://localhost/socket",
                LOCAL_SETTINGS
        ));
        assertThrows(IllegalArgumentException.class, () -> EndpointPolicy.validate("", LOCAL_SETTINGS));
        assertThrows(IllegalArgumentException.class, () -> EndpointPolicy.validate("not a uri", LOCAL_SETTINGS));
        assertThrows(IllegalArgumentException.class, () -> EndpointPolicy.validate(
                "ws://localhost:70000/socket",
                LOCAL_SETTINGS
        ));
        WebSocketSettings invalidToken = new WebSocketSettings(
                "ws://localhost/socket",
                false,
                "secret\nheader",
                120
        );
        assertThrows(IllegalArgumentException.class, () -> EndpointPolicy.validate(
                "ws://localhost/socket",
                invalidToken
        ));
    }

    @Test
    void endpointDisplayAlwaysRedactsQueryValues() {
        URI endpoint = URI.create("wss://agent.example.test:9443/chat?token=do-not-show&room=one");
        String displayed = EndpointPolicy.display(endpoint);

        assertEquals("wss://agent.example.test:9443/chat?<redacted>", displayed);
        assertFalse(displayed.contains("do-not-show"));
        assertFalse(displayed.contains("room=one"));

        String credentialInPath = EndpointPolicy.display(
                URI.create("wss://agent.example.test/chat/top-secret"),
                "top-secret"
        );
        assertEquals("wss://agent.example.test/chat/<redacted>", credentialInPath);
    }

    @Test
    void loopbackDetectionDoesNotTreatLookalikeDnsNamesAsLocal() {
        assertTrue(EndpointPolicy.isLoopbackHost("localhost"));
        assertTrue(EndpointPolicy.isLoopbackHost("127.0.0.42"));
        assertTrue(EndpointPolicy.isLoopbackHost("::1"));
        assertFalse(EndpointPolicy.isLoopbackHost("localhost.example.com"));
        assertFalse(EndpointPolicy.isLoopbackHost("128.0.0.1"));
        assertFalse(EndpointPolicy.isLoopbackHost("127.0.0.999"));
    }
}
