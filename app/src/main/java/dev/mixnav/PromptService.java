package dev.mixnav;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/**
 * Foreground service that keeps prompt playback alive while the phone is in a pocket / other app.
 * Also drives the simulated route.
 */
public final class PromptService extends Service {

    public static final String ACTION_SPEAK = "dev.mixnav.action.SPEAK";
    public static final String ACTION_START = "dev.mixnav.action.START";
    public static final String ACTION_STOP = "dev.mixnav.action.STOP";
    public static final String ACTION_LOG = "dev.mixnav.action.LOG";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_LOG = "line";

    private static final String TAG = "MixNav";
    private static final String CHANNEL_ID = "prompts";
    private static final int NOTIFICATION_ID = 1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private PromptPlayer player;
    private boolean routeRunning;
    private int routeIndex;
    private String status = "Idle";

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        player = new PromptPlayer(this, this::log);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification(status),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);

        if (intent == null || intent.getAction() == null) {
            return START_NOT_STICKY;
        }

        switch (intent.getAction()) {
            case ACTION_SPEAK:
                speakOnce(intent.getStringExtra(EXTRA_TEXT));
                break;
            case ACTION_START:
                startRoute();
                break;
            case ACTION_STOP:
                stopEverything();
                break;
            default:
                break;
        }
        return START_NOT_STICKY;
    }

    private void speakOnce(String text) {
        stopRoute();
        if (text == null || text.isEmpty()) {
            text = Route.MANEUVERS[0];
        }
        updateStatus(text);
        player.speak(text, this::stopWhenIdle);
    }

    private void startRoute() {
        if (routeRunning) {
            return;
        }
        routeRunning = true;
        routeIndex = 0;
        log("route: started");
        speakNext();
    }

    private void speakNext() {
        if (!routeRunning) {
            return;
        }
        if (routeIndex >= Route.MANEUVERS.length) {
            routeRunning = false;
            updateStatus("Route complete");
            log("route: complete");
            handler.postDelayed(this::stopWhenIdle, 2500);
            return;
        }
        String text = Route.MANEUVERS[routeIndex++];
        updateStatus(text);
        player.speak(text, () -> handler.postDelayed(this::speakNext, 4000));
    }

    private void stopRoute() {
        routeRunning = false;
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.cancel();
        }
    }

    private void stopEverything() {
        stopRoute();
        stopWhenIdle();
    }

    private void stopWhenIdle() {
        if (routeRunning) {
            return;
        }
        updateStatus("Idle");
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void updateStatus(String text) {
        status = text;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(status));
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("MixNav")
                .setContentText(text)
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void createChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Navigation prompts",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Foreground service for prompt playback");
        manager.createNotificationChannel(channel);
    }

    private void log(String line) {
        Log.i(TAG, line);
        Intent broadcast = new Intent(ACTION_LOG)
                .setPackage(getPackageName())
                .putExtra(EXTRA_LOG, line);
        sendBroadcast(broadcast);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
