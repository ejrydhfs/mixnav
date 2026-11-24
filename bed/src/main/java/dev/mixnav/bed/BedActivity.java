package dev.mixnav.bed;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class BedActivity extends Activity {

    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private TextView logView;
    private ScrollView logScroll;
    private boolean registered;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String line = intent.getStringExtra(BedService.EXTRA_LOG);
            if (line != null) {
                log(clock.format(new Date()) + "  " + line);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        File file = new File(getExternalFilesDir(null), "bed.ogg");
        log("file: " + file + " (" + (file.exists() ? file.length() + " bytes" : "missing") + ")");
        log("push audio with: adb push bed.ogg " + file.getAbsolutePath());
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        if (BedService.ACTION_START.equals(intent.getAction())) {
            startBed(intent.getStringExtra(BedService.EXTRA_PATH));
        } else if (BedService.ACTION_STOP.equals(intent.getAction())) {
            stopService(new Intent(this, BedService.class));
        }
    }

    private View buildUi() {
        int pad = dp(16);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Media Bed (test harness)");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Plays a local file as ordinary media, holds AUDIOFOCUS_GAIN, and logs every "
                + "focus change. Use it as the 'music app' while the navigation POC speaks.");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hint.setPadding(0, dp(8), 0, dp(12));
        root.addView(hint);

        LinearLayout buttons = new LinearLayout(this);
        Button play = new Button(this);
        play.setText("Play");
        play.setOnClickListener(v -> startBed(null));
        buttons.addView(play);
        Button stop = new Button(this);
        stop.setText("Stop");
        stop.setOnClickListener(v -> stopService(new Intent(this, BedService.class)));
        buttons.addView(stop);
        root.addView(buttons, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        logScroll = new ScrollView(this);
        logScroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300)));
        logView = new TextView(this);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logScroll.addView(logView);
        root.addView(logScroll);
        return scroll;
    }

    private void startBed(String path) {
        Intent intent = new Intent(this, BedService.class).setAction(BedService.ACTION_START);
        if (path != null) {
            intent.putExtra(BedService.EXTRA_PATH, path);
        }
        startForegroundService(intent);
    }

    private void log(String line) {
        logView.append(line + "\n");
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(BedService.ACTION_LOG);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
        registered = true;
    }

    @Override
    protected void onStop() {
        if (registered) {
            unregisterReceiver(receiver);
            registered = false;
        }
        super.onStop();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
