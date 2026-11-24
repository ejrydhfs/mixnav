package dev.mixnav;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import dev.mixnav.Config.Policy;

/**
 * Speaks navigation prompts with the focus behaviour selected in {@link Config}.
 *
 * <p>The point of the POC: audio focus is what pauses/ducks other apps. In MIX mode this class
 * never touches focus, so the mixer simply plays the prompt on top of whatever is already
 * playing. Text is synthesized to a WAV by the TTS engine and played by us, so the engine's own
 * player (which may request focus) is out of the loop.
 */
public final class PromptPlayer {

    public interface Sink {
        void log(String line);
    }

    private static final int TTS_PENDING = 0;
    private static final int TTS_READY = 1;
    private static final int TTS_FAILED = 2;

    private final Context context;
    private final Sink sink;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioManager audio;

    private final TextToSpeech tts;
    private volatile int ttsState = TTS_PENDING;
    private String queuedText;
    private Runnable queuedDone;

    private String pendingUtterance;
    private MediaPlayer player;
    private AudioFocusRequest focusRequest;
    private Policy activePolicy;
    private int previousMode = AudioManager.MODE_INVALID;
    private Runnable pendingDone;

    public PromptPlayer(Context context, Sink sink) {
        this.context = context.getApplicationContext();
        this.sink = sink;
        this.audio = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);

