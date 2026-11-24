# MixNav — navigation voice prompts that don't pause your music

Android POC: spoken turn-by-turn prompts played **over** whatever media app is running, with the
music never paused and never ducked.

The core finding is boring and testable: **music is not paused by the prompt audio, it is paused by
the audio-focus request that usually accompanies it.** A navigation app that never requests focus
can play prompts on top of the music, and the platform mixer plays both streams.

This repo contains the POC (`app/`) and a fixture media app (`bed/`) used to prove the behaviour
with `dumpsys` output instead of vibes.

---

## What it does

| Policy | What MixNav requests from the OS | What the music app does |
|---|---|---|
| **MIX** (default) | nothing — `requestAudioFocus()` is never called | keeps playing |
| DUCK | `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` | platform ramps its volume to 20% for the prompt, then restores it |
| PAUSE | `AUDIOFOCUS_GAIN_TRANSIENT` | receives `LOSS_TRANSIENT`, pauses, resumes on `GAIN` |
| CALL | `MODE_IN_COMMUNICATION` + `USAGE_VOICE_COMMUNICATION` | **muted** by the platform (call-mode port/stream mute) |

Implementation:

- `TextToSpeech.synthesizeToFile()` → WAV in cache. The engine never plays anything, so its own
  player (which may request focus / duck) is out of the loop. If the device has no TTS engine, a
  synthesized chime WAV is used so the audio path is still exercised.
- Playback via `MediaPlayer` with `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` + `CONTENT_TYPE_SPEECH`,
  app-level gain via `MediaPlayer.setVolume()`.
- Foreground service (`mediaPlayback` type) so prompts keep playing while the phone is pocketed /
  another app is in front.
- No `AudioFocusRequest` at all in MIX mode. Nothing is sent to the media app, so nothing can pause.

The media app's own choice matters: an app that declares `setWillPauseWhenDucked(true)` turns a
`MAY_DUCK` request into a pause. That is the mechanism behind "my navigation app pauses Spotify in
the car". MIX does not trigger it.

---

## Measured results

Device: Android 16 (`sdk=36`), x86_64 emulator, `MediaBed` playing a 265 s Wikipedia spoken article
as `USAGE_MEDIA` and holding `AUDIOFOCUS_GAIN`.

Trigger used for every row:

```bash
adb shell am start -n dev.mixnav/.MainActivity -a dev.mixnav.action.SPEAK \
  --es policy MIX --es text "'Turn right onto Market Street'"
```

**MIX** — no focus entry for MixNav, music never notified, both players active:

```
Audio Focus stack entries (last is top of stack):
  ... -- pack: dev.mixnav.bed -- gain: GAIN -- loss: none ...

  AudioPlaybackConfiguration piid:223 ... u/pid:10155/3115 state:started
      attr: usage=USAGE_MEDIA content=CONTENT_TYPE_MUSIC
  AudioPlaybackConfiguration piid:231 ... u/pid:10153/3168 state:started
      attr: usage=USAGE_ASSISTANCE_NAVIGATION_GUIDANCE content=CONTENT_TYPE_SPEECH

# dumpsys media.audio_flinger (same output thread)
  3115  10155  dev.mixnav.bed
  3168  10153  dev.mixnav
```

**PAUSE** — music actually stops while the prompt plays:

```
  ... pack: dev.mixnav.bed -- gain: GAIN             -- loss: LOSS_TRANSIENT ...
  ... pack: dev.mixnav     -- gain: GAIN_TRANSIENT   -- loss: none ...
  AudioPlaybackConfiguration ... u/pid:10155/3115 state:paused   # music
  AudioPlaybackConfiguration ... u/pid:10153/3168 state:started  # prompt
MixNavBed: focus: LOSS_TRANSIENT -> music PAUSED
MixNavBed: focus: GAIN -> volume restored, resuming if paused
```

**DUCK** — platform-level `VolumeShaper`, music keeps playing at 20%:

```
  ... pack: dev.mixnav.bed -- loss: LOSS_TRANSIENT_CAN_DUCK ...
  ... pack: dev.mixnav     -- gain: GAIN_TRANSIENT_MAY_DUCK ...
ducking player piid:223 ... mVolumes[] = [1.0, 0.2] mDurationMs = 500.0
unducking piid:223
```

