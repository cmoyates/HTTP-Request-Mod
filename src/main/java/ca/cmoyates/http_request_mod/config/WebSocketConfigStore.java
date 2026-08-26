package ca.cmoyates.http_request_mod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

/** Loads and atomically persists the WebSocket configuration. */
public final class WebSocketConfigStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Set<PosixFilePermission> OWNER_ONLY = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
    );

    private final Path path;
    private WebSocketSettings settings;

    public WebSocketConfigStore(Path path) {
        this.path = path;
        this.settings = WebSocketSettings.defaults();
    }

    public synchronized Path path() {
        return path;
    }

    public synchronized WebSocketSettings get() {
        return settings;
    }

    /**
     * Loads the file, creating a safe default file when it does not exist.
     *
     * @throws IOException if the file cannot be read or contains invalid configuration
     */
    public synchronized WebSocketSettings reload() throws IOException {
        if (Files.notExists(path)) {
            settings = WebSocketSettings.defaults();
            saveInternal(settings);
            return settings;
        }

        final JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new IOException("configuration root must be a JSON object");
            }
            root = parsed.getAsJsonObject();
        } catch (RuntimeException exception) {
            // Do not quote the parser message: malformed input could contain the shared token.
            throw new IOException("configuration is not valid JSON", exception);
        }

        String endpoint = optionalString(root, "endpoint", WebSocketSettings.DEFAULT_ENDPOINT);
        boolean automaticConnect = optionalBoolean(root, "automaticConnect", false);
        String sharedToken = optionalString(root, "sharedToken", "");
        int timeout = optionalInt(
                root,
                "promptTimeoutSeconds",
                WebSocketSettings.DEFAULT_PROMPT_TIMEOUT_SECONDS
        );

        settings = new WebSocketSettings(endpoint, automaticConnect, sharedToken, timeout);
        securePermissions(path);
        return settings;
    }

    public synchronized void save(WebSocketSettings newSettings) throws IOException {
        saveInternal(newSettings);
        settings = newSettings;
    }

    /** Replaces only the in-memory snapshot after a loaded file fails policy validation. */
    public synchronized void restore(WebSocketSettings previousSettings) {
        settings = previousSettings;
    }

    private void saveInternal(WebSocketSettings value) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        JsonObject root = new JsonObject();
        root.addProperty("endpoint", value.endpoint());
        root.addProperty("automaticConnect", value.automaticConnect());
        root.addProperty("sharedToken", value.sharedToken());
        root.addProperty("promptTimeoutSeconds", value.promptTimeoutSeconds());

        Path temporary = parent == null
                ? Files.createTempFile(path.getFileName().toString(), ".tmp")
                : Files.createTempFile(parent, path.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(root) + System.lineSeparator(), StandardCharsets.UTF_8);
            securePermissions(temporary);
            try {
                Files.move(
                        temporary,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            securePermissions(path);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String optionalString(JsonObject root, String key, String fallback) throws IOException {
        JsonElement value = root.get(key);
        if (value == null || value.isJsonNull()) {
            return fallback;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IOException(key + " must be a string");
        }
        return value.getAsString();
    }

    private static boolean optionalBoolean(JsonObject root, String key, boolean fallback) throws IOException {
        JsonElement value = root.get(key);
        if (value == null || value.isJsonNull()) {
            return fallback;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IOException(key + " must be true or false");
        }
        return value.getAsBoolean();
    }

    private static int optionalInt(JsonObject root, String key, int fallback) throws IOException {
        JsonElement value = root.get(key);
        if (value == null || value.isJsonNull()) {
            return fallback;
        }
        try {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                throw new IOException(key + " must be an integer");
            }
            String raw = value.getAsString();
            if (!raw.matches("-?(0|[1-9][0-9]*)")) {
                throw new IOException(key + " must be an integer");
            }
            int parsed = Integer.parseInt(raw);
            if (parsed < WebSocketSettings.MIN_PROMPT_TIMEOUT_SECONDS
                    || parsed > WebSocketSettings.MAX_PROMPT_TIMEOUT_SECONDS) {
                throw new IOException(
                        key + " must be between " + WebSocketSettings.MIN_PROMPT_TIMEOUT_SECONDS
                                + " and " + WebSocketSettings.MAX_PROMPT_TIMEOUT_SECONDS
                );
            }
            return parsed;
        } catch (NumberFormatException | UnsupportedOperationException exception) {
            throw new IOException(key + " must be an integer", exception);
        }
    }

    private static void securePermissions(Path target) {
        try {
            Files.setPosixFilePermissions(target, OWNER_ONLY);
        } catch (IOException | UnsupportedOperationException ignored) {
            // POSIX permissions are unavailable on some supported platforms (notably Windows).
        }
    }
}
