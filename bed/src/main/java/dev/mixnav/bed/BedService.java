package dev.mixnav.bed;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.io.IOException;

/**
 * Stand-in for "whatever music app the driver is using": plays a local file as USAGE_MEDIA,
 * holds a normal music audio focus, and reacts to focus changes the way real players do
 * (pause on transient loss, duck on can-duck loss). All reactions are logged so the POC can
 * prove what the prompt did or did not ask the music app to do.
 */
public final class BedService extends Service {

    public static final String ACTION_START = "dev.mixnav.bed.START";
    public static final String ACTION_STOP = "dev.mixnav.bed.STOP";
    public static final String ACTION_LOG = "dev.mixnav.bed.LOG";
    public static final String EXTRA_PATH = "path";
    public static final String EXTRA_LOG = "line";

    private static final String TAG = "MixNavBed";
    private static final String CHANNEL_ID = "bed";
    private static final int NOTIFICATION_ID = 1;

    private MediaPlayer player;
    private AudioManager audio;
    private AudioFocusRequest focusRequest;
    private boolean pausedByFocus;

    @Override
    public void onCreate() {
        super.onCreate();
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Media bed",
                    NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, notification("Media bed"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);

        if (intent == null || intent.getAction() == null) {
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(intent.getAction())) {
            stopPlayback();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(intent.getAction())) {
            play(intent.getStringExtra(EXTRA_PATH));
        }
        return START_NOT_STICKY;
    }

    private void play(String path) {
        stopPlayback();
        File file = path == null || path.isEmpty()
                ? new File(getExternalFilesDir(null), "bed.ogg")
                : new File(path);
        if (!file.exists()) {
            log("missing audio file: " + file);
            return;
        }
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            mp.setDataSource(file.getAbsolutePath());
            mp.setLooping(true);
            mp.prepare();
            requestFocus();
            mp.start();
            player = mp;
            log("playing " + file.getName() + " as USAGE_MEDIA, looping");
        } catch (IOException | RuntimeException e) {
            log("could not play " + file + ": " + e);
        }
    }

    private void requestFocus() {
        if (focusRequest != null) {
            return;
        }
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setOnAudioFocusChangeListener(this::onFocusChange)
                .build();
        int result = audio.requestAudioFocus(focusRequest);
        log("requestAudioFocus(GAIN) -> " + (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                ? "GRANTED" : "denied (" + result + ")"));
    }

    private void onFocusChange(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                log("focus: LOSS -> stopping (another app took over)");
                stopPlayback();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                log("focus: LOSS_TRANSIENT -> music PAUSED");
                if (player != null) {
                    player.pause();
                    pausedByFocus = true;
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                log("focus: LOSS_TRANSIENT_CAN_DUCK -> music ducked to 20%, still playing");
                if (player != null) {
                    player.setVolume(0.2f, 0.2f);
                }
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                log("focus: GAIN -> volume restored, resuming if paused");
                if (player != null) {
                    player.setVolume(1f, 1f);
                    if (pausedByFocus) {
                        player.start();
                        pausedByFocus = false;
                    }
                }
                break;
            default:
                log("focus: unknown change " + change);
                break;
        }
    }

    private void stopPlayback() {
        if (player != null) {
            try {
                player.stop();
            } catch (IllegalStateException ignored) {
                // already stopped
            }
            player.release();
            player = null;
        }
        if (focusRequest != null) {
            audio.abandonAudioFocusRequest(focusRequest);
            focusRequest = null;
        }
        pausedByFocus = false;
    }

    private Notification notification(String text) {
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Media bed")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void log(String line) {
        Log.i(TAG, line);
        sendBroadcast(new Intent(ACTION_LOG)
                .setPackage(getPackageName())
                .putExtra(EXTRA_LOG, line));
    }

    @Override
    public void onDestroy() {
        stopPlayback();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
