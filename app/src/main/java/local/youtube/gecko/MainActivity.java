package local.youtube.gecko;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.animation.ValueAnimator;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.Rational;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.window.OnBackInvokedDispatcher;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSession.ContentDelegate;
import org.mozilla.geckoview.GeckoSession.NavigationDelegate;
import org.mozilla.geckoview.GeckoSession.PermissionDelegate;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YouTube's mobile (app-style) site, with a second tab for YouTube Music. Each tab is its own
 * GeckoSession, so it keeps its page and history while the other is shown; both share sign-in,
 * extensions, ad removal and background playback.
 */
public class MainActivity extends Activity {
    private static final String TAG = GeckoApp.TAG;
    private static final int TAB_YOUTUBE = 0;
    private static final int TAB_MUSIC = 1;
    private static final String[] HOMES = {"https://m.youtube.com/", "https://music.youtube.com/"};
    private static final String[] TAB_LABELS = {"YouTube", "Music"};

    /** Links that belong to the other tab: YouTube pages people navigate to (not sign-in steps). */
    private static final Pattern YOUTUBE_PAGE = Pattern.compile(
            "^/($|watch|shorts|channel/|c/|user/|@|playlist|results|feed|live|hashtag)");

    /** Hosts that stay inside the app; any other link opens in the phone's browser. */
    private static final Pattern INTERNAL_HOST = Pattern.compile(
            "(^|\\.)(youtube\\.com|youtu\\.be|youtube-nocookie\\.com|google\\.com|google\\.[a-z.]+"
                    + "|gstatic\\.com|googleusercontent\\.com|googlevideo\\.com|ytimg\\.com|ggpht\\.com)$");

    private static final Pattern URL_IN_TEXT = Pattern.compile("https?://\\S+");