**CALL** — the mode switch does not mix; the platform mutes media:

```
  AudioPlaybackConfiguration ... u/pid:10155/3115 state:started
      attr: usage=USAGE_MEDIA ... mutedState:streamVolume portVolume
- Requested mode = MODE_IN_COMMUNICATION
- Actual mode = MODE_IN_COMMUNICATION
MixNav (prompt): usage=VOICE_COMMUNICATION gain=80%
```

After the prompt restores `MODE_NORMAL`, the media player returns to `mutedState:none`. On this
platform the "pretend to be a call" idea silences the music instead of mixing with it, and it also
takes the audio mode/route away from real calls. Treat it as a negative result.

Full route (`ACTION_START`, six prompts, MIX): the bed app logged no focus change across the whole
route; `state:started` was maintained.

---

## Why the TTS engine matters

`TextToSpeech.speak()` plays audio inside the engine's process. The engine decides focus/duck
behaviour; some engines request `MAY_DUCK` themselves. `synthesizeToFile()` keeps the client in
control of playback and therefore of focus. The POC does the latter.

If you build this yourself, targetSdk 30+ needs package visibility for the engine:

```xml
<queries>
    <intent><action android:name="android.intent.action.TTS_SERVICE" /></intent>
</queries>
```

Without it `TextToSpeech` init fails on devices with an engine installed (observed: silent fallback
to the chime until the `queries` element was added).

---

## Reproduce

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug :bed:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r bed/build/outputs/apk/debug/bed-debug.apk

# fixture audio: a local file the bed app can read (no storage permission needed)
adb push your-audio.ogg /sdcard/Android/data/dev.mixnav.bed/files/bed.ogg

adb shell am start -n dev.mixnav.bed/.BedActivity -a dev.mixnav.bed.START   # "music"
adb shell am start -n dev.mixnav/.MainActivity -a dev.mixnav.action.SPEAK \
  --es policy MIX --es text "'Turn right onto Market Street'"

adb shell dumpsys audio | sed -n '/Focus stack/,/^$/p'
adb shell dumpsys audio | grep -E 'state:(started|paused)' | grep 'u/pid'
adb shell dumpsys media.audio_flinger | grep -E 'dev.mixnav'
```

The `--es text "'...'"` quoting is deliberate: `adb shell` re-parses the command line, so the inner
quotes must survive to keep the sentence as one extra.

UI alternative: the pieced-together activity has the policy radio group, gain slider, "Speak prompt"
and "Start route" buttons, plus a live log of focus decisions.

---

## Limits / risks before shipping this

- **Car head units and Bluetooth AVRCP**: some units pause media whenever *any* other stream starts
  because the phone tells the unit about focus loss. MIX avoids the phone-side trigger; test the
  actual car.
- **Android Auto**: navigation guidance is a first-class channel there; the head unit does its own
  ducking. Test on the target unit.
- **Mixed volumes**: prompts share `STREAM_MUSIC` volume with the music. `setVolume()` gives an
  app-relative gain only; there is no independent hardware "navigation" volume outside the car
  channel.
- **Do not ship CALL mode.** It mutes media (measured), forces the communication route, fights
  Telecom for real calls, and looks like a call to accessories.
- **Playback capture is the wrong tool** for this: `AudioPlaybackCapture` needs a projection
  consent and re-rendering captured audio invites echo/latency. Focus-free playback is enough.
- The `bed` app is a test fixture, not a music player.

## Session record

The full build/test transcript for this repo (including every `dumpsys` capture) is in
[`session/`](session/). Open `session/viewer.html` in a browser for the rendered view; the raw
transcript is `session/session.jsonl` and untruncated tool logs are under `session/artifacts/`.

## Layout

```
app/   MixNav: TTS -> WAV -> MediaPlayer, foreground service, four focus policies
bed/   MediaBed: plays a local file as USAGE_MEDIA, holds GAIN, logs focus changes
```

Fixture audio for the measurements: *En-Butterfly(Loona song)-article.ogg* (spoken Wikipedia
article, speaker -dangiome), CC BY-SA 4.0, via Wikimedia Commons — not redistributed here.
TTS engine used on the emulator for the measured run: eSpeak NG (F-Droid, `com.reecedunn.espeak`,
GPL-3.0).
