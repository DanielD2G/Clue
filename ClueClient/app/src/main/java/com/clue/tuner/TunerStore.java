package com.clue.tuner;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

/**
 * Tuner results per PC (uuid) and network (quoted SSID, "wired" or "unknown"),
 * in the SharedPreferences file "clue_tuner".
 */
public final class TunerStore {
    public static final String PREFS = "clue_tuner";

    public static final int TRANSPORT_OTHER = 0, TRANSPORT_WIFI = 1, TRANSPORT_WIRED = 2;
    public static final String WIRED = "wired", UNKNOWN = "unknown";

    private static final String LAST_PC = "last_pc";

    private final SharedPreferences prefs;

    public TunerStore(Context context) {
        this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE));
    }

    public TunerStore(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    private static String key(String kind, String pcUuid, String network) {
        return kind + "|" + pcUuid + "|" + network;
    }

    public void save(String pcUuid, String network, int suggestedKbps, String summary) {
        prefs.edit()
                .putInt(key("kbps", pcUuid, network), suggestedKbps)
                .putString(key("summary", pcUuid, network), summary)
                .putLong(key("time", pcUuid, network), System.currentTimeMillis())
                .apply();
    }

    /** The saved suggestion in kbps, or null when this PC + network was never tuned. */
    public Integer lookup(String pcUuid, String network) {
        String k = key("kbps", pcUuid, network);
        return prefs.contains(k) ? prefs.getInt(k, 0) : null;
    }

    public String summary(String pcUuid, String network) {
        return prefs.getString(key("summary", pcUuid, network), null);
    }

    public void markSkipped(String pcUuid, String network) {
        prefs.edit().putBoolean(key("skipped", pcUuid, network), true).apply();
    }

    /** Offer the automatic test only when this PC + network has neither a result nor a skip. */
    public boolean shouldOffer(String pcUuid, String network) {
        return lookup(pcUuid, network) == null
                && !prefs.getBoolean(key("skipped", pcUuid, network), false);
    }

    /** The last PC a stream was launched to, for the entry point in Settings. */
    public void setLastPc(String pcUuid) {
        prefs.edit().putString(LAST_PC, pcUuid).apply();
    }

    public String lastPc() {
        return prefs.getString(LAST_PC, null);
    }

    /** Pure mapping from the active transport and the raw SSID to the store's network key. */
    public static String networkKeyFrom(int transport, String ssid) {
        if (transport == TRANSPORT_WIRED) {
            return WIRED;
        }
        if (transport != TRANSPORT_WIFI || ssid == null || ssid.isEmpty()
                || ssid.equals(WifiManager.UNKNOWN_SSID) || ssid.equals("\"\"")) {
            return UNKNOWN;
        }
        if (ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
            return ssid;
        }
        return "\"" + ssid + "\"";
    }

    /**
     * The key for the current network. Without location permission Android hides the SSID
     * ("&lt;unknown ssid&gt;"), which maps to "unknown"; the tuner works the same.
     */
    @SuppressWarnings("deprecation")
    public static String networkKey(Context context) {
        int transport = TRANSPORT_OTHER;
        String ssid = null;
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
                    if (caps != null) {
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                            transport = TRANSPORT_WIRED;
                        }
                        else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            transport = TRANSPORT_WIFI;
                        }
                    }
                }
                else {
                    NetworkInfo ni = cm.getActiveNetworkInfo();
                    if (ni != null) {
                        if (ni.getType() == ConnectivityManager.TYPE_ETHERNET) {
                            transport = TRANSPORT_WIRED;
                        }
                        else if (ni.getType() == ConnectivityManager.TYPE_WIFI) {
                            transport = TRANSPORT_WIFI;
                        }
                    }
                }
            }
            if (transport == TRANSPORT_WIFI) {
                WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                ssid = info != null ? info.getSSID() : null;
            }
        } catch (SecurityException | NullPointerException ignored) {
        }
        return networkKeyFrom(transport, ssid);
    }
}
