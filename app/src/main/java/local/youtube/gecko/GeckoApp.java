package local.youtube.gecko;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONObject;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns the one GeckoRuntime shared by every screen, the app's own helper extension,
 * and the currently visible activity (needed for passkey and dialog UI).
 */
public class GeckoApp extends Application implements Application.ActivityLifecycleCallbacks {
    static final String TAG = "YouTubeGecko";

    private static final String HELPER_URI = "resource://android/assets/extensions/tweaks/";
    private static final String HELPER_ID = "tweaks@youtube.gecko.local";
    private static final String NATIVE_APP = "ytgecko";

    private static GeckoApp sInstance;

    private GeckoRuntime mRuntime;
    private Activity mCurrentActivity;
    private final List<Runnable> mExtensionListeners = new ArrayList<>();

    static GeckoApp get() {
        return sInstance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        registerActivityLifecycleCallbacks(this);
    }

    /**
     * Created on first use from an activity, never in Application.onCreate: GeckoView's
     * content processes also run this Application class and must not start a runtime.
     */
    GeckoRuntime runtime() {
        if (mRuntime == null) {
            GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                    .preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_DARK)
                    .automaticFontSizeAdjustment(true)
                    .loginAutofillEnabled(true)
                    .consoleOutput(false)
                    .build();
            mRuntime = GeckoRuntime.create(this, settings);
            setUpExtensions(mRuntime.getWebExtensionController());
        }
        return mRuntime;
    }

    Activity currentActivity() {
        return mCurrentActivity;
    }

    void addExtensionListener(Runnable listener) {
        mExtensionListeners.add(listener);
    }

    void removeExtensionListener(Runnable listener) {
        mExtensionListeners.remove(listener);
    }

    void notifyExtensionsChanged() {
        for (Runnable listener : new ArrayList<>(mExtensionListeners)) {
            listener.run();
        }
    }

    private void setUpExtensions(WebExtensionController controller) {
        // Installs and updates are only started from the settings screen, so accept their prompts.
        controller.setPromptDelegate(new WebExtensionController.PromptDelegate() {
            @Override
            public GeckoResult<WebExtension.PermissionPromptResponse> onInstallPromptRequest(
                    WebExtension extension, String[] permissions, String[] origins, String[] dataCollection) {
                return GeckoResult.fromValue(new WebExtension.PermissionPromptResponse(true, false, false));
            }

            @Override
            public GeckoResult<AllowOrDeny> onUpdatePrompt(
                    WebExtension extension, String[] permissions, String[] origins, String[] dataCollection) {
                return GeckoResult.allow();
            }

            @Override
            public GeckoResult<AllowOrDeny> onOptionalPrompt(
                    WebExtension extension, String[] permissions, String[] origins, String[] dataCollection) {
                return GeckoResult.allow();
            }
        });

        controller.setAddonManagerDelegate(new WebExtensionController.AddonManagerDelegate() {
            @Override
            public void onReady(WebExtension extension) {
                Extensions.attach(GeckoApp.this, extension);
            }

            @Override
            public void onInstalled(WebExtension extension) {
                Extensions.attach(GeckoApp.this, extension);
                notifyExtensionsChanged();
            }

            @Override
            public void onUninstalled(WebExtension extension) {
                notifyExtensionsChanged();
            }

            @Override
            public void onEnabled(WebExtension extension) {
                notifyExtensionsChanged();
            }

            @Override
            public void onDisabled(WebExtension extension) {
                notifyExtensionsChanged();
            }
        });

        controller.ensureBuiltIn(HELPER_URI, HELPER_ID).accept(
                extension -> extension.setMessageDelegate(new NativeMessages(), NATIVE_APP),
                e -> Log.e(TAG, "Helper extension failed", e));

        Extensions.onStartup(this, controller);
    }

    /** Messages from the helper extension's background script. */
    private class NativeMessages implements WebExtension.MessageDelegate {
        @Override
        public GeckoResult<Object> onMessage(String nativeApp, Object message, WebExtension.MessageSender sender) {
            if (!(message instanceof JSONObject)
                    || sender.environmentType != WebExtension.MessageSender.ENV_TYPE_EXTENSION) {
                return null;
            }
            JSONObject msg = (JSONObject) message;
            switch (msg.optString("type")) {
                case "openSettings":
                    Activity activity = mCurrentActivity;
                    if (activity != null) {
                        activity.startActivity(new Intent(activity, SettingsActivity.class));
                    }
                    return GeckoResult.fromValue(true);
                case "webauthn":
                    return Passkeys.handle(mCurrentActivity, msg);
                default:
                    return null;
            }
        }
    }

    @Override
    public void onActivityResumed(Activity activity) {
        mCurrentActivity = activity;
    }

    @Override
    public void onActivityPaused(Activity activity) {
        // Keep the reference while paused: the passkey sheet pauses the activity that opened it.
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
        if (mCurrentActivity == activity) {
            mCurrentActivity = null;
        }
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
    }

    @Override
    public void onActivityStarted(Activity activity) {
    }

    @Override
    public void onActivityStopped(Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    }
}
