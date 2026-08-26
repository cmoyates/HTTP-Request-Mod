package ca.cmoyates.http_request_mod.websocket;

import ca.cmoyates.http_request_mod.config.WebSocketSettings;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** Validation, authentication, and safe-display policy for configured endpoints. */
public final class EndpointPolicy {
    private static final Set<String> WEB_SOCKET_SCHEMES = Set.of("ws", "wss");

    private EndpointPolicy() {}

    public static URI validate(String value, WebSocketSettings settings) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("no WebSocket endpoint is configured");
        }

        final URI endpoint;
        try {
            endpoint = URI.create(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("endpoint is not a valid URI");
        }

        String scheme = endpoint.getScheme();
        if (scheme == null
                || !WEB_SOCKET_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))
                || endpoint.getHost() == null
                || endpoint.getHost().isBlank()
                || endpoint.getPort() == 0
                || endpoint.getPort() > 65_535
                || endpoint.getUserInfo() != null
                || endpoint.getFragment() != null) {
            throw new IllegalArgumentException(
                    "endpoint must be an absolute ws:// or wss:// URL without credentials or a fragment"
            );
        }

        if (settings.hasSharedToken()
                && (settings.sharedToken().length() > 4_096
                || settings.sharedToken().codePoints().anyMatch(codePoint -> codePoint <= 0x20 || codePoint >= 0x7f))) {
            throw new IllegalArgumentException("sharedToken contains invalid HTTP header characters");
        }

        boolean loopback = isLoopbackHost(endpoint.getHost());
        if (!loopback && !"wss".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("non-loopback endpoints must use wss://");
        }
        if (!loopback && !settings.hasSharedToken()) {
            throw new IllegalArgumentException(
                    "non-loopback endpoints require sharedToken in the mod configuration"
            );
        }

        return endpoint;
    }

    /** Uses literal host rules instead of DNS resolution, avoiding DNS-rebinding surprises. */
    public static boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (normalized.equals("localhost") || normalized.endsWith(".localhost")) {
            return true;
        }
        if (normalized.equals("::1") || normalized.equals("0:0:0:0:0:0:0:1")) {
            return true;
        }

        String[] octets = normalized.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        try {
            if (Integer.parseInt(octets[0]) != 127) {
                return false;
            }
            for (String octet : octets) {
                if (octet.isEmpty()) {
                    return false;
                }
                int number = Integer.parseInt(octet);
                if (number < 0 || number > 255) {
                    return false;
                }
            }
            return true;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    /**
     * Returns an endpoint description safe for chat/logs. URI user-info is rejected and query
     * values are always redacted because they often contain credentials.
     */
    public static String display(URI endpoint) {
        return display(endpoint, "");
    }

    public static String display(URI endpoint, String credential) {
        if (endpoint == null) {
            return "(not configured)";
        }
        String host = endpoint.getHost();
        String displayHost = host;
        if (host != null && host.contains(":") && !(host.startsWith("[") && host.endsWith("]"))) {
            displayHost = "[" + host + "]";
        }
        StringBuilder result = new StringBuilder()
                .append(endpoint.getScheme())
                .append("://")
                .append(displayHost);
        if (endpoint.getPort() >= 0) {
            result.append(':').append(endpoint.getPort());
        }
        String path = endpoint.getRawPath();
        if (path != null && !path.isEmpty()) {
            result.append(path);
        }
        if (endpoint.getRawQuery() != null) {
            result.append("?<redacted>");
        }
        String displayed = result.toString();
        return credential == null || credential.isBlank()
                ? displayed
                : displayed.replace(credential, "<redacted>");
    }
}
