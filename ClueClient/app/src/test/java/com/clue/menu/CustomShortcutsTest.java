package com.clue.menu;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class CustomShortcutsTest {
    @Test
    public void parsesHexAndVkNames() throws Exception {
        String json = "{\"data\":[" +
                "{\"id\":\"1\",\"name\":\"Ctrl+Shift+Esc\",\"keys\":[\"VK_LCONTROL\",\"VK_LSHIFT\",\"VK_ESCAPE\"]}," +
                "{\"id\":\"2\",\"name\":\"Hex F5\",\"keys\":[\"0x74\"]}" +
                "]}";
        List<CustomShortcuts.Entry> out = new ArrayList<>();
        CustomShortcuts.parse(json, out);

        assertEquals(2, out.size());
        assertEquals("Ctrl+Shift+Esc", out.get(0).name);
        assertArrayEquals(new short[]{0xA2, 0xA0, 0x1B}, out.get(0).keys);
        assertEquals("Hex F5", out.get(1).name);
        assertArrayEquals(new short[]{0x74}, out.get(1).keys);
    }

    @Test
    public void emptyFileAddsNothing() throws Exception {
        List<CustomShortcuts.Entry> out = new ArrayList<>();
        CustomShortcuts.parse("{\"data\":[]}", out);
        CustomShortcuts.parse("{}", out);
        assertTrue(out.isEmpty());
    }

    @Test
    public void keepsShortcutsBeforeABadOne() {
        String json = "{\"data\":[" +
                "{\"name\":\"Good\",\"keys\":[\"VK_F11\"]}," +
                "{\"name\":\"Bad\",\"keys\":[\"F12\"]}," +
                "{\"name\":\"After\",\"keys\":[\"VK_F11\"]}" +
                "]}";
        List<CustomShortcuts.Entry> out = new ArrayList<>();
        try {
            CustomShortcuts.parse(json, out);
            fail("expected the unknown key to throw");
        } catch (Exception expected) {
            // Same as Artemis: stop at the bad shortcut
        }
        assertEquals(1, out.size());
        assertEquals("Good", out.get(0).name);
        assertArrayEquals(new short[]{0x7A}, out.get(0).keys);
    }

    @Test(expected = NoSuchFieldException.class)
    public void unknownVkNameThrows() throws Exception {
        CustomShortcuts.parseKey("VK_DOES_NOT_EXIST");
    }

    @Test(expected = Exception.class)
    public void malformedJsonThrows() throws Exception {
        CustomShortcuts.parse("not json", new ArrayList<>());
    }
}
