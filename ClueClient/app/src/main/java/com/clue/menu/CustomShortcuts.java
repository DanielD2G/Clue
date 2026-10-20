package com.clue.menu;

import com.limelight.utils.KeyConfigHelper;
import com.limelight.utils.KeyMapper;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Custom "Send special keys" shortcuts imported from an Artemis shortcut file.
 * <p>
 * Same parsing as Artemis' GameMenu.showSpecialKeysMenu(): each key is either a literal hex
 * value ("0x1B") or the name of a VK_* constant in {@link KeyMapper} ("VK_ESCAPE"). Moved out of
 * GameMenu so it can be unit tested.
 */
public final class CustomShortcuts {
    public static final class Entry {
        public final String name;
        public final short[] keys;

        Entry(String name, short[] keys) {
            this.name = name;
            this.keys = keys;
        }
    }

    private CustomShortcuts() {}

    /**
     * Appends the shortcuts in {@code json} to {@code out}. Like Artemis, it stops at the first
     * shortcut it can't read and throws, keeping the shortcuts it already appended.
     */
    public static void parse(String json, List<Entry> out) throws Exception {
        KeyConfigHelper.ShortcutFile shortcutFile = KeyConfigHelper.parseShortcutFile(json);
        if (shortcutFile == null || shortcutFile.data == null || shortcutFile.data.isEmpty()) {
            return;
        }

        for (KeyConfigHelper.Shortcut sc : shortcutFile.data) {
            List<String> keys = sc.keys;
            short[] keyCodes = new short[keys.size()];

            for (int i = 0; i < keys.size(); i++) {
                keyCodes[i] = (short) parseKey(keys.get(i));
            }

            out.add(new Entry(sc.name, keyCodes));
        }
    }

    static int parseKey(String code) throws Exception {
        if (code.startsWith("0x")) {               // literal hex value
            return Integer.parseInt(code.substring(2), 16);
        } else if (code.startsWith("VK_")) {       // symbolic constant in KeyMapper
            Field field = KeyMapper.class.getDeclaredField(code);
            return field.getInt(null);
        } else {                                   // unsupported
            throw new IllegalArgumentException("Unknown key code: " + code);
        }
    }
}
