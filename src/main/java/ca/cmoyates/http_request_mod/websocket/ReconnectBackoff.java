package ca.cmoyates.http_request_mod.websocket;

/** Exponential reconnect delay capped at 30 seconds. */
public final class ReconnectBackoff {
    public static final int MAXIMUM_DELAY_SECONDS = 30;

    private ReconnectBackoff() {}

    public static int delaySeconds(int attempt) {
        if (attempt <= 1) {
            return 1;
        }
        int exponent = Math.min(attempt - 1, 5);
        return Math.min(1 << exponent, MAXIMUM_DELAY_SECONDS);
    }
}
