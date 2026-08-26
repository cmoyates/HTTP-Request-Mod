package ca.cmoyates.http_request_mod.websocket;

import java.util.ArrayList;
import java.util.List;

/** Coalesces token-sized stream chunks into bounded, line-aware Minecraft chat components. */
public final class StreamingTextBuffer {
    private final int maximumCodePoints;
    private final StringBuilder pending = new StringBuilder();
    private long lastAppendTick;

    public StreamingTextBuffer(int maximumCodePoints) {
        if (maximumCodePoints < 1) {
            throw new IllegalArgumentException("maximumCodePoints must be positive");
        }
        this.maximumCodePoints = maximumCodePoints;
    }

    public List<String> append(String text, long currentTick) {
        List<String> ready = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return ready;
        }
        pending.append(text);
        lastAppendTick = currentTick;
        drainCompleteLinesAndLongSegments(ready);
        return ready;
    }

    public List<String> flushIfStale(long currentTick, long staleAfterTicks) {
        if (pending.isEmpty() || currentTick - lastAppendTick < staleAfterTicks) {
            return List.of();
        }
        return flush();
    }

    public List<String> flush() {
        if (pending.isEmpty()) {
            return List.of();
        }
        List<String> ready = new ArrayList<>();
        while (!pending.isEmpty()) {
            ready.add(removePrefix(maximumCodePoints));
        }
        return ready;
    }

    public boolean isEmpty() {
        return pending.isEmpty();
    }

    private void drainCompleteLinesAndLongSegments(List<String> output) {
        while (true) {
            int newline = pending.indexOf("\n");
            int codePoints = pending.codePointCount(0, pending.length());

            if (newline >= 0 && pending.codePointCount(0, newline) <= maximumCodePoints) {
                String line = pending.substring(0, newline);
                pending.delete(0, newline + 1);
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1);
                }
                output.add(line);
                continue;
            }
            if (codePoints >= maximumCodePoints) {
                output.add(removePrefix(maximumCodePoints));
                continue;
            }
            return;
        }
    }

    private String removePrefix(int maximum) {
        int codePoints = pending.codePointCount(0, pending.length());
        int count = Math.min(maximum, codePoints);
        int end = pending.offsetByCodePoints(0, count);
        String result = pending.substring(0, end);
        pending.delete(0, end);
        return result;
    }
}
