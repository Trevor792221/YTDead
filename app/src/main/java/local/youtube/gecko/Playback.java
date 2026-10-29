package local.youtube.gecko;

import android.graphics.Bitmap;
import android.os.SystemClock;

import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.MediaSession;

import java.util.ArrayList;
import java.util.List;

/**
 * What is playing, as reported by GeckoView's media session for the page, and the controls
 * the notification uses. Main thread only.
 */
final class Playback {
    interface Listener {
        void onPlaybackChanged();
    }

    private static MediaSession sSession;
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

    private static void changed() {
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

    // --- Updates from GeckoView -------------------------------------------------------

    /** Feeds a GeckoSession's media events into this state. */
    static MediaSession.Delegate delegate(Runnable onPlay) {
        return new MediaSession.Delegate() {
            @Override
            public void onActivated(GeckoSession session, MediaSession mediaSession) {
                sSession = mediaSession;
                changed();
            }

            @Override
            public void onDeactivated(GeckoSession session, MediaSession mediaSession) {
                if (sSession == mediaSession) {
                    sSession = null;
                    playing = false;
                    changed();
                }
            }

            @Override
            public void onMetadata(GeckoSession session, MediaSession mediaSession, MediaSession.Metadata meta) {
                sSession = mediaSession;
                title = meta.title;
                artist = meta.artist;
                artwork = null;
                changed();
                if (meta.artwork != null) {
                    String forTitle = meta.title;
                    meta.artwork.getBitmap(512).accept(bitmap -> {
                        if (java.util.Objects.equals(forTitle, title)) {
                            artwork = bitmap;
                            changed();
                        }
                    }, e -> { });
                }
            }

            @Override
            public void onFeatures(GeckoSession session, MediaSession mediaSession, long f) {
                features = f;
                changed();
            }

            @Override
            public void onPlay(GeckoSession session, MediaSession mediaSession) {
                sSession = mediaSession;
                playing = true;
                positionTime = SystemClock.elapsedRealtime();
                onPlay.run();
                changed();
            }

            @Override
            public void onPause(GeckoSession session, MediaSession mediaSession) {
                positionSec = currentPosition();
                positionTime = SystemClock.elapsedRealtime();
                playing = false;
                changed();
            }

            @Override
            public void onStop(GeckoSession session, MediaSession mediaSession) {
                playing = false;
                changed();
            }

            @Override
            public void onPositionState(GeckoSession session, MediaSession mediaSession,
                                        MediaSession.PositionState state) {
                durationSec = state.duration;
                positionSec = state.position;
                rate = state.playbackRate > 0 ? state.playbackRate : 1;
                positionTime = SystemClock.elapsedRealtime();
                changed();
            }
        };
    }
}
