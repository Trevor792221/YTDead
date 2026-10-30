package local.youtube.gecko;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
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
    };

    /** Where "Get add-ons" goes: the add-ons that work in Firefox for Android. */
    static final String ADDONS_SITE = "https://addons.mozilla.org/android/";

    static final String PREFS = "settings";
    static final String PREF_AUTO_UPDATE = "auto_update";
    private static final String PREF_LAST_UPDATE_CHECK = "last_update_check";
    static final String PREF_ONBOARDED = "onboarded";
    static final String PREF_BACKGROUND_PLAY = "background_play";
    static final String PREF_HIGHEST_QUALITY = "highest_quality";
    /** Apply the phone's font size to pages (off by default: it can make text overlap icons). */
    static final String PREF_SYSTEM_TEXT_SIZE = "system_text_size";
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

    /**
     * Lets an extension open its settings page and new tabs (e.g. uBlock Origin's dashboard),
     * but only from one of its own pages the user has open. Pages an extension opens by itself,
     * like "Welcome" and "What's new" pages after installing or updating, are blocked.
     */
    static void attach(Context context, WebExtension extension) {
        if (extension.isBuiltIn) {
            return;
        }
        extension.setTabDelegate(new WebExtension.TabDelegate() {
            @Override
            public GeckoResult<GeckoSession> onNewTab(WebExtension source, WebExtension.CreateTabDetails details) {
                if (details.url != null && userInExtensionPage()) {
                    openPage(details.url, source.metaData.name);
                }
                return null;
            }

            @Override
            public void onOpenOptionsPage(WebExtension source) {
                if (source.metaData.optionsPageUrl != null && userInExtensionPage()) {
                    openPage(source.metaData.optionsPageUrl, source.metaData.name);
                }
            }
        });
    }

    private static boolean userInExtensionPage() {
        return GeckoApp.get().currentActivity() instanceof ExtensionPageActivity;
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

    /** An add-on's install file (.xpi), e.g. behind addons.mozilla.org's "Add to Firefox". */
    static boolean isAddonFile(String url) {
        if (url == null || !url.startsWith("https://")) {
            return false;
        }
        String path = url.replaceFirst("[?#].*$", "");
        return path.endsWith(".xpi")
                || path.startsWith("https://addons.mozilla.org/firefox/downloads/");
    }

    /**
     * The install file for what someone pasted: an addons.mozilla.org page link, an add-on's
     * name from such a link, or a link straight to an .xpi file. Null if it isn't one of those.
     */
    static String downloadFor(String text) {
        String t = text == null ? "" : text.trim();
        if (isAddonFile(t)) {
            return t;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("addons\\.mozilla\\.org/(?:[^/]+/)*addon/([^/?#]+)").matcher(t);
        String slug = m.find() ? m.group(1) : t.matches("[A-Za-z0-9._@{}-]+") ? t : null;
        return slug == null ? null
                : "https://addons.mozilla.org/firefox/downloads/latest/" + Uri.encode(slug) + "/latest.xpi";
    }

    /** Installs an add-on you picked yourself; you're asked to confirm what it can access first. */
    static GeckoResult<WebExtension> installFromUrl(String url) {
        return controller().install(url, WebExtensionController.INSTALLATION_METHOD_MANAGER);
    }

    /**
     * Asks before installing an add-on that isn't one of the app's own choices, showing what it
     * will be able to access. Resolves to the answer (no if nothing is on screen to ask).
     */
    static GeckoResult<WebExtension.PermissionPromptResponse> confirmInstall(
            WebExtension extension, String[] permissions, String[] origins) {
        GeckoResult<WebExtension.PermissionPromptResponse> result = new GeckoResult<>();
        WebExtension.PermissionPromptResponse no = new WebExtension.PermissionPromptResponse(false, false, false);
        Activity activity = GeckoApp.get().currentActivity();
        if (activity == null || activity.isFinishing()) {
            result.complete(no);
            return result;
        }
        StringBuilder message = new StringBuilder();
        java.util.Set<String> can = new java.util.LinkedHashSet<>();
        for (String p : permissions == null ? new String[0] : permissions) {
            String described = describePermission(p);
            if (described != null) {
                can.add(described);
            }
        }
        boolean allSites = false;
        java.util.Set<String> sites = new java.util.LinkedHashSet<>();
        for (String o : origins == null ? new String[0] : origins) {
            if (o.equals("<all_urls>") || o.matches("\\*://\\*/.*") || o.matches("https?://\\*/.*")) {
                allSites = true;
            } else {
                sites.add(o.replaceFirst("^[^:]*://", "").replaceFirst("/.*$", ""));
            }
        }
        if (allSites) {
            can.add("Read and change everything on every website");
        } else if (!sites.isEmpty()) {
            can.add("Read and change pages on " + String.join(", ", sites));
        }
        if (can.isEmpty()) {
            message.append("It doesn't ask for any special access.");
        } else {
            message.append("It will be able to:");
            for (String line : can) {
                message.append("\n• ").append(line);
            }
        }
        message.append("\n\nOnly add add-ons you trust.");
        boolean[] answered = {false};
        new android.app.AlertDialog.Builder(activity)
                .setTitle("Add " + extension.metaData.name + "?")
                .setMessage(message)
                .setPositiveButton("Add", (d, w) -> {
                    answered[0] = true;
                    result.complete(new WebExtension.PermissionPromptResponse(true, false, false));
                })
                .setNegativeButton("Cancel", (d, w) -> {
                    answered[0] = true;
                    result.complete(no);
                })
                .setOnDismissListener(d -> {
                    if (!answered[0]) {
                        answered[0] = true;
                        result.complete(no);
                    }
                })
                .show();
        return result;
    }

    private static String describePermission(String permission) {
        switch (permission) {
            case "tabs":
            case "activeTab":
                return "See the pages you have open";
            case "webRequest":
            case "webRequestBlocking":
            case "declarativeNetRequest":
            case "declarativeNetRequestWithHostAccess":
                return "Block or change what pages load";
            case "cookies":
                return "Read and change cookies";
            case "history":
                return "Read your browsing history";
            case "downloads":
                return "Download files";
            case "clipboardRead":
                return "Read what you copy";
            case "clipboardWrite":
                return "Put text on your clipboard";
            case "notifications":
                return "Show notifications";
            case "nativeMessaging":
                return "Talk to other apps";
            case "privacy":
            case "proxy":
                return "Change network and privacy settings";
            case "geolocation":
                return "Use your location";
            default:
                return null; // storage, alarms, menus, ... nothing a person needs to weigh
        }
    }

    /** Checks every installed add-on (not the app's own helper); resolves to the number updated. */
    static GeckoResult<Integer> updateAll(Context context) {
        WebExtensionController controller = controller();
        return controller.list().then(list -> {
            List<GeckoResult<WebExtension>> updates = new ArrayList<>();
            for (WebExtension ext : list) {
                if (!ext.isBuiltIn) {
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
