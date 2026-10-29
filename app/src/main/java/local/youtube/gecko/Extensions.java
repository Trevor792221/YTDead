package local.youtube.gecko;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;

import java.util.ArrayList;
import java.util.List;

/** The extensions offered in Settings, installed and updated from addons.mozilla.org. */
final class Extensions {
    static final class Info {
        final String id;
        final String slug;
        final String name;
        final String summary;
        /** The extension's toolbar pop-up page, shown full screen by "Open"; null if none. */
        final String popupPath;
        /** Included in "Install all". */
        final boolean recommended;

        Info(String id, String slug, String name, String summary, String popupPath, boolean recommended) {
            this.id = id;
            this.slug = slug;
            this.name = name;
            this.summary = summary;
            this.popupPath = popupPath;
            this.recommended = recommended;
        }

        String downloadUrl() {
            return "https://addons.mozilla.org/firefox/downloads/latest/" + slug + "/latest.xpi";
        }
    }

    static final Info[] CATALOG = {
        new Info("uBlock0@raymondhill.net", "ublock-origin", "uBlock Origin",
                "Blocks ads, trackers and pop-ups. Open shows what it blocked on the current page.",
                "popup-fenix.html", true),
        new Info("sponsorBlocker@ajay.app", "sponsorblock", "SponsorBlock",
                "Skips sponsor segments, intros, outros and other parts of videos marked by viewers.",
                "popup.html", true),
        new Info("{762f9885-5a13-4abd-9c77-433dcd38b8fd}", "return-youtube-dislikes",
                "Return YouTube Dislike", "Shows dislike counts again.", "popup.html", true),
        new Info("{446900e4-71c2-419f-a6a7-df9c091e268b}", "bitwarden-password-manager",
                "Bitwarden", "Fills passwords from your Bitwarden vault. Use Open to sign in to Bitwarden. "
                        + "Made for desktop Firefox, so some features may not work here; the Bitwarden "
                        + "Android app also fills sign-in forms in YTDead.",
                "popup/index.html", false),
    };

    static final String PREFS = "settings";
    static final String PREF_AUTO_UPDATE = "auto_update";
    private static final String PREF_LAST_UPDATE_CHECK = "last_update_check";
    static final String PREF_ONBOARDED = "onboarded";
    static final String PREF_BACKGROUND_PLAY = "background_play";
    static final String PREF_HIGHEST_QUALITY = "highest_quality";
    /** Crossfade length on YouTube Music, in seconds; 0 is off. */
    static final String PREF_CROSSFADE = "crossfade_seconds";
    static final int CROSSFADE_DEFAULT = 6;
    private static final long UPDATE_INTERVAL_MS = 24L * 60 * 60 * 1000;

    private Extensions() {
    }

    static Info find(String id) {
        for (Info info : CATALOG) {
            if (info.id.equals(id)) {
                return info;
            }
        }
        return null;
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static WebExtensionController controller() {
        return GeckoApp.get().runtime().getWebExtensionController();
    }

    /** Lets an extension open its settings page and new tabs (e.g. uBlock Origin's dashboard). */
    static void attach(Context context, WebExtension extension) {
        if (extension.isBuiltIn) {
            return;
        }
        extension.setTabDelegate(new WebExtension.TabDelegate() {
            @Override
            public GeckoResult<GeckoSession> onNewTab(WebExtension source, WebExtension.CreateTabDetails details) {
                if (details.url != null) {
                    openPage(details.url, source.metaData.name);
                }
                return null;
            }

            @Override
            public void onOpenOptionsPage(WebExtension source) {
                if (source.metaData.optionsPageUrl != null) {
                    openPage(source.metaData.optionsPageUrl, source.metaData.name);
                }
            }
        });
    }

    static void openPage(String url, String title) {
        Activity activity = GeckoApp.get().currentActivity();
        if (activity == null) {
            return;
        }
        activity.startActivity(new Intent(activity, ExtensionPageActivity.class)
                .putExtra(ExtensionPageActivity.EXTRA_URL, url)
                .putExtra(ExtensionPageActivity.EXTRA_TITLE, title));
    }

    static GeckoResult<WebExtension> install(Info info) {
        return controller().install(info.downloadUrl(), WebExtensionController.INSTALLATION_METHOD_MANAGER);
    }

    /** Checks every installed extension from the catalog; resolves to the number updated. */
    static GeckoResult<Integer> updateAll(Context context) {
        WebExtensionController controller = controller();
        return controller.list().then(list -> {
            List<GeckoResult<WebExtension>> updates = new ArrayList<>();
            for (WebExtension ext : list) {
                if (find(ext.id) != null && !ext.isBuiltIn) {
                    updates.add(controller.update(ext).exceptionally(e -> {
                        Log.w(GeckoApp.TAG, "Update failed: " + ext.id, e);
                        return GeckoResult.fromValue(null);
                    }));
                }
            }
            prefs(context).edit().putLong(PREF_LAST_UPDATE_CHECK, System.currentTimeMillis()).apply();
            if (updates.isEmpty()) {
                return GeckoResult.fromValue(0);
            }
            return GeckoResult.allOf(updates).map(results -> {
                int updated = 0;
                for (WebExtension result : results) {
                    if (result != null) {
                        updated++;
                    }
                }
                return updated;
            });
        });
    }

    static void onStartup(GeckoApp app, WebExtensionController controller) {
        controller.list().accept(list -> {
            for (WebExtension ext : list) {
                Info info = find(ext.id);
                if (info != null && ext.isBuiltIn) {
                    // Version 1.1 bundled these; swap them for the add-ons.mozilla.org versions,
                    // which can be updated.
                    Runnable reinstall = () -> install(info).accept(
                            null, e -> Log.e(GeckoApp.TAG, "Reinstall failed: " + info.id, e));
                    controller.uninstall(ext).accept(v -> reinstall.run(), e -> reinstall.run());
                } else {
                    attach(app, ext);
                }
            }
            SharedPreferences prefs = prefs(app);
            long last = prefs.getLong(PREF_LAST_UPDATE_CHECK, 0);
            if (prefs.getBoolean(PREF_AUTO_UPDATE, true)
                    && System.currentTimeMillis() - last > UPDATE_INTERVAL_MS) {
                updateAll(app).accept(
                        n -> Log.i(GeckoApp.TAG, "Extensions updated: " + n),
                        e -> Log.w(GeckoApp.TAG, "Update check failed", e));
            }
        }, e -> Log.e(GeckoApp.TAG, "Listing extensions failed", e));
    }
}
