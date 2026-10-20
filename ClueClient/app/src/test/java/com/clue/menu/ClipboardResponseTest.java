package com.clue.menu;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ClipboardResponseTest {
    @Test
    public void sunshineNotFoundIsAHostStatus() {
        assertTrue(ClipboardResponse.looksLikeHostStatus(
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<root status_code=\"404\" status_message=\"Not Found\"/>"));
    }

    @Test
    public void clipboardTextIsNotAHostStatus() {
        assertFalse(ClipboardResponse.looksLikeHostStatus("hello world"));
        assertFalse(ClipboardResponse.looksLikeHostStatus(""));
        assertFalse(ClipboardResponse.looksLikeHostStatus(null));
        // XML the user copied on the host is still clipboard text
        assertFalse(ClipboardResponse.looksLikeHostStatus("<?xml version=\"1.0\"?><project><name>x</name></project>"));
    }
}
