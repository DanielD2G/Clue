package com.clue.tuner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class TunerStoreTest {

    @Test
    public void offersBeforeAnything() {
        TunerStore s = new TunerStore(new MemPrefs());
        assertTrue(s.shouldOffer("pc1", "\"home\""));
        assertNull(s.lookup("pc1", "\"home\""));
    }

    @Test
    public void saveStopsOfferAndIsLookedUp() {
        TunerStore s = new TunerStore(new MemPrefs());
        s.save("pc1", "\"home\"", 55000, "20 ok");
        assertFalse(s.shouldOffer("pc1", "\"home\""));
        assertEquals(Integer.valueOf(55000), s.lookup("pc1", "\"home\""));
        assertEquals("20 ok", s.summary("pc1", "\"home\""));
    }

    @Test
    public void skipStopsOfferWithoutResult() {
        TunerStore s = new TunerStore(new MemPrefs());
        s.markSkipped("pc1", "\"home\"");
        assertFalse(s.shouldOffer("pc1", "\"home\""));
        assertNull(s.lookup("pc1", "\"home\""));
    }

    @Test
    public void resultsArePerPcAndNetwork() {
        TunerStore s = new TunerStore(new MemPrefs());
        s.save("pc1", "\"home\"", 55000, "");
        s.markSkipped("pc2", "wired");
        assertTrue(s.shouldOffer("pc1", "\"office\""));
        assertTrue(s.shouldOffer("pc2", "\"home\""));
        assertTrue(s.shouldOffer("pc1", "wired"));
    }

    @Test
    public void networkKeyFromTransport() {
        assertEquals("\"home\"", TunerStore.networkKeyFrom(TunerStore.TRANSPORT_WIFI, "\"home\""));
        assertEquals("unknown", TunerStore.networkKeyFrom(TunerStore.TRANSPORT_WIFI, "<unknown ssid>"));
        assertEquals("unknown", TunerStore.networkKeyFrom(TunerStore.TRANSPORT_WIFI, null));
        assertEquals("unknown", TunerStore.networkKeyFrom(TunerStore.TRANSPORT_WIFI, ""));
        assertEquals("wired", TunerStore.networkKeyFrom(TunerStore.TRANSPORT_WIRED, null));
        assertEquals("unknown", TunerStore.networkKeyFrom(TunerStore.TRANSPORT_OTHER, "\"x\""));
    }

    @Test
    public void autoOfferOnlyOnWifi() {
        assertTrue(TunerStore.autoOfferAllowed(TunerStore.TRANSPORT_WIFI));
        assertFalse(TunerStore.autoOfferAllowed(TunerStore.TRANSPORT_WIRED));
        assertFalse(TunerStore.autoOfferAllowed(TunerStore.TRANSPORT_OTHER));
    }

    @Test
    public void unquotedSsidIsQuoted() {
        assertEquals("\"home\"", TunerStore.networkKeyFrom(TunerStore.TRANSPORT_WIFI, "home"));
    }

    @Test
    public void lastPcIsRemembered() {
        TunerStore s = new TunerStore(new MemPrefs());
        assertNull(s.lastPc());
        s.setLastPc("pc9");
        assertEquals("pc9", s.lastPc());
    }

    /** Minimal in-memory SharedPreferences: no Robolectric in this project. */
    static final class MemPrefs implements SharedPreferences {
        final Map<String, Object> map = new HashMap<>();

        @Override public Map<String, ?> getAll() { return map; }
        @Override public String getString(String k, String d) { Object v = map.get(k); return v == null ? d : (String) v; }
        @Override public Set<String> getStringSet(String k, Set<String> d) { throw new UnsupportedOperationException(); }
        @Override public int getInt(String k, int d) { Object v = map.get(k); return v == null ? d : (Integer) v; }
        @Override public long getLong(String k, long d) { Object v = map.get(k); return v == null ? d : (Long) v; }
        @Override public float getFloat(String k, float d) { Object v = map.get(k); return v == null ? d : (Float) v; }
        @Override public boolean getBoolean(String k, boolean d) { Object v = map.get(k); return v == null ? d : (Boolean) v; }
        @Override public boolean contains(String k) { return map.containsKey(k); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }

        @Override public Editor edit() {
            final Map<String, Object> pending = new HashMap<>();
            return new Editor() {
                @Override public Editor putString(String k, String v) { pending.put(k, v); return this; }
                @Override public Editor putStringSet(String k, Set<String> v) { pending.put(k, v); return this; }
                @Override public Editor putInt(String k, int v) { pending.put(k, v); return this; }
                @Override public Editor putLong(String k, long v) { pending.put(k, v); return this; }
                @Override public Editor putFloat(String k, float v) { pending.put(k, v); return this; }
                @Override public Editor putBoolean(String k, boolean v) { pending.put(k, v); return this; }
                @Override public Editor remove(String k) { pending.put(k, null); return this; }
                @Override public Editor clear() { map.clear(); return this; }
                @Override public boolean commit() { apply(); return true; }
                @Override public void apply() {
                    for (Map.Entry<String, Object> e : pending.entrySet()) {
                        if (e.getValue() == null) map.remove(e.getKey()); else map.put(e.getKey(), e.getValue());
                    }
                }
            };
        }
    }
}
