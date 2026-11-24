package dev.mixnav;

import android.content.Context;
import android.content.SharedPreferences;

/** Persisted knobs: which audio-focus strategy to use, and prompt gain. */
public final class Config {

    public enum Policy {
        MIX("MIX", "no focus request — media app is never told anything"),
        DUCK("DUCK", "AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK"),
        PAUSE("PAUSE", "AUDIOFOCUS_GAIN_TRANSIENT (classic nav: pauses music)"),
        CALL("CALL", "MODE_IN_COMMUNICATION / USAGE_VOICE_COMMUNICATION");

        public final String label;
        public final String detail;

        Policy(String label, String detail) {
            this.label = label;
            this.detail = detail;
        }
    }

    private static final String FILE = "mixnav";
    private static final String KEY_POLICY = "policy";
    private static final String KEY_GAIN = "gain";

    private Config() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static Policy policy(Context context) {
        String name = prefs(context).getString(KEY_POLICY, Policy.MIX.name());
        try {
            return Policy.valueOf(name);
        } catch (IllegalArgumentException e) {
            return Policy.MIX;
        }
    }

    public static void setPolicy(Context context, Policy policy) {
        prefs(context).edit().putString(KEY_POLICY, policy.name()).apply();
    }

    /** Prompt gain relative to the media stream, 0.0..1.0. */
    public static float gain(Context context) {
        return prefs(context).getInt(KEY_GAIN, 80) / 100f;
    }

    public static void setGainPercent(Context context, int percent) {
        prefs(context).edit().putInt(KEY_GAIN, Math.max(0, Math.min(100, percent))).apply();
    }
}
