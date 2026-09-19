package com.flashsignal.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

final class StateStore {
    private static final String PREF = "flash_signal_mobile";
    private static final String KEY_ALL = "all_matches_mode";
    private static final String KEY_ENABLED = "enabled";
    private static final String STATE_FILE = "live_state.json";

    private static final String DEFAULT_STATE =
            "{\"enabled\":true,\"allMatchesMode\":false,\"monitorStatus\":\"Başlatılıyor\",\"matches\":[],\"stats\":[],\"signals\":[]}";

    private StateStore() {}

    static SharedPreferences prefs(Context c) {
        // Settings are owned by MonitorService (:monitor process).
        // MainActivity sends changes through service Intents.
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static AtomicFile stateFile(Context c) {
        File file = new File(c.getFilesDir(), STATE_FILE);
        return new AtomicFile(file);
    }

    static String getState(Context c) {
        AtomicFile af = stateFile(c);
        if (!af.getBaseFile().exists()) return DEFAULT_STATE;

        try (FileInputStream in = af.openRead()) {
            byte[] data = new byte[(int) Math.min(2_000_000L, Math.max(0L, af.getBaseFile().length()))];
            int total = 0;
            while (total < data.length) {
                int n = in.read(data, total, data.length - total);
                if (n < 0) break;
                total += n;
            }
            if (total <= 0) return DEFAULT_STATE;
            return new String(data, 0, total, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return DEFAULT_STATE;
        }
    }

    static void setState(Context c, String json) {
        AtomicFile af = stateFile(c);
        FileOutputStream out = null;
        try {
            out = af.startWrite();
            byte[] data = json.getBytes(StandardCharsets.UTF_8);
            out.write(data);
            out.flush();
            af.finishWrite(out);
        } catch (Exception e) {
            if (out != null) af.failWrite(out);
        }
    }

    static boolean isAllMatchesMode(Context c) {
        return prefs(c).getBoolean(KEY_ALL, false);
    }

    static void setAllMatchesMode(Context c, boolean value) {
        prefs(c).edit().putBoolean(KEY_ALL, value).apply();
    }

    static boolean isEnabled(Context c) {
        return prefs(c).getBoolean(KEY_ENABLED, true);
    }

    static void setEnabled(Context c, boolean value) {
        prefs(c).edit().putBoolean(KEY_ENABLED, value).apply();
    }
}