        this.tts = new TextToSpeech(this.context, status -> main.post(() -> onTtsInit(status)));
        this.tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
            }

            @Override
            public void onDone(String utteranceId) {
                main.post(() -> onSynthesized(utteranceId));
            }

            @Override
            public void onError(String utteranceId) {
                main.post(() -> {
                    if (utteranceId != null && utteranceId.equals(pendingUtterance)) {
                        sink.log("   TTS synthesis error, using fallback chime");
                        pendingUtterance = null;
                        play(toneFile());
                    }
                });
            }
        });
    }

    private void onTtsInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            ttsState = TTS_READY;
            String engine = tts.getDefaultEngine();
            sink.log("TTS ready" + (engine == null ? "" : " (" + engine + ")"));
        } else {
            ttsState = TTS_FAILED;
            sink.log("TTS unavailable -> synthesized chime fallback");
        }
        if (queuedText != null) {
            String text = queuedText;
            Runnable done = queuedDone;
            queuedText = null;
            queuedDone = null;
            speak(text, done);
        }
    }

    private void onSynthesized(String utteranceId) {
        if (utteranceId == null || !utteranceId.equals(pendingUtterance)) {
            return;
        }
        pendingUtterance = null;
        File wav = promptFile();
        if (wav.length() > 1024) {
            play(wav);
        } else {
            sink.log("   TTS produced no audio, using fallback chime");
            play(toneFile());
        }
    }

    /** Speaks {@code text}; {@code done} runs after playback ends (or immediately on failure). */
    public void speak(final String text, final Runnable done) {
        main.post(() -> {
            cancelInternal();
            Policy policy = Config.policy(context);
            sink.log("> prompt [" + policy.label + "] \"" + text + "\"");
            pendingDone = done;

            if (ttsState == TTS_PENDING) {
                queuedText = text;
                queuedDone = done;
                sink.log("   TTS still initializing, queued");
                return;
            }
            if (ttsState == TTS_READY) {
                File wav = promptFile();
                //noinspection ResultOfMethodCallIgnored
                wav.delete();
                pendingUtterance = "u" + System.nanoTime();
                int result = tts.synthesizeToFile(text, null, wav, pendingUtterance);
                if (result == TextToSpeech.SUCCESS) {
                    sink.log("   synthesizing to WAV (engine playback bypassed)");
                    return;
                }
                pendingUtterance = null;
                sink.log("   synthesizeToFile failed (" + result + ")");
            }
            play(toneFile());
        });
    }

    public void cancel() {
        main.post(this::cancelInternal);
    }

    private void cancelInternal() {
        if (player != null) {
            try {
                player.stop();
            } catch (IllegalStateException ignored) {
                // already stopped
            }
            player.release();
            player = null;
        }
        endPolicy();
        pendingUtterance = null;
        pendingDone = null;
        queuedText = null;
        queuedDone = null;
    }

    private void play(final File file) {
        final Policy policy = Config.policy(context);
        main.post(() -> {
            beginPolicy(policy);
            MediaPlayer mp = new MediaPlayer();
            try {
                mp.setAudioAttributes(attributesFor(policy));
                mp.setDataSource(file.getAbsolutePath());
                float gain = Config.gain(context);
                mp.setVolume(gain, gain);
                mp.setOnCompletionListener(p -> onPlaybackEnd("completed"));
                mp.setOnErrorListener((p, what, extra) -> {
                    sink.log("   player error " + what + "/" + extra);
                    onPlaybackEnd("error");
                    return true;
                });
                mp.prepare();
                player = mp;
                mp.start();
                sink.log("   playing " + file.getName() + " usage=" + usageName(policy)
                        + " gain=" + Math.round(gain * 100) + "%");
            } catch (IOException | RuntimeException e) {
                mp.release();
                sink.log("   playback failed: " + e);
                onPlaybackEnd("failure");
            }
        });
    }

    private void onPlaybackEnd(String reason) {
        if (player != null) {
            player.release();
            player = null;
        }
        endPolicy();
        sink.log("   " + reason);
        Runnable done = pendingDone;
        pendingDone = null;
        if (done != null) {
            done.run();
        }
    }

    private void beginPolicy(Policy policy) {
        activePolicy = policy;
        switch (policy) {
            case MIX:
                sink.log("   focus: NONE requested — the media app is never asked to duck or pause");
                break;
            case DUCK:
                requestFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, "GAIN_TRANSIENT_MAY_DUCK");
                break;
            case PAUSE:
                requestFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, "GAIN_TRANSIENT");
                break;
            case CALL:
                previousMode = audio.getMode();
                audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
                forceSpeaker(true);
                sink.log("   mode: " + previousMode + " -> MODE_IN_COMMUNICATION (voice path)");
                break;
        }
    }

    private void requestFocus(int gain, String label) {
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        focusRequest = new AudioFocusRequest.Builder(gain)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(change -> sink.log("   focus change: " + focusName(change)))
                .build();
        int result = audio.requestAudioFocus(focusRequest);
        sink.log("   focus: " + label + " -> " + requestName(result));
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            audio.abandonAudioFocusRequest(focusRequest);
            focusRequest = null;
        }
    }

    private void endPolicy() {
        if (activePolicy == null) {
            return;
        }
        if (focusRequest != null) {
            audio.abandonAudioFocusRequest(focusRequest);
            focusRequest = null;
        }
        if (activePolicy == Policy.CALL) {
            forceSpeaker(false);
            if (previousMode != AudioManager.MODE_INVALID && previousMode != AudioManager.MODE_IN_COMMUNICATION) {
                audio.setMode(previousMode);
            }
            previousMode = AudioManager.MODE_INVALID;
        }
        activePolicy = null;
    }

    private void forceSpeaker(boolean on) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (on) {
                for (AudioDeviceInfo device : audio.getAvailableCommunicationDevices()) {
                    if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                        audio.setCommunicationDevice(device);
                        return;
                    }
                }
                sink.log("   no builtin speaker among communication devices");
            } else {
                audio.clearCommunicationDevice();
            }
        } else {
            audio.setSpeakerphoneOn(on);
        }
    }

    private static AudioAttributes attributesFor(Policy policy) {
        int usage = policy == Policy.CALL
                ? AudioAttributes.USAGE_VOICE_COMMUNICATION
                : AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE;
        return new AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
    }

    private static String usageName(Policy policy) {
        return policy == Policy.CALL ? "VOICE_COMMUNICATION" : "NAVIGATION_GUIDANCE";
    }

    private File promptFile() {
        return new File(context.getCacheDir(), "prompt.wav");
    }

    /** Two-tone chime written as a WAV so the POC still works on devices without a TTS engine. */
    private File toneFile() {
        File file = new File(context.getCacheDir(), "prompt_tone.wav");
        if (file.length() > 44) {
            return file;
        }
        int rate = 22050;
        // ~2s placeholder prompt so the mixing window is observable in dumpsys on devices without TTS.
        double[][] sequence = {{660, 0.25}, {0, 0.10}, {880, 0.35}, {0, 0.15},
                {660, 0.25}, {0, 0.10}, {1046, 0.45}, {0, 0.30}};
        int total = 0;
        for (double[] step : sequence) {
            total += (int) (step[1] * rate);
        }
        short[] pcm = new short[total];
        int position = 0;
        for (double[] step : sequence) {
            int count = (int) (step[1] * rate);
            for (int i = 0; i < count; i++) {
                double t = (double) i / rate;
                double attack = Math.min(1.0, i / (0.01 * rate));
                double release = Math.min(1.0, (count - i) / (0.02 * rate));
                double envelope = Math.max(0.0, Math.min(attack, release));
                pcm[position++] = (short) (Math.sin(2 * Math.PI * step[0] * t) * 0.35 * 32767 * envelope);
            }
        }
        writeWav(file, pcm, rate);
        return file;
    }

    private void writeWav(File file, short[] pcm, int rate) {
        ByteBuffer buffer = ByteBuffer.allocate(44 + pcm.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(36 + pcm.length * 2);
        buffer.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        buffer.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(16);
        buffer.putShort((short) 1);
        buffer.putShort((short) 1);
        buffer.putInt(rate);
        buffer.putInt(rate * 2);
        buffer.putShort((short) 2);
        buffer.putShort((short) 16);
        buffer.put("data".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(pcm.length * 2);
        for (short sample : pcm) {
            buffer.putShort(sample);
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(buffer.array());
        } catch (IOException e) {
            sink.log("   could not write fallback chime: " + e);
        }
    }

    private static String requestName(int result) {
        switch (result) {
            case AudioManager.AUDIOFOCUS_REQUEST_GRANTED:
                return "GRANTED";
            case AudioManager.AUDIOFOCUS_REQUEST_FAILED:
                return "FAILED";
            case AudioManager.AUDIOFOCUS_REQUEST_DELAYED:
                return "DELAYED";
            default:
                return "UNKNOWN(" + result + ")";
        }
    }

    private static String focusName(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_GAIN:
                return "GAIN";
            case AudioManager.AUDIOFOCUS_LOSS:
                return "LOSS";
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                return "LOSS_TRANSIENT";
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                return "LOSS_TRANSIENT_CAN_DUCK";
            default:
                return "UNKNOWN(" + change + ")";
        }
    }

    public void release() {
        main.post(() -> {
            cancelInternal();
            tts.shutdown();
        });
    }
}
