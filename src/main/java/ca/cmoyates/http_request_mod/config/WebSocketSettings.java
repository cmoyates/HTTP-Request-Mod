package ca.cmoyates.http_request_mod.config;

/**
 * Persisted settings for the pi WebSocket integration.
 *
 * <p>This class deliberately does not implement {@code toString}; the shared token must never be
 * included in diagnostics by accident.</p>
 */
public final class WebSocketSettings {
    public static final String DEFAULT_ENDPOINT = "ws://127.0.0.1:8765";
    public static final int DEFAULT_PROMPT_TIMEOUT_SECONDS = 120;
    public static final int MIN_PROMPT_TIMEOUT_SECONDS = 10;
    public static final int MAX_PROMPT_TIMEOUT_SECONDS = 1800;

    private final String endpoint;
    private final boolean automaticConnect;
    private final String sharedToken;
    private final int promptTimeoutSeconds;

    public WebSocketSettings(
            String endpoint,
            boolean automaticConnect,
            String sharedToken,
            int promptTimeoutSeconds
    ) {
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        this.automaticConnect = automaticConnect;
        this.sharedToken = sharedToken == null ? "" : sharedToken;
        this.promptTimeoutSeconds = Math.max(
                MIN_PROMPT_TIMEOUT_SECONDS,
                Math.min(MAX_PROMPT_TIMEOUT_SECONDS, promptTimeoutSeconds)
        );
    }

    public static WebSocketSettings defaults() {
        return new WebSocketSettings(
                DEFAULT_ENDPOINT,
                false,
                "",
                DEFAULT_PROMPT_TIMEOUT_SECONDS
        );
    }

    public String endpoint() {
        return endpoint;
    }

    public boolean automaticConnect() {
        return automaticConnect;
    }

    /** Treat this value as a credential. Never include it in chat, logs, or exception messages. */
    public String sharedToken() {
        return sharedToken;
    }

    public boolean hasSharedToken() {
        return !sharedToken.isBlank();
    }

    public int promptTimeoutSeconds() {
        return promptTimeoutSeconds;
    }

    public WebSocketSettings withEndpoint(String newEndpoint) {
        return new WebSocketSettings(newEndpoint, automaticConnect, sharedToken, promptTimeoutSeconds);
    }

    public WebSocketSettings withAutomaticConnect(boolean enabled) {
        return new WebSocketSettings(endpoint, enabled, sharedToken, promptTimeoutSeconds);
    }
}
