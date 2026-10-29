package local.youtube.gecko;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.Rational;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
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

/** A single full-screen GeckoView showing YouTube's mobile (app-style) site. */
public class MainActivity extends Activity {
    private static final String TAG = GeckoApp.TAG;
    private static final String HOME = "https://m.youtube.com/";

    /** Hosts that stay inside the app; any other link opens in the phone's browser. */
    private static final Pattern INTERNAL_HOST = Pattern.compile(
            "(^|\\.)(youtube\\.com|youtu\\.be|youtube-nocookie\\.com|google\\.com|google\\.[a-z.]+"
                    + "|gstatic\\.com|googleusercontent\\.com|googlevideo\\.com|ytimg\\.com|ggpht\\.com)$");

    private static final Pattern URL_IN_TEXT = Pattern.compile("https?://\\S+");

    private FrameLayout mRoot;
    private GeckoView mView;
    private GeckoSession mSession;
    private boolean mCanGoBack;
    private boolean mFullScreen;
    /** The page went full screen only to fill a pop-up (picture-in-picture) window. */
    private boolean mSwipePip;
    /** Set when picture-in-picture ends; cleared if the app comes back, so onStop knows it was closed. */
    private boolean mPipEnded;
    private boolean mResumed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mRoot = new FrameLayout(this);
        mRoot.setBackgroundColor(getColor(R.color.background));
        mView = new GeckoView(this);
        mView.setAutofillEnabled(true);
        mView.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);
        mRoot.addView(mView);
        setContentView(mRoot);
        mRoot.setOnApplyWindowInsetsListener(this::applyInsets);
        setFullScreen(false);

        mSession = new GeckoSession(new GeckoSessionSettings.Builder()
                .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                .build());
        mSession.setContentDelegate(new Content());
        mSession.setNavigationDelegate(new Navigation());
        mSession.setPermissionDelegate(new Permissions());
        mSession.setPromptDelegate(new Prompts(this));
        mSession.setMediaSessionDelegate(Playback.delegate(this::onMediaPlay));
        mSession.open(GeckoApp.get().runtime());
        mView.setSession(mSession);

        String url = urlFromIntent(getIntent());
        mSession.loadUri(url != null ? url : HOME);

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
            mSession.loadUri(url);
        }
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
        mResumed = true;
        mPipEnded = false;
    }

    @Override
    protected void onPause() {
        mResumed = false;
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mPipEnded) {
            // The pop-up window was closed rather than expanded: stop, like the YouTube app.
            mPipEnded = false;
            Playback.pause();
            return;
        }
        // With background playback off, leaving the app pauses the video. Picture-in-picture
        // is still "started", so it doesn't pause.
        if (!backgroundPlayEnabled() && !isInPictureInPictureMode() && !isChangingConfigurations()) {
            Playback.pause();
        }
    }

    @Override
    protected void onDestroy() {
        if (mSession != null) {
            mView.releaseSession();
            mSession.close();
        }
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        handleBack();
    }

    private void handleBack() {
        if (mFullScreen) {
            mSession.exitFullScreen();
        } else if (mCanGoBack) {
            mSession.goBack();
        } else {
            // Like the real app: go to the home screen but keep playing.
            moveTaskToBack(true);
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // Leaving the app while a video is full screen shrinks it to picture-in-picture.
        if (mFullScreen) {
            enterPictureInPictureMode(new PictureInPictureParams.Builder()
                    .setAspectRatio(new Rational(16, 9))
                    .build());
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPip, Configuration newConfig) {
        super.onPictureInPictureModeChanged(inPip, newConfig);
        if (inPip || isFinishing()) {
            return;
        }
        // Expanding the window resumes the app (before or after this call); closing it doesn't.
        mPipEnded = !mResumed;
        if (mSwipePip) {
            // Back from a swipe pop-up: return the page to its normal layout.
            endSwipePip();
        } else if (mFullScreen) {
            setFullScreen(true);
        }
    }

    /**
     * Swipe-down pop-up player, driven by the helper extension's swipe-pip.js:
     * "start" before the page goes full screen, "enter" once it has, "cancel" if it failed.
     */
    boolean swipePip(String phase, int width, int height) {
        switch (phase) {
            case "start":
                if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
                        || isInPictureInPictureMode()) {
                    return false;
                }
                mSwipePip = true;
                return true;
            case "enter":
                if (!mSwipePip) {
                    return false;
                }
                try {
                    if (enterPictureInPictureMode(new PictureInPictureParams.Builder()
                            .setAspectRatio(aspectRatio(width, height))
                            .build())) {
                        return true;
                    }
                } catch (IllegalStateException | IllegalArgumentException e) {
                    Log.w(TAG, "Picture-in-picture failed", e);
                }
                endSwipePip();
                return false;
            default:
                endSwipePip();
                return true;
        }
    }

    private void endSwipePip() {
        mSwipePip = false;
        if (mFullScreen) {
            mSession.exitFullScreen();
        }
    }

    /** Android only allows pop-up windows between 2.39:1 and 1:2.39. */
    private static Rational aspectRatio(int width, int height) {
        if (width <= 0 || height <= 0) {
            return new Rational(16, 9);
        }
        double r = (double) width / height;
        if (r > 2.39) {
            return new Rational(239, 100);
        }
        if (r < 1 / 2.39) {
            return new Rational(100, 239);
        }
        return new Rational(width, height);
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
            // Wait a moment: a swipe pop-up goes full screen too, and must not rotate.
            mRoot.postDelayed(() -> {
                if (mFullScreen && !mSwipePip && !isInPictureInPictureMode()) {
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                }
            }, 300);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
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
        mView.releaseSession();
        mSession.close();
        mSession = null;
        recreate();
    }

    private class Navigation implements NavigationDelegate {
        @Override
        public void onCanGoBack(GeckoSession session, boolean canGoBack) {
            mCanGoBack = canGoBack;
        }

        @Override
        public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession session, LoadRequest request) {
            Uri uri = Uri.parse(request.uri);
            if (isInternal(uri)) {
                return GeckoResult.allow();
            }
            openExternally(request.uri);
            return GeckoResult.deny();
        }

        @Override
        public GeckoResult<GeckoSession> onNewSession(GeckoSession session, String uri) {
            // Links that would open a new tab open in this one instead.
            if (isInternal(Uri.parse(uri))) {
                mSession.loadUri(uri);
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
