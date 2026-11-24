package dev.mixnav;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import dev.mixnav.Config.Policy;

public final class MainActivity extends Activity {

    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private TextView logView;
    private ScrollView logScroll;
    private TextView gainLabel;
    private boolean receiverRegistered;

    private final BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String line = intent.getStringExtra(PromptService.EXTRA_LOG);
            if (line != null) {
                appendLog(line);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        log("device: " + Build.MANUFACTURER + " " + Build.MODEL + ", API " + Build.VERSION.SDK_INT);
        log("selected policy: " + Config.policy(this).label + " — " + Config.policy(this).detail);
        log("start Spotify/YouTube Music, then fire a prompt. MIX must leave the music playing.");
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 7);
        }
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    /**
     * Scriptable entry point so the POC can be driven from adb:
     * {@code am start -n dev.mixnav/.MainActivity -a dev.mixnav.action.SPEAK --es policy DUCK}
     */
    private void handleIntent(Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String policyName = intent.getStringExtra("policy");
        if (policyName != null) {
            try {
                Policy policy = Policy.valueOf(policyName.toUpperCase(Locale.US));
                Config.setPolicy(this, policy);
                log("policy -> " + policy.label + " (" + policy.detail + ")");
            } catch (IllegalArgumentException e) {
                log("unknown policy extra: " + policyName);
            }
        }
        String action = intent.getAction();
        if (PromptService.ACTION_SPEAK.equals(action)) {
            sendAction(action, true, intent.getStringExtra(PromptService.EXTRA_TEXT));
        } else if (PromptService.ACTION_START.equals(action) || PromptService.ACTION_STOP.equals(action)) {
            sendAction(action, !PromptService.ACTION_STOP.equals(action), null);
        }
    }

    private View buildUi() {
        int pad = dp(20);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("MixNav");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setPadding(0, 0, 0, dp(4));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Navigation prompts mixed over your music. The prompt is synthesized to a WAV "
                + "and played by this app; the TTS engine is not allowed to play or take focus.\n\n"
                + "MIX requests no audio focus at all, so the mixer plays the prompt on top of the "
                + "music instead of the music being paused (PAUSE) or ducked (DUCK).");
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        subtitle.setPadding(0, 0, 0, pad);
        root.addView(subtitle);

        TextView policyLabel = new TextView(this);
        policyLabel.setText("Audio focus strategy");
        policyLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        policyLabel.setPadding(0, 0, 0, dp(8));
        root.addView(policyLabel);

        RadioGroup group = new RadioGroup(this);
        Policy[] policies = Policy.values();
        for (int i = 0; i < policies.length; i++) {
            Policy policy = policies[i];
            RadioButton button = new RadioButton(this);
            button.setText(policy.label + " — " + policy.detail);
            button.setId(i + 1);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            group.addView(button);
            if (Config.policy(this) == policy) {
                group.check(i + 1);
            }
        }
        group.setOnCheckedChangeListener((g, checkedId) -> {
            Policy policy = policies[checkedId - 1];
            Config.setPolicy(this, policy);
            log("policy -> " + policy.label + " (" + policy.detail + ")");
        });
        root.addView(group);

        gainLabel = new TextView(this);
        gainLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        gainLabel.setPadding(0, pad, 0, dp(4));
        root.addView(gainLabel);

        SeekBar gain = new SeekBar(this);
        gain.setMax(100);
        gain.setProgress(Math.round(Config.gain(this) * 100));
        updateGainLabel(Math.round(Config.gain(this) * 100));
        gain.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                if (fromUser) {
                    Config.setGainPercent(MainActivity.this, value);
                }
                updateGainLabel(value);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        root.addView(gain);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, pad, 0, pad);

        Button speak = new Button(this);
        speak.setText("Speak prompt");
        speak.setOnClickListener(v -> sendAction(PromptService.ACTION_SPEAK, true, null));
        buttons.addView(speak, weighted());

        Button start = new Button(this);
        start.setText("Start route");
        start.setOnClickListener(v -> sendAction(PromptService.ACTION_START, true, null));
        buttons.addView(start, weighted());

        Button stop = new Button(this);
        stop.setText("Stop");
        stop.setOnClickListener(v -> sendAction(PromptService.ACTION_STOP, false, null));
        buttons.addView(stop, weighted());

        root.addView(buttons);

        logScroll = new ScrollView(this);
        logScroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300)));
        logView = new TextView(this);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setGravity(Gravity.START);
        logScroll.addView(logView);
        root.addView(logScroll);

        return scroll;
    }

    private static LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(0, 0, 8, 0);
        return params;
    }

    private void updateGainLabel(int percent) {
        gainLabel.setText("Prompt gain: " + percent + "% of the media stream volume");
    }

    private void sendAction(String action, boolean startService, String text) {
        Intent intent = new Intent(this, PromptService.class).setAction(action);
        if (PromptService.ACTION_SPEAK.equals(action)) {
            intent.putExtra(PromptService.EXTRA_TEXT,
                    text == null || text.isEmpty() ? Route.MANEUVERS[0] : text);
        }
        if (startService) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void appendLog(String line) {
        log(clock.format(new Date()) + "  " + line);
    }

    private void log(String line) {
        logView.append(line + "\n");
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(PromptService.ACTION_LOG);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(logReceiver, filter);
        }
        receiverRegistered = true;
    }

    @Override
    protected void onStop() {
        if (receiverRegistered) {
            unregisterReceiver(logReceiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