    private FrameLayout mRoot;
    private LinearLayout mTabBar;
    private final TextView[] mTabButtons = new TextView[2];
    private GeckoView mView;
    private final GeckoSession[] mSessions = new GeckoSession[2];
    private final boolean[] mCanGoBack = new boolean[2];
    private int mTab = TAB_YOUTUBE;
    private boolean mFullScreen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mRoot = new FrameLayout(this);
        mRoot.setBackgroundColor(getColor(R.color.background));
        mView = new GeckoView(this);
        // A TextureView can be drawn scaled (the pop-up player, below); switching backends while
        // the app runs left the page black, so it's used throughout.
        mView.setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW);
        mView.setAutofillEnabled(true);
        mView.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);
        mRoot.addView(mView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        // The tab bar floats over the top of the page and slides away (see "Tab bar" below).
        mTabBar = buildTabBar();
        mTabBar.setBackgroundColor(getColor(R.color.background));
        mRoot.addView(mTabBar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        mTabBar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t != mBarHeight) {
                mBarHeight = b - t;
                applyBar();
            }
        });
        setContentView(mRoot);
        mRoot.setOnApplyWindowInsetsListener(this::applyInsets);
        mRoot.postDelayed(mHideBar, BAR_HIDE_DELAY_MS);
        setFullScreen(false);

        IntentFilter pipFilter = new IntentFilter(PIP_ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(mPipReceiver, pipFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mPipReceiver, pipFilter);
        }
        Playback.addListener(mPipUpdater);

        String url = urlFromIntent(getIntent());
        open(url != null ? tabFor(Uri.parse(url), TAB_YOUTUBE) : TAB_YOUTUBE, url);

        // First launch: show Settings so the extensions can be installed.
        if (savedInstanceState == null
                && !Extensions.prefs(this).getBoolean(Extensions.PREF_ONBOARDED, false)) {
            startActivity(new Intent(this, SettingsActivity.class));
        }

        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String url = urlFromIntent(intent);
        if (url != null) {
            open(tabFor(Uri.parse(url), mTab), url);
        }
    }

    // --- Tabs -----------------------------------------------------------------------

    private LinearLayout buildTabBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(6), dp(12), dp(6));
        for (int i = 0; i < TAB_LABELS.length; i++) {
            final int tab = i;
            TextView chip = new TextView(this);
            chip.setText(TAB_LABELS[i]);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            chip.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(16), 0, dp(16), 0);
            chip.setOnClickListener(v -> {
                open(tab, null);
                showBar();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(32));
            lp.rightMargin = dp(8);
            bar.addView(chip, lp);
            mTabButtons[i] = chip;
        }

        // App settings, next to the tabs.
        TextView settings = new TextView(this);
        settings.setText("Settings");
        settings.setContentDescription("App settings");
        settings.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        settings.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        settings.setTextColor(0xFFF1F1F1);
        settings.setGravity(Gravity.CENTER);
        settings.setPadding(dp(12), 0, dp(16), 0);
        android.graphics.drawable.Drawable gear = getDrawable(R.drawable.ic_settings);
        gear.setBounds(0, 0, dp(18), dp(18));
        settings.setCompoundDrawablesRelative(gear, null, null, null);
        settings.setCompoundDrawablePadding(dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        bg.setColor(0xFF272727);
        settings.setBackground(bg);
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        bar.addView(settings, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(32)));
        return bar;
    }

    private void updateTabBar() {
        for (int i = 0; i < mTabButtons.length; i++) {
            boolean selected = i == mTab;
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(8));
            bg.setColor(selected ? 0xFFF1F1F1 : 0xFF272727);
            mTabButtons[i].setBackground(bg);
            mTabButtons[i].setTextColor(selected ? 0xFF0F0F0F : 0xFFF1F1F1);
            mTabButtons[i].setSelected(selected);
        }
    }

    private void updateTabBarVisibility() {
        applyBar();
    }

    // --- Tab bar: hides by itself, comes back when scrolling up ---------------------------
    // Shown at start and whenever you scroll up (drag down) anywhere; hides after a few
    // seconds or when you scroll down. It slides over the page like Firefox's address bar
    // (GeckoView's "dynamic toolbar"): the page is laid out once for the space below the bar
    // and is moved, not resized, so showing and hiding it doesn't make YouTube re-lay out.

    private static final long BAR_HIDE_DELAY_MS = 3000;
    private static final long BAR_ANIMATION_MS = 180;

    private int mBarHeight;
    /** 1 = fully shown, 0 = hidden. */
    private float mBarFraction = 1;
    private int mToolbarMaxHeight = -1;
    private ValueAnimator mBarAnimator;
    private final Runnable mHideBar = this::hideBar;
    private float mTouchStartX;
    private float mTouchStartY;
    private boolean mTouchOnBar;
    private boolean mGestureHandled;

    private boolean barAllowed() {
        return !mFullScreen && !isInPictureInPictureMode() && mFrozenWidth == 0;
    }

    private void applyBar() {
        boolean allowed = barAllowed();
        float shown = allowed ? mBarFraction * mBarHeight : 0;
        mTabBar.setTranslationY(shown - mBarHeight);
        mTabBar.setVisibility(shown > 0 ? View.VISIBLE : View.INVISIBLE);
        int maxHeight = allowed ? mBarHeight : 0;
        if (maxHeight != mToolbarMaxHeight) {
            mToolbarMaxHeight = maxHeight;
            mView.setDynamicToolbarMaxHeight(maxHeight);
        }
        if (mFrozenWidth == 0) {
            mView.setTranslationY(shown);
        }
        // How far the bar has slid away: the page's fixed bottom bar moves down by as much.
        mView.setVerticalClipping(Math.round(maxHeight - shown));
    }

    private void showBar() {
        animateBar(1);
        mRoot.removeCallbacks(mHideBar);
        mRoot.postDelayed(mHideBar, BAR_HIDE_DELAY_MS);
    }

    private void hideBar() {
        mRoot.removeCallbacks(mHideBar);
        animateBar(0);
    }

    private void animateBar(float target) {
        if (mBarAnimator != null) {
            mBarAnimator.cancel();
        }
        if (mBarFraction == target || !barAllowed()) {
            mBarFraction = target;
            applyBar();
            return;
        }
        mBarAnimator = ValueAnimator.ofFloat(mBarFraction, target);
        mBarAnimator.setDuration(BAR_ANIMATION_MS);
        mBarAnimator.addUpdateListener(a -> {
            mBarFraction = (float) a.getAnimatedValue();
            applyBar();
        });
        mBarAnimator.start();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (barAllowed()) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mTouchStartX = ev.getRawX();
                    mTouchStartY = ev.getRawY();
                    mGestureHandled = false;
                    int[] at = new int[2];
                    mTabBar.getLocationOnScreen(at);
                    mTouchOnBar = mBarFraction > 0 && ev.getRawY() < at[1] + mTabBar.getHeight();
                    if (mTouchOnBar) {
                        mRoot.removeCallbacks(mHideBar); // not while it's being used
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dy = ev.getRawY() - mTouchStartY;
                    float dx = ev.getRawX() - mTouchStartX;
                    if (!mTouchOnBar && !mGestureHandled && Math.abs(dy) > dp(24) && Math.abs(dy) > Math.abs(dx)) {
                        mGestureHandled = true;
                        if (dy > 0) {
                            showBar(); // finger down: scrolling up
                        } else if (mBarFraction > 0) {
                            hideBar();
                        }
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (mTouchOnBar) {
                        mRoot.postDelayed(mHideBar, BAR_HIDE_DELAY_MS);
                    }
                    break;
                default:
                    break;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    /** Shows a tab, creating its page on first use, and loads url in it if given. */
    private void open(int tab, String url) {
        GeckoSession session = mSessions[tab];
        if (session == null) {
            session = newSession(tab == TAB_MUSIC);
            mSessions[tab] = session;
            session.loadUri(url != null ? url : HOMES[tab]);
        } else if (url != null) {
            session.loadUri(url);
        }
        if (mView.getSession() != session) {
            GeckoSession shown = mView.getSession();
            if (shown != null) {
                if (mFullScreen) {
                    shown.exitFullScreen();
                }
                mView.releaseSession();
                // Hidden tabs keep playing audio, like background tabs in Firefox.
                shown.setActive(false);
                GeckoApp.get().runtime().getWebExtensionController().setTabActive(shown, false);
            }
            mView.setSession(session);
            session.setActive(true);
            GeckoApp.get().runtime().getWebExtensionController().setTabActive(session, true);
        }
        mTab = tab;
        updateTabBar();
    }

    private GeckoSession newSession(boolean music) {
        GeckoSession session = new GeckoSession(new GeckoSessionSettings.Builder()
                .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                .build());
        session.setContentDelegate(new Content());
        session.setNavigationDelegate(new Navigation());
        session.setPermissionDelegate(new Permissions());
        session.setPromptDelegate(new Prompts(this));
        session.setMediaSessionDelegate(Playback.delegate(this::onMediaPlay, music));
        session.open(GeckoApp.get().runtime());
        return session;
    }

    private int tabOf(GeckoSession session) {
        return session == mSessions[TAB_MUSIC] ? TAB_MUSIC : TAB_YOUTUBE;
    }

    /** Which tab a YouTube or YouTube Music link belongs to, or fallback for anything else. */
    private static int tabFor(Uri uri, int fallback) {
        String host = uri.getHost();
        if (host == null) {
            return fallback;
        }
        if (host.equals("music.youtube.com")) {
            return TAB_MUSIC;
        }
        if (host.equals("youtu.be") || host.equals("youtube.com") || host.equals("m.youtube.com")
                || host.equals("www.youtube.com")) {
            return TAB_YOUTUBE;
        }
        return fallback;
    }

    /** A link in one tab to the other site: open it in that site's tab instead. */
    private boolean belongsToOtherTab(GeckoSession from, Uri uri) {
        int to = tabFor(uri, -1);
        if (to < 0 || to == tabOf(from)) {
            return false;
        }
        String path = uri.getPath() == null ? "/" : uri.getPath();
        if (to == TAB_YOUTUBE) {
            return "youtu.be".equals(uri.getHost()) || YOUTUBE_PAGE.matcher(path).find();
        }
        return !path.contains("signin") && !path.contains("logout");
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private boolean backgroundPlayEnabled() {
        return Extensions.prefs(this).getBoolean(Extensions.PREF_BACKGROUND_PLAY, true);
    }

    private void onMediaPlay() {
        if (!backgroundPlayEnabled()) {
            return;
        }
        // The media notification is exempt from the notification permission, but ask once so
        // it also shows on phones that hide it without the permission.
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED
                && !Extensions.prefs(this).getBoolean("asked_notifications", false)) {
            Extensions.prefs(this).edit().putBoolean("asked_notifications", true).apply();
            requestPermissions(new String[] {android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        PlaybackService.start(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Back from the pop-up player: full size again, even if the mode change was missed.
        if (!isInPictureInPictureMode()) {
            unfreezePageSize();
            GeckoApp.get().setPictureInPicture(false);
            showBar(); // back in the app (from Settings, the pop-up or another app)
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // With background playback off, leaving the app pauses the video. Picture-in-picture
        // is still "started", so it doesn't pause.
        if (!backgroundPlayEnabled() && !isInPictureInPictureMode() && !isChangingConfigurations()) {
            Playback.pause();
        }
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(mPipReceiver);
        Playback.removeListener(mPipUpdater);
        mView.releaseSession();
        for (int i = 0; i < mSessions.length; i++) {
            if (mSessions[i] != null) {
                mSessions[i].close();
                mSessions[i] = null;
            }
        }
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        handleBack();
    }

    private void handleBack() {
        GeckoSession session = mSessions[mTab];
        if (mFullScreen) {
            session.exitFullScreen();
        } else if (mCanGoBack[mTab]) {
            session.goBack();
        } else if (mTab == TAB_MUSIC) {
            open(TAB_YOUTUBE, null);
        } else {
            // Like the real app: go to the home screen but keep playing.
            moveTaskToBack(true);
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // Leaving the app while a video is full screen shrinks it to picture-in-picture.
        if (mFullScreen && getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            try {
                // Show only the video in the pop-up (tweaks.css), without YouTube's controls.
                GeckoApp.get().setPictureInPicture(true);
                freezePageSize();
                enterPictureInPictureMode(pipParams());
            } catch (IllegalStateException e) {
                Log.w(TAG, "Picture-in-picture not available", e);
                unfreezePageSize();
                GeckoApp.get().setPictureInPicture(false);
            }
        }
    }

    // --- Picture-in-picture size --------------------------------------------------------
    // If the page saw the window shrink to pop-up size, YouTube would react (leave full screen,
    // re-lay out its player, pause). So the page keeps its full-screen size and is drawn scaled
    // down to fit the pop-up; for YouTube nothing changes.

    private int mFrozenWidth;
    private int mFrozenHeight;

    private final View.OnLayoutChangeListener mPipScaler =
            (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> fitPageToWindow();

    private void freezePageSize() {
        int width = mView.getWidth();
        int height = mView.getHeight();
        if (width <= 0 || height <= 0 || mFrozenWidth > 0) {
            return;
        }
        mFrozenWidth = width;
        mFrozenHeight = height;
        mView.setLayoutParams(new FrameLayout.LayoutParams(width, height));
        mView.setPivotX(0);
        mView.setPivotY(0);
        mRoot.addOnLayoutChangeListener(mPipScaler);
    }

    private void fitPageToWindow() {
        if (mFrozenWidth <= 0) {
            return;
        }
        int width = mRoot.getWidth() - mRoot.getPaddingLeft() - mRoot.getPaddingRight();
        int height = mRoot.getHeight() - mRoot.getPaddingTop() - mRoot.getPaddingBottom();
        if (width <= 0 || height <= 0) {
            return;
        }
        float scale = Math.min(1f, Math.min((float) width / mFrozenWidth, (float) height / mFrozenHeight));
        mView.setScaleX(scale);
        mView.setScaleY(scale);
        mView.setTranslationX((width - mFrozenWidth * scale) / 2f);
        mView.setTranslationY((height - mFrozenHeight * scale) / 2f - mView.getTop() + mRoot.getPaddingTop());
    }

    private void unfreezePageSize() {
        if (mFrozenWidth <= 0) {
            return;
        }
        mFrozenWidth = 0;
        mFrozenHeight = 0;
        mRoot.removeOnLayoutChangeListener(mPipScaler);
        mView.setScaleX(1);
        mView.setScaleY(1);
        mView.setTranslationX(0);
        mView.setTranslationY(0);
        mView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        applyBar();
    }

    // --- Picture-in-picture buttons ----------------------------------------------------
    // Android doesn't pass taps through to the page in the pop-up window, so YouTube's own
    // controls are hidden there (tweaks.css) and the window gets Android's buttons instead.

    private static final String PIP_ACTION = "local.youtube.gecko.PIP";
    private static final String PIP_COMMAND = "command";

    private final BroadcastReceiver mPipReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (String.valueOf(intent.getStringExtra(PIP_COMMAND))) {
                case "play_pause":
                    if (Playback.playing) {
                        Playback.pause();
                    } else {
                        Playback.play();
                    }
                    break;
                case "previous":
                    Playback.previous();
                    break;
                case "next":
                    Playback.next();
                    break;
                case "rewind":
                    Playback.seekBy(-10);
                    break;
                case "forward":
                    Playback.seekBy(10);
                    break;
                default:
                    break;
            }
        }
    };

    private final Playback.Listener mPipUpdater = () -> {
        if (isInPictureInPictureMode()) {
            setPictureInPictureParams(pipParams());
        }
    };

    private PictureInPictureParams pipParams() {
        boolean previous = Playback.has(org.mozilla.geckoview.MediaSession.Feature.PREVIOUS_TRACK);
        boolean next = Playback.has(org.mozilla.geckoview.MediaSession.Feature.NEXT_TRACK);
        java.util.List<android.app.RemoteAction> actions = new java.util.ArrayList<>();
        actions.add(previous
                ? pipAction(android.R.drawable.ic_media_previous, R.string.previous, "previous")
                : pipAction(R.drawable.ic_replay_10, R.string.rewind_10, "rewind"));
        actions.add(Playback.playing
                ? pipAction(android.R.drawable.ic_media_pause, R.string.pause, "play_pause")
                : pipAction(android.R.drawable.ic_media_play, R.string.play, "play_pause"));
        actions.add(next
                ? pipAction(android.R.drawable.ic_media_next, R.string.next, "next")
                : pipAction(R.drawable.ic_forward_10, R.string.forward_10, "forward"));
        int max = getMaxNumPictureInPictureActions();
        if (actions.size() > max) {
            // Keep play/pause if the system allows fewer buttons.
            actions = max <= 1 ? actions.subList(1, 2) : actions.subList(0, max);
        }
        return new PictureInPictureParams.Builder()
                .setAspectRatio(pipAspectRatio())
                .setActions(actions)
                .build();
    }

    /** The page's own shape (Android allows between 1:2.39 and 2.39:1), else 16:9. */
    private Rational pipAspectRatio() {
        if (mFrozenWidth <= 0 || mFrozenHeight <= 0) {
            return new Rational(16, 9);
        }
        float ratio = Math.max(1 / 2.39f, Math.min(2.39f, (float) mFrozenWidth / mFrozenHeight));
        return new Rational(Math.round(ratio * 1000), 1000);
    }

    private android.app.RemoteAction pipAction(int icon, int label, String command) {
        Intent intent = new Intent(PIP_ACTION).setPackage(getPackageName()).putExtra(PIP_COMMAND, command);
        android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(this, command.hashCode(), intent,
                android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
        return new android.app.RemoteAction(android.graphics.drawable.Icon.createWithResource(this, icon),
                getString(label), getString(label), pi);
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPip, Configuration newConfig) {
        super.onPictureInPictureModeChanged(inPip, newConfig);
        GeckoApp.get().setPictureInPicture(inPip);
        if (inPip) {
            fitPageToWindow();
        } else {
            unfreezePageSize();
        }
        updateTabBarVisibility();
        if (!inPip && mFullScreen && !isFinishing()) {
            setFullScreen(true);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Folding, unfolding and window resizing: the page re-lays itself out as GeckoView
        // resizes; refresh the app's own padding and bars for the new size.
        if (mFullScreen && newConfig.smallestScreenWidthDp >= 600) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
        updateTabBarVisibility();
        mRoot.requestApplyInsets();
    }

    private static String urlFromIntent(Intent intent) {
        if (intent == null) {
            return null;
        }
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            return intent.getData().toString();
        }
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null) {
                Matcher m = URL_IN_TEXT.matcher(text);
                if (m.find()) {
                    return m.group();
                }
            }
        }
        return null;
    }

    private static boolean isInternal(Uri uri) {
        String scheme = uri.getScheme();
        if ("about".equals(scheme) || "data".equals(scheme) || "blob".equals(scheme)
                || "resource".equals(scheme) || "moz-extension".equals(scheme)) {
            return true;
        }
        String host = uri.getHost();
        return host != null && INTERNAL_HOST.matcher(host).find();
    }

    private void openExternally(String url) {
        try {
            Intent intent = url.startsWith("intent:")
                    ? Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    : new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            intent.setComponent(null);
            intent.setSelector(null);
            startActivity(intent);
        } catch (ActivityNotFoundException | java.net.URISyntaxException e) {
            Log.w(TAG, "No app for " + url);
        }
    }

    private WindowInsets applyInsets(View v, WindowInsets insets) {
        if (mFullScreen) {
            v.setPadding(0, 0, 0, 0);
        } else if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
        } else {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
        }
        return insets;
    }

    @SuppressWarnings("deprecation")
    private void setFullScreen(boolean fullScreen) {
        mFullScreen = fullScreen;
        View decor = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = decor.getWindowInsetsController();
            if (c != null) {
                if (fullScreen) {
                    c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    c.hide(WindowInsets.Type.systemBars());
                } else {
                    c.show(WindowInsets.Type.systemBars());
                }
            }
        } else {
            decor.setSystemUiVisibility(fullScreen
                    ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    : View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        if (fullScreen) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            // Phones turn to landscape for full-screen video; large screens (unfolded foldables,
            // tablets) already have room, so they keep whatever orientation they're in.
            if (getResources().getConfiguration().smallestScreenWidthDp < 600) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            }
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
        updateTabBarVisibility();
        mRoot.requestApplyInsets();
    }

    private class Content implements ContentDelegate {
        @Override
        public void onFullScreen(GeckoSession session, boolean fullScreen) {
            setFullScreen(fullScreen);
        }

        @Override
        public void onCrash(GeckoSession session) {
            recreateSession();
        }

        @Override
        public void onKill(GeckoSession session) {
            recreateSession();
        }
    }

    private void recreateSession() {
        // onDestroy closes the sessions.
        recreate();
    }

    private class Navigation implements NavigationDelegate {
        @Override
        public void onCanGoBack(GeckoSession session, boolean canGoBack) {
            mCanGoBack[tabOf(session)] = canGoBack;
        }

        @Override
        public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession session, LoadRequest request) {
            Uri uri = Uri.parse(request.uri);
            if (!request.isRedirect && belongsToOtherTab(session, uri)) {
                String url = request.uri;
                mRoot.post(() -> open(tabFor(uri, mTab), url));
                return GeckoResult.deny();
            }
            if (isInternal(uri)) {
                return GeckoResult.allow();
            }
            openExternally(request.uri);
            return GeckoResult.deny();
        }

        @Override
        public GeckoResult<GeckoSession> onNewSession(GeckoSession session, String uri) {
            // Links that would open a new tab open in this one (or the other site's tab) instead.
            Uri parsed = Uri.parse(uri);
            if (belongsToOtherTab(session, parsed)) {
                mRoot.post(() -> open(tabFor(parsed, mTab), uri));
            } else if (isInternal(parsed)) {
                session.loadUri(uri);
            } else {
                openExternally(uri);
            }
            return null;
        }
    }

    private static class Permissions implements PermissionDelegate {
        @Override
        public GeckoResult<Integer> onContentPermissionRequest(GeckoSession session, ContentPermission perm) {
            if (perm.permission == PERMISSION_AUTOPLAY_AUDIBLE
                    || perm.permission == PERMISSION_AUTOPLAY_INAUDIBLE
                    || perm.permission == PERMISSION_MEDIA_KEY_SYSTEM_ACCESS
                    || perm.permission == PERMISSION_PERSISTENT_STORAGE) {
                return GeckoResult.fromValue(ContentPermission.VALUE_ALLOW);
            }
            return GeckoResult.fromValue(ContentPermission.VALUE_DENY);
        }
    }
}
