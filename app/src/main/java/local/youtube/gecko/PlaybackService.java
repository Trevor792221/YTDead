package local.youtube.gecko;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

/**
 * Keeps playback going with the app in the background and shows the media notification
 * (lock screen and quick settings controls): previous/next or back/forward 10 s, play/pause,
 * a seek bar, and close.
 */
public class PlaybackService extends Service implements Playback.Listener {
    private static final String CHANNEL = "playback";
    private static final int NOTIFICATION_ID = 1;
    private static final String ACTION_PLAY_PAUSE = "play_pause";
    private static final String ACTION_NEXT = "next";
    private static final String ACTION_PREVIOUS = "previous";
    private static final String ACTION_FORWARD = "forward";
    private static final String ACTION_REWIND = "rewind";
    private static final String ACTION_CLOSE = "close";
    private static final double SKIP_SECONDS = 10;

    private MediaSession mSession;
    private AudioManager mAudio;
    private AudioFocusRequest mFocusRequest;
    private boolean mHasFocus;
    private boolean mForeground;

    private final BroadcastReceiver mNoisy = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Headphones unplugged.
            Playback.pause();
        }
    };

    /** Starts the service if it isn't running. Safe to call repeatedly. */
    static void start(Context context) {
        try {
            context.startForegroundService(new Intent(context, PlaybackService.class));
        } catch (RuntimeException e) {
            // Android refuses to start foreground services from the background; the next
            // play while the app is visible starts it.
            Log.w(GeckoApp.TAG, "Couldn't start playback service", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, getString(R.string.playback_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);

        mSession = new MediaSession(this, "YTDead");
        mSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                Playback.play();
            }

            @Override
            public void onPause() {
                Playback.pause();
            }

            @Override
            public void onSkipToNext() {
                Playback.next();
            }

            @Override
            public void onSkipToPrevious() {
                Playback.previous();
            }

            @Override
            public void onFastForward() {
                Playback.seekBy(SKIP_SECONDS);
            }

            @Override
            public void onRewind() {
                Playback.seekBy(-SKIP_SECONDS);
            }

            @Override
            public void onSeekTo(long pos) {
                Playback.seekTo(pos / 1000.0);
            }

            @Override
            public void onStop() {
                close();
            }

            @Override
            public void onCustomAction(String action, android.os.Bundle extras) {
                handleAction(action);
            }
        });
        mSession.setSessionActivity(openAppIntent());
        mSession.setActive(true);

        mAudio = getSystemService(AudioManager.class);
        mFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build())
                .setOnAudioFocusChangeListener(this::onAudioFocusChange)
                .build();

        registerReceiver(mNoisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        Playback.addListener(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Every startForegroundService() call must be answered with startForeground().
        goForeground();
        if (intent != null && intent.getAction() != null) {
            handleAction(intent.getAction());
        }
        onPlaybackChanged();
        return START_NOT_STICKY;
    }

    private void handleAction(String action) {
        switch (action) {
            case ACTION_PLAY_PAUSE:
                if (Playback.playing) {
                    Playback.pause();
                } else {
                    Playback.play();
                }
                break;
            case ACTION_NEXT:
                Playback.next();
                break;
            case ACTION_PREVIOUS:
                Playback.previous();
                break;
            case ACTION_FORWARD:
                Playback.seekBy(SKIP_SECONDS);
                break;
            case ACTION_REWIND:
                Playback.seekBy(-SKIP_SECONDS);
                break;
            case ACTION_CLOSE:
                close();
                break;
            default:
                break;
        }
    }

    private void close() {
        Playback.pause();
        stopSelf();
    }

    @Override
    public void onPlaybackChanged() {
        if (!Playback.isActive()) {
            stopSelf();
            return;
        }
        if (Playback.playing && !mHasFocus) {
            mHasFocus = mAudio.requestAudioFocus(mFocusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
        updateSession();
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification());
    }

    private void onAudioFocusChange(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_GAIN:
                mHasFocus = true;
                Playback.audioFocus(org.mozilla.geckoview.MediaSession.SYSTEM_AUDIO_FOCUS_GAIN);
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                Playback.audioFocus(org.mozilla.geckoview.MediaSession.SYSTEM_AUDIO_FOCUS_TRANSIENT_LOSS);
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                mHasFocus = false;
                Playback.audioFocus(org.mozilla.geckoview.MediaSession.SYSTEM_AUDIO_FOCUS_PERMANENT_LOSS);
                Playback.pause();
                break;
            default:
                break;
        }
    }

    private void updateSession() {
        MediaMetadata.Builder meta = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, Playback.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, Playback.artist);
        if (Playback.durationSec > 0) {
            meta.putLong(MediaMetadata.METADATA_KEY_DURATION, (long) (Playback.durationSec * 1000));
        }
        if (Playback.artwork != null) {
            meta.putBitmap(MediaMetadata.METADATA_KEY_ART, Playback.artwork);
        }
        mSession.setMetadata(meta.build());

        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_STOP | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND;
        if (Playback.has(org.mozilla.geckoview.MediaSession.Feature.SEEK_TO)) {
            actions |= PlaybackState.ACTION_SEEK_TO;
        }
        PlaybackState.Builder state = new PlaybackState.Builder();
        // Android 13+ draws the notification's buttons from these: real previous/next when the
        // page offers them (playlists, autoplay), otherwise back/forward 10 seconds.
        if (Playback.has(org.mozilla.geckoview.MediaSession.Feature.PREVIOUS_TRACK)) {
            actions |= PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        } else {
            state.addCustomAction(ACTION_REWIND, getString(R.string.rewind_10), R.drawable.ic_replay_10);
        }
        if (Playback.has(org.mozilla.geckoview.MediaSession.Feature.NEXT_TRACK)) {
            actions |= PlaybackState.ACTION_SKIP_TO_NEXT;
        } else {
            state.addCustomAction(ACTION_FORWARD, getString(R.string.forward_10), R.drawable.ic_forward_10);
        }
        state.addCustomAction(ACTION_CLOSE, getString(R.string.close), R.drawable.ic_close);
        state.setActions(actions);
        state.setState(Playback.playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                (long) (Playback.currentPosition() * 1000),
                Playback.playing ? (float) Playback.rate : 0f,
                SystemClock.elapsedRealtime());
        mSession.setPlaybackState(state.build());
    }

    private Notification buildNotification() {
        boolean hasPrevious = Playback.has(org.mozilla.geckoview.MediaSession.Feature.PREVIOUS_TRACK);
        boolean hasNext = Playback.has(org.mozilla.geckoview.MediaSession.Feature.NEXT_TRACK);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(Playback.title != null ? Playback.title : getString(R.string.app_name))
                .setContentText(Playback.artist)
                .setLargeIcon(Playback.artwork)
                .setContentIntent(openAppIntent())
                .setDeleteIntent(action(ACTION_CLOSE))
                .setOngoing(Playback.playing)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                // Older Android versions draw these buttons; 13+ uses the media session above.
                .addAction(hasPrevious
                        ? button(android.R.drawable.ic_media_previous, R.string.previous, ACTION_PREVIOUS)
                        : button(R.drawable.ic_replay_10, R.string.rewind_10, ACTION_REWIND))
                .addAction(Playback.playing
                        ? button(android.R.drawable.ic_media_pause, R.string.pause, ACTION_PLAY_PAUSE)
                        : button(android.R.drawable.ic_media_play, R.string.play, ACTION_PLAY_PAUSE))
                .addAction(hasNext
                        ? button(android.R.drawable.ic_media_next, R.string.next, ACTION_NEXT)
                        : button(R.drawable.ic_forward_10, R.string.forward_10, ACTION_FORWARD))
                .addAction(button(R.drawable.ic_close, R.string.close, ACTION_CLOSE))
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(mSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    private void goForeground() {
        if (mForeground) {
            return;
        }
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
        mForeground = true;
    }

    private Notification.Action button(int icon, int label, String action) {
        return new Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, icon), getString(label), action(action)).build();
    }

    private PendingIntent action(String action) {
        return PendingIntent.getService(this, action.hashCode(),
                new Intent(this, PlaybackService.class).setAction(action),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent openAppIntent() {
        return PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Swiping the app away from recents stops playback, like the YouTube app.
        close();
    }

    @Override
    public void onDestroy() {
        Playback.removeListener(this);
        unregisterReceiver(mNoisy);
        if (mHasFocus) {
            mAudio.abandonAudioFocusRequest(mFocusRequest);
        }
        mSession.setActive(false);
        mSession.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
