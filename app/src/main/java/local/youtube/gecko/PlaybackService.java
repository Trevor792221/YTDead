package local.youtube.gecko;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.media.MediaBrowserService;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps playback going with the app in the background and shows the media notification
 * (lock screen and quick settings controls): previous/next or back/forward 10 s, play/pause,
 * a seek bar, and close.
 *
 * Also the app's media browser for Android Auto: the car shows what's playing (the sound of a
 * YouTube video or a YouTube Music song) with the same controls. No video in the car.
 */
public class PlaybackService extends MediaBrowserService implements Playback.Listener {
    private static final String BROWSE_ROOT = "root";
    private static final String NOW_PLAYING = "now_playing";
    private static final String NO_RECS = "no_recs";
    private static final String SHELF = "shelf:";
    private static final String ITEM = "item:";
    /** Who may see and control playback: Android Auto, Android's own media controls, the app. */
    private static final Set<String> TRUSTED_CALLERS = new HashSet<>(Arrays.asList(
            "com.google.android.projection.gearhead", // Android Auto
            "com.android.car.media", // cars with Android built in
            "com.google.android.carassistant",
            "com.android.systemui",
            "com.android.bluetooth"));

    private static final String CHANNEL = "playback";
    private static final int NOTIFICATION_ID = 1;
    private static final String ACTION_PLAY_PAUSE = "play_pause";
    private static final String ACTION_NEXT = "next";
    private static final String ACTION_PREVIOUS = "previous";
    private static final String ACTION_FORWARD = "forward";
    private static final String ACTION_REWIND = "rewind";
    private static final String ACTION_CLOSE = "close";
    private static final String ACTION_LIKE = "like";
    private static final String ACTION_DISLIKE = "dislike";
    private static final String ACTION_SHUFFLE = "shuffle";
    private static final double SKIP_SECONDS = 10;

    private MediaSession mSession;
    private AudioManager mAudio;
    private AudioFocusRequest mFocusRequest;
    private boolean mHasFocus;
    private boolean mForeground;
    /** What the notification last showed; rebuilt only when this changes, not every second. */
    private String mShownKey;

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
            public void onPlayFromMediaId(String mediaId, Bundle extras) {
                if (mediaId != null && mediaId.startsWith(ITEM)) {
                    playPick(mediaId); // a recommendation, picked in the car
                } else if (NOW_PLAYING.equals(mediaId)) {
                    Playback.play();
                }
            }

