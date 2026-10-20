package com.clue.menu;

/**
 * Tells Apollo's clipboard text apart from a host status reply.
 * <p>
 * Apollo answers {@code actions/clipboard} with the clipboard text itself. Hosts without the
 * endpoint (plain Sunshine) answer with their XML status document instead, sometimes with
 * HTTP 200, which Artemis would paste into the local clipboard.
 */
public final class ClipboardResponse {
    private ClipboardResponse() {}

    public static boolean looksLikeHostStatus(String response) {
        if (response == null) {
            return false;
        }
        String trimmed = response.trim();
        return trimmed.startsWith("<?xml") && trimmed.contains("<root") && trimmed.contains("status_code");
    }
}
