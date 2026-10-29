package local.youtube.gecko;

import android.graphics.Bitmap;
import android.os.SystemClock;

import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.MediaSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What is playing, as reported by GeckoView's media sessions (one per tab), and the controls
 * the notification uses. The notification follows the tab that last started playing; starting
 * playback in one tab pauses the other. Main thread only.
 */
final class Playback {
    interface Listener {
        void onPlaybackChanged();
    }

    /** Everything known about one tab's media. */
    private static final class State {
        String title;
        String artist;
        Bitmap artwork;
        boolean playing;
        long features;
        double durationSec = -1;
        double positionSec;
        double rate = 1;
        long positionTime;
    }

    private static final Map<MediaSession, State> sStates = new HashMap<>();
    /** The media session the notification shows and controls. */
    private static MediaSession sSession;

    // What the notification reads: the shown session's state.
    static String title;
    static String artist;
    static Bitmap artwork;
    static boolean playing;
    static long features;
    static double durationSec = -1;
    static double positionSec;
    static double rate = 1;
    /** SystemClock.elapsedRealtime() when positionSec was reported. */
    static long positionTime;

    private static final List<Listener> sListeners = new ArrayList<>();

    private Playback() {
    }

    static boolean isActive() {
        return sSession != null;
    }

    static boolean has(long feature) {
        return (features & feature) != 0;
    }

    static void addListener(Listener l) {
        sListeners.add(l);
    }

    static void removeListener(Listener l) {
        sListeners.remove(l);
    }

    private static State state(MediaSession ms) {
        State s = sStates.get(ms);
        if (s == null) {
            s = new State();
            sStates.put(ms, s);
        }
        return s;
    }

    /** Called after any change to a session's state; refreshes the notification if it's shown. */
    private static void changed(MediaSession ms) {
        if (sSession == null || (sSession != ms && !state(sSession).playing)) {
            // Nothing else is playing: show this one.
            sSession = ms;
        }
        if (sSession != ms) {
            return;
        }
        State s = state(ms);
        title = s.title;
        artist = s.artist;
        artwork = s.artwork;
        playing = s.playing;
        features = s.features;
        durationSec = s.durationSec;
        positionSec = s.positionSec;
        rate = s.rate;
        positionTime = s.positionTime;
        for (Listener l : new ArrayList<>(sListeners)) {
            l.onPlaybackChanged();
        }
    }

    // --- Controls -----------------------------------------------------------------

    static void play() {
        if (sSession != null) {
            sSession.play();
        }
    }

    static void pause() {
        if (sSession != null) {
            sSession.pause();
        }
    }

    static void next() {
        if (sSession != null) {
            sSession.nextTrack();
        }
    }

    static void previous() {
        if (sSession != null) {
            sSession.previousTrack();
        }
    }

    static void seekTo(double seconds) {
        if (sSession != null) {
            double max = durationSec > 0 ? durationSec : Double.MAX_VALUE;
            sSession.seekTo(Math.max(0, Math.min(seconds, max)), false);
        }
    }

    static void seekBy(double seconds) {
        if (sSession == null) {
            return;
        }
        if (has(MediaSession.Feature.SEEK_TO)) {
            seekTo(currentPosition() + seconds);
        } else if (seconds > 0) {
            sSession.seekForward();
        } else {
            sSession.seekBackward();
        }
    }

    static void audioFocus(int change) {
        if (sSession != null) {
            sSession.notifySystemAudioFocusChange(change);
        }
    }

    static double currentPosition() {
        double pos = positionSec;
        if (playing) {
            pos += (SystemClock.elapsedRealtime() - positionTime) / 1000.0 * rate;
        }
        return durationSec > 0 ? Math.min(pos, durationSec) : pos;
    }

    private static double currentPosition(State s) {
        double pos = s.positionSec;
        if (s.playing) {
            pos += (SystemClock.elapsedRealtime() - s.positionTime) / 1000.0 * s.rate;
        }
        return s.durationSec > 0 ? Math.min(pos, s.durationSec) : pos;
    }

    // --- Updates from GeckoView -------------------------------------------------------

    /** Feeds a GeckoSession's media events into this state. */
    static MediaSession.Delegate delegate(Runnable onPlay) {
        return new MediaSession.Delegate() {
            @Override
            public void onActivated(GeckoSession session, MediaSession ms) {
                state(ms);
                changed(ms);
            }

            @Override
            public void onDeactivated(GeckoSession session, MediaSession ms) {
                sStates.remove(ms);
                if (sSession == ms) {
                    sSession = null;
                    // Fall back to another tab's media, if any.
                    for (MediaSession other : sStates.keySet()) {
                        sSession = other;
                        break;
                    }
                    if (sSession != null) {
                        changed(sSession);
                    } else {
                        playing = false;
                        for (Listener l : new ArrayList<>(sListeners)) {
                            l.onPlaybackChanged();
                        }
                    }
                }
            }

            @Override
            public void onMetadata(GeckoSession session, MediaSession ms, MediaSession.Metadata meta) {
                State s = state(ms);
                s.title = meta.title;
                s.artist = meta.artist;
                s.artwork = null;
                changed(ms);
                if (meta.artwork != null) {
                    String forTitle = meta.title;
                    meta.artwork.getBitmap(512).accept(bitmap -> {
                        if (sStates.get(ms) == s && Objects.equals(forTitle, s.title)) {
                            s.artwork = bitmap;
                            changed(ms);
                        }
                    }, e -> { });
                }
            }

            @Override
            public void onFeatures(GeckoSession session, MediaSession ms, long f) {
                state(ms).features = f;
                changed(ms);
            }

            @Override
            public void onPlay(GeckoSession session, MediaSession ms) {
                if (sSession != null && sSession != ms && state(sSession).playing) {
                    // Only one tab plays at a time.
                    sSession.pause();
                }
                State s = state(ms);
                s.playing = true;
                s.positionTime = SystemClock.elapsedRealtime();
                sSession = ms;
                onPlay.run();
                changed(ms);
            }

            @Override
            public void onPause(GeckoSession session, MediaSession ms) {
                State s = state(ms);
                s.positionSec = currentPosition(s);
                s.positionTime = SystemClock.elapsedRealtime();
                s.playing = false;
                changed(ms);
            }

            @Override
            public void onStop(GeckoSession session, MediaSession ms) {
                state(ms).playing = false;
                changed(ms);
            }

            @Override
            public void onPositionState(GeckoSession session, MediaSession ms, MediaSession.PositionState p) {
                State s = state(ms);
                s.durationSec = p.duration;
                s.positionSec = p.position;
                s.rate = p.playbackRate > 0 ? p.playbackRate : 1;
                s.positionTime = SystemClock.elapsedRealtime();
                changed(ms);
            }
        };
    }
}