            @Override
            public void onCustomAction(String action, android.os.Bundle extras) {
                handleAction(action);
            }
        });
        mSession.setSessionActivity(openAppIntent());
        mSession.setActive(true);
        setSessionToken(mSession.getSessionToken());
        if (!Playback.isActive()) {
            // Android Auto connected before anything plays: say how to start.
            mSession.setPlaybackState(new PlaybackState.Builder()
                    .setState(PlaybackState.STATE_ERROR, 0, 0)
                    .setErrorMessage("Start a video or song in YTDead on your phone.")
                    .build());
        }

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
            case ACTION_LIKE:
            case ACTION_DISLIKE:
            case ACTION_SHUFFLE:
                Playback.musicAction(action);
                break;
            default:
                break;
        }
    }

    private void close() {
        Playback.pause();
        stopSelf();
    }

    // --- Android Auto ----------------------------------------------------------------------

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        boolean trusted = clientPackageName.equals(getPackageName()) || TRUSTED_CALLERS.contains(clientPackageName);
        return trusted ? new BrowserRoot(BROWSE_ROOT, null) : null;
    }

    @Override
    public void onLoadChildren(String parentId, Result<List<MediaBrowser.MediaItem>> result) {
        List<Recs.Shelf> shelves = Recs.load(this);
        if (BROWSE_ROOT.equals(parentId)) {
            if (Recs.stale(this)) {
                GeckoApp.get().refreshRecs(); // fresher picks next time the car asks
            }
            List<MediaBrowser.MediaItem> items = new ArrayList<>();
            if (Playback.isActive()) {
                items.add(new MediaBrowser.MediaItem(new MediaDescription.Builder()
                        .setMediaId(NOW_PLAYING)
                        .setTitle(Playback.title != null ? Playback.title : getString(R.string.app_name))
                        .setSubtitle(Playback.artist)
                        .setIconBitmap(smallArtwork())
                        .build(), MediaBrowser.MediaItem.FLAG_PLAYABLE));
            }
            for (int i = 0; i < shelves.size(); i++) {
                items.add(new MediaBrowser.MediaItem(new MediaDescription.Builder()
                        .setMediaId(SHELF + i)
                        .setTitle(shelves.get(i).title)
                        .build(), MediaBrowser.MediaItem.FLAG_BROWSABLE));
            }
            if (shelves.isEmpty()) {
                items.add(new MediaBrowser.MediaItem(new MediaDescription.Builder()
                        .setMediaId(NO_RECS)
                        .setTitle("No recommendations yet")
                        .setSubtitle("Open the Music tab in YTDead on your phone once")
                        .build(), MediaBrowser.MediaItem.FLAG_PLAYABLE));
            }
            result.sendResult(items);
            return;
        }
        int shelf = parentId.startsWith(SHELF) ? parseIndex(parentId.substring(SHELF.length())) : -1;
        if (shelf < 0 || shelf >= shelves.size()) {
            result.sendResult(new ArrayList<>());
            return;
        }
        // Artwork comes from the web, so the list is sent once it's loaded (off the main thread).
        List<Recs.Item> picks = shelves.get(shelf).items;
        result.detach();
        new Thread(() -> {
            List<MediaBrowser.MediaItem> items = new ArrayList<>();
            for (int j = 0; j < picks.size(); j++) {
                Recs.Item item = picks.get(j);
                items.add(new MediaBrowser.MediaItem(new MediaDescription.Builder()
                        .setMediaId(ITEM + shelf + ":" + j)
                        .setTitle(item.title)
                        .setSubtitle(item.subtitle)
                        .setIconBitmap(thumbnail(item.thumb))
                        .build(), MediaBrowser.MediaItem.FLAG_PLAYABLE));
            }
            mMainThread.post(() -> result.sendResult(items));
        }, "car-artwork").start();
    }

    private static int parseIndex(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private final android.os.Handler mMainThread = new android.os.Handler(android.os.Looper.getMainLooper());

    private static final android.util.LruCache<String, android.graphics.Bitmap> sThumbs =
            new android.util.LruCache<>(80);

    /** A small picture for the car's list, or null if it can't be had quickly. */
    private static android.graphics.Bitmap thumbnail(String url) {
        if (url == null) {
            return null;
        }
        android.graphics.Bitmap cached = sThumbs.get(url);
        if (cached != null) {
            return cached;
        }
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            try (java.io.InputStream in = c.getInputStream()) {
                android.graphics.Bitmap full = android.graphics.BitmapFactory.decodeStream(in);
                if (full == null) {
                    return null;
                }
                int size = 120;
                float scale = Math.min(1f, (float) size / Math.max(full.getWidth(), full.getHeight()));
                android.graphics.Bitmap small = scale < 1f ? android.graphics.Bitmap.createScaledBitmap(full,
                        Math.round(full.getWidth() * scale), Math.round(full.getHeight() * scale), true) : full;
                sThumbs.put(url, small);
                return small;
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Plays a recommendation picked in the car, in the app's Music tab. */
    private void playPick(String mediaId) {
        String[] parts = mediaId.substring(ITEM.length()).split(":");
        List<Recs.Shelf> shelves = Recs.load(this);
        int shelf = parts.length == 2 ? parseIndex(parts[0]) : -1;
        int item = parts.length == 2 ? parseIndex(parts[1]) : -1;
        if (shelf < 0 || shelf >= shelves.size() || item < 0 || item >= shelves.get(shelf).items.size()) {
            return;
        }
        if (!GeckoApp.get().playInMusicTab(shelves.get(shelf).items.get(item).url)) {
            mSession.setPlaybackState(new PlaybackState.Builder()
                    .setState(PlaybackState.STATE_ERROR, 0, 0)
                    .setErrorMessage("Open YTDead on your phone, then pick again.")
                    .build());
        }
    }

    /** The artwork at list size (a full-size picture could be too big to send to the car). */
    private static android.graphics.Bitmap smallArtwork() {
        android.graphics.Bitmap art = Playback.artwork;
        if (art == null || Math.max(art.getWidth(), art.getHeight()) <= 256) {
            return art;
        }
        float scale = 256f / Math.max(art.getWidth(), art.getHeight());
        return android.graphics.Bitmap.createScaledBitmap(art,
                Math.round(art.getWidth() * scale), Math.round(art.getHeight() * scale), true);
    }

    @Override
    public void onPlaybackChanged() {
        if (!Playback.isActive()) {
            // Stays while Android Auto is connected (bound); otherwise stops.
            stopSelf();
            return;
        }
        if (Playback.playing && !mHasFocus) {
            mHasFocus = mAudio.requestAudioFocus(mFocusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
        updateSession();
        String key = Playback.title + "|" + Playback.artist + "|" + System.identityHashCode(Playback.artwork)
                + "|" + Playback.playing + "|" + Playback.features + "|" + Playback.musicRating;
        if (!key.equals(mShownKey)) {
            mShownKey = key;
            notifyChildrenChanged(BROWSE_ROOT); // the car's "Now playing" entry
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification());
        }
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
        if (Playback.isMusic()) {
            // YouTube Music: like / dislike (filled when chosen) and shuffle, in Android Auto and
            // (first two) in the phone's media controls, like the YouTube Music app.
            state.addCustomAction(ACTION_LIKE, getString(R.string.like),
                    "LIKE".equals(Playback.musicRating) ? R.drawable.ic_thumb_up_filled : R.drawable.ic_thumb_up);
            state.addCustomAction(ACTION_DISLIKE, getString(R.string.dislike),
                    "DISLIKE".equals(Playback.musicRating) ? R.drawable.ic_thumb_down_filled : R.drawable.ic_thumb_down);
            state.addCustomAction(ACTION_SHUFFLE, getString(R.string.shuffle), R.drawable.ic_shuffle);
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
}
