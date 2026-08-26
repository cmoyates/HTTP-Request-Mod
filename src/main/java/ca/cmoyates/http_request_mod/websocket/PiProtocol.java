package ca.cmoyates.http_request_mod.websocket;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Version 1 of the JSON protocol shared by the mod and the companion pi extension. */
public final class PiProtocol {
    public static final int VERSION = 1;
    public static final int MAX_FRAME_BYTES = 65_536;
    public static final int MAX_PROMPT_CHARACTERS = 4_096;

    private PiProtocol() {}

    public static String hello(String serverName) {
        JsonObject message = base("hello");
        message.addProperty("client", "http-request-mod");
        message.addProperty("server", truncateUtf16(serverName == null ? "minecraft" : serverName, 256));
        return message.toString();
    }

    public static String playerMessage(UUID requestId, UUID playerId, String playerName, String content) {
        JsonObject message = base("message");
        message.addProperty("requestId", requestId.toString());
        JsonObject player = new JsonObject();
        player.addProperty("id", playerId.toString());
        player.addProperty("name", playerName);
        message.add("player", player);
        message.addProperty("content", content);
        return message.toString();
    }

    public static ServerMessage decodeServerMessage(String json) throws ProtocolException {
        if (json == null
                || json.length() > MAX_FRAME_BYTES
                || json.getBytes(StandardCharsets.UTF_8).length > MAX_FRAME_BYTES) {
            throw new ProtocolException("message exceeds the protocol frame limit");
        }

        final JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                throw new ProtocolException("message must be a JSON object");
            }
            root = parsed.getAsJsonObject();
        } catch (ProtocolException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ProtocolException("message is not valid JSON");
        }

        int version = requiredInteger(root, "version");
        if (version != VERSION) {
            throw new ProtocolException("unsupported protocol version " + version);
        }

        String type = requiredString(root, "type", 32);
        return switch (type) {
            case "chunk" -> new Chunk(
                    requiredRequestId(root),
                    requiredIntegerAtLeast(root, "sequence", 0),
                    requiredString(root, "content", MAX_FRAME_BYTES)
            );
            case "complete" -> new Complete(
                    requiredRequestId(root),
                    requiredIntegerAtLeast(root, "sequence", 0)
            );
            case "error" -> new ErrorMessage(
                    optionalRequestId(root),
                    requiredString(root, "code", 64),
                    requiredString(root, "message", 1_024),
                    optionalBoolean(root, "retryable", false)
            );
            case "status" -> new Status(
                    requiredString(root, "status", 64),
                    optionalString(root, "message", 1_024)
            );
            default -> throw new ProtocolException("unknown message type " + type);
        };
    }

    private static String truncateUtf16(String value, int maximumCodeUnits) {
        if (value.length() <= maximumCodeUnits) {
            return value;
        }
        int end = maximumCodeUnits;
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    private static JsonObject base(String type) {
        JsonObject result = new JsonObject();
        result.addProperty("version", VERSION);
        result.addProperty("type", type);
        return result;
    }

    private static UUID requiredRequestId(JsonObject root) throws ProtocolException {
        UUID id = optionalRequestId(root);
        if (id == null) {
            throw new ProtocolException("requestId is required");
        }
        return id;
    }

    private static UUID optionalRequestId(JsonObject root) throws ProtocolException {
        JsonElement value = root.get("requestId");
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new ProtocolException("requestId must be a UUID string");
        }
        try {
            return UUID.fromString(value.getAsString());
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException("requestId must be a UUID string");
        }
    }

    private static String requiredString(JsonObject root, String key, int maximumLength)
            throws ProtocolException {
        String value = optionalString(root, key, maximumLength);
        if (value == null) {
            throw new ProtocolException(key + " is required");
        }
        return value;
    }

    private static String optionalString(JsonObject root, String key, int maximumLength)
            throws ProtocolException {
        JsonElement value = root.get(key);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new ProtocolException(key + " must be a string");
        }
        String result = value.getAsString();
        if (result.length() > maximumLength) {
            throw new ProtocolException(key + " exceeds its length limit");
        }
        return result;
    }

    private static int requiredIntegerAtLeast(JsonObject root, String key, int minimum)
            throws ProtocolException {
        int result = requiredInteger(root, key);
        if (result < minimum) {
            throw new ProtocolException(key + " must be at least " + minimum);
        }
        return result;
    }

    private static int requiredInteger(JsonObject root, String key) throws ProtocolException {
        JsonElement value = root.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new ProtocolException(key + " must be an integer");
        }
        try {
            String raw = value.getAsString();
            if (!raw.matches("-?(0|[1-9][0-9]*)")) {
                throw new ProtocolException(key + " must be an integer");
            }
            return Integer.parseInt(raw);
        } catch (NumberFormatException exception) {
            throw new ProtocolException(key + " is outside the supported range");
        }
    }

    private static boolean optionalBoolean(JsonObject root, String key, boolean fallback)
            throws ProtocolException {
        JsonElement value = root.get(key);
        if (value == null || value.isJsonNull()) {
            return fallback;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new ProtocolException(key + " must be true or false");
        }
        return value.getAsBoolean();
    }

    public sealed interface ServerMessage permits Chunk, Complete, ErrorMessage, Status {}

    public record Chunk(UUID requestId, int sequence, String content) implements ServerMessage {}

    /** sequence is the number of chunks sent for this request. */
    public record Complete(UUID requestId, int sequence) implements ServerMessage {}

    public record ErrorMessage(UUID requestId, String code, String message, boolean retryable)
            implements ServerMessage {}

    public record Status(String status, String message) implements ServerMessage {}

    public static final class ProtocolException extends Exception {
        public ProtocolException(String message) {
            super(message);
        }
    }
}
