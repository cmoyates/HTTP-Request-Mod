package ca.cmoyates.http_request_mod.config;

import ca.cmoyates.http_request_mod.websocket.EndpointPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketConfigStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void createsSafeDefaultsAndPersistsChangesAcrossInstances() throws Exception {
        Path path = temporaryDirectory.resolve("http-request-mod.json");
        WebSocketConfigStore first = new WebSocketConfigStore(path);
        WebSocketSettings defaults = first.reload();

        assertTrue(Files.isRegularFile(path));
        assertEquals(WebSocketSettings.DEFAULT_ENDPOINT, defaults.endpoint());
        assertFalse(defaults.automaticConnect());
        assertFalse(defaults.hasSharedToken());

        WebSocketSettings changed = new WebSocketSettings(
                "wss://agent.example.test/socket",
                true,
                "token-value",
                300
        );
        first.save(changed);

        WebSocketConfigStore second = new WebSocketConfigStore(path);
        WebSocketSettings reloaded = second.reload();
        assertEquals("wss://agent.example.test/socket", reloaded.endpoint());
        assertTrue(reloaded.automaticConnect());
        assertEquals("token-value", reloaded.sharedToken());
        assertEquals(300, reloaded.promptTimeoutSeconds());
    }

    @Test
    void malformedConfigurationDoesNotReplaceLastKnownGoodSettings() throws Exception {
        Path path = temporaryDirectory.resolve("config.json");
        WebSocketConfigStore store = new WebSocketConfigStore(path);
        WebSocketSettings knownGood = new WebSocketSettings("ws://localhost:9000", true, "", 60);
        store.save(knownGood);

        Files.writeString(path, "{not valid and perhaps secret material}");
        IOException exception = assertThrows(IOException.class, store::reload);

        assertEquals("configuration is not valid JSON", exception.getMessage());
        assertEquals("ws://localhost:9000", store.get().endpoint());
        assertTrue(store.get().automaticConnect());
    }

    @Test
    void rejectsWrongTypesAndOutOfRangeTimeouts() throws Exception {
        Path path = temporaryDirectory.resolve("config.json");
        WebSocketConfigStore store = new WebSocketConfigStore(path);

        Files.writeString(path, "{\"automaticConnect\":\"yes\"}");
        assertThrows(IOException.class, store::reload);

        Files.writeString(path, "{\"promptTimeoutSeconds\":1}");
        assertThrows(IOException.class, store::reload);

        Files.writeString(path, "{\"promptTimeoutSeconds\":12.5}");
        assertThrows(IOException.class, store::reload);
    }

    @Test
    void policyInvalidReloadCanRestoreTheLastKnownGoodSnapshot() throws Exception {
        Path path = temporaryDirectory.resolve("config.json");
        WebSocketConfigStore store = new WebSocketConfigStore(path);
        WebSocketSettings knownGood = new WebSocketSettings("ws://localhost:9000", true, "", 60);
        store.save(knownGood);

        Files.writeString(path, """
                {
                  "endpoint": "ws://remote.example.test/socket",
                  "automaticConnect": true,
                  "sharedToken": "",
                  "promptTimeoutSeconds": 60
                }
                """);
        WebSocketSettings loaded = store.reload();
        assertThrows(IllegalArgumentException.class, () -> EndpointPolicy.validate(loaded.endpoint(), loaded));
        store.restore(knownGood);

        assertEquals("ws://localhost:9000", store.get().endpoint());
        assertTrue(store.get().automaticConnect());
    }

    @Test
    void usesOwnerOnlyPermissionsWhenPosixPermissionsAreAvailable() throws Exception {
        Path path = temporaryDirectory.resolve("config.json");
        WebSocketConfigStore store = new WebSocketConfigStore(path);
        store.reload();

        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
            assertEquals(
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    permissions
            );
        } catch (UnsupportedOperationException ignored) {
            // Windows and other non-POSIX file systems are supported without this assertion.
        }
    }
}
