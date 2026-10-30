package local.youtube.gecko;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.credentials.CredentialManager;

import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Install, update, enable and configure the extensions; links to password and passkey settings. */
public class SettingsActivity extends Activity {
    private static final int TEXT = 0xFFF1F1F1;
    private static final int TEXT_DIM = 0xFFAAAAAA;
    private static final int CARD = 0xFF212121;
    private static final int ACCENT = 0xFFFF0033;

    private LinearLayout mList;
    private final Set<String> mBusy = new HashSet<>();
    private final Runnable mOnChanged = this::refresh;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.settings_title);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(getColor(R.color.background));
        scroll.setFillViewport(true);
        mList = new LinearLayout(this);
        mList.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        mList.setPadding(pad, pad, pad, pad);
        scroll.addView(mList);
        setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            }
            return insets;
        });

        GeckoApp.get().runtime();
        Extensions.prefs(this).edit().putBoolean(Extensions.PREF_ONBOARDED, true).apply();
    }

    @Override
    protected void onResume() {
        super.onResume();
        GeckoApp.get().addExtensionListener(mOnChanged);
        refresh();
    }

    @Override
    protected void onPause() {
        GeckoApp.get().removeExtensionListener(mOnChanged);
        super.onPause();
    }

    private void refresh() {
        Extensions.controller().list().accept(this::render,
                e -> Log.e(GeckoApp.TAG, "Listing extensions failed", e));
    }

    private void render(List<WebExtension> installed) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        Map<String, WebExtension> byId = new HashMap<>();
        for (WebExtension ext : installed) {
            byId.put(ext.id, ext);
        }

        mList.removeAllViews();
        mList.addView(heading(getString(R.string.settings_title), 24));

        mList.addView(heading("Extensions", 18));
        mList.addView(body("Downloaded from addons.mozilla.org, the official Firefox add-on site."));

        boolean anyMissing = false;
        for (Extensions.Info info : Extensions.CATALOG) {
            WebExtension ext = byId.get(info.id);
            anyMissing |= ext == null && info.recommended;
            mList.addView(extensionCard(info, ext));
        }

        LinearLayout row = buttonRow();
        if (anyMissing) {
            row.addView(button("Install all", true, v -> {
                for (Extensions.Info info : Extensions.CATALOG) {
                    if (byId.get(info.id) == null && info.recommended) {
                        install(info);
                    }
                }
            }));
        }
        row.addView(button("Check for updates", false, v -> checkForUpdates()));
        mList.addView(row);

        Switch auto = new Switch(this);
        auto.setText("Update extensions automatically (daily)");
        auto.setTextColor(TEXT);
        auto.setChecked(Extensions.prefs(this).getBoolean(Extensions.PREF_AUTO_UPDATE, true));
        auto.setOnCheckedChangeListener((b, on) ->
                Extensions.prefs(this).edit().putBoolean(Extensions.PREF_AUTO_UPDATE, on).apply());
        auto.setPadding(0, dp(8), 0, dp(8));
        mList.addView(auto);

        // Any other add-on, chosen by you.
        mList.addView(heading("Your add-ons", 18));
        mList.addView(body("Add any add-on made for Firefox for Android. Only add ones you trust: "
                + "add-ons can see and change the pages you visit."));
        for (WebExtension ext : installed) {
            if (!ext.isBuiltIn && Extensions.find(ext.id) == null) {
                String description = ext.metaData.description != null ? ext.metaData.description : "";
                mList.addView(extensionCard(new Extensions.Info(ext.id, null,
                        ext.metaData.name != null ? ext.metaData.name : ext.id, description, null, false), ext));
            }
        }
        LinearLayout addRow = buttonRow();
        addRow.addView(button("Get add-ons", true, v -> startActivity(
                new Intent(this, ExtensionPageActivity.class)
                        .putExtra(ExtensionPageActivity.EXTRA_URL, Extensions.ADDONS_SITE)
                        .putExtra(ExtensionPageActivity.EXTRA_TITLE, "Add-ons")
                        .putExtra(ExtensionPageActivity.EXTRA_STORE, true))));
        addRow.addView(button("Add from link", false, v -> addFromLink()));
        mList.addView(addRow);

        mList.addView(heading("Playback", 18));
        mList.addView(toggle("Highest video quality",
                "Always start videos at the best quality available, instead of the size the player picks.",
                Extensions.PREF_HIGHEST_QUALITY));
        mList.addView(crossfadeRow());
        mList.addView(toggle("Background playback",
                "Keep playing with the screen off or in other apps, with play/pause and skip controls "
                        + "in a notification.",
                Extensions.PREF_BACKGROUND_PLAY));

        mList.addView(heading("Display", 18));
        mList.addView(toggle("Use phone's text size",
                "Make YouTube's text follow the font size set in your phone's settings. Large "
                        + "sizes can make text overlap icons.",
                Extensions.PREF_SYSTEM_TEXT_SIZE, false,
                on -> GeckoApp.get().runtime().getSettings().setAutomaticFontSizeAdjustment(on)));

        mList.addView(heading("Passwords and passkeys", 18));
        mList.addView(body("Sign-in forms use your phone's password manager (autofill service), and passkeys "
                + "use your phone's passkey provider. If nothing is offered, check which ones are "
                + "selected here. Some password managers only work with browsers they recognise; in "
                + "Bitwarden, for example, allow this app when it asks, or add it as a trusted browser."));
        LinearLayout row2 = buttonRow();
        row2.addView(button("Autofill service", false, v -> openAutofillSettings()));
        row2.addView(button("Passkey providers", false, v -> openPasskeySettings()));
        mList.addView(row2);

        mList.addView(heading("About", 18));
        try {
            mList.addView(body("YTDead " + getPackageManager().getPackageInfo(getPackageName(), 0).versionName
                    + ". Free software under the GNU GPL version 3."));
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            // Can't happen for our own package.
        }
        LinearLayout row3 = buttonRow();
        row3.addView(button("Open-source licenses", false,
                v -> startActivity(new Intent(this, LicensesActivity.class))));
        mList.addView(row3);
    }

    private View extensionCard(Extensions.Info info, WebExtension ext) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(12));
        card.setBackground(bg);
        card.setPadding(dp(16), dp(14), dp(16), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        card.setLayoutParams(lp);

        TextView name = new TextView(this);
        name.setText(info.name);
        name.setTextColor(TEXT);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(name);

        TextView summary = new TextView(this);
        summary.setText(info.summary);
        summary.setTextColor(TEXT_DIM);
        card.addView(summary);

        TextView status = new TextView(this);
        status.setPadding(0, dp(6), 0, 0);
        boolean busy = mBusy.contains(info.id);
        if (busy) {
            status.setText("Working…");
            status.setTextColor(TEXT);
        } else if (ext == null) {
            status.setText("Not installed");
            status.setTextColor(TEXT_DIM);
        } else {
            status.setText("Version " + ext.metaData.version + (ext.metaData.enabled ? " · On" : " · Off"));
            status.setTextColor(ext.metaData.enabled ? 0xFF3EA6FF : TEXT_DIM);
        }
        card.addView(status);

        LinearLayout row = buttonRow();
        if (!busy) {
            if (ext == null) {
                row.addView(button("Install", true, v -> install(info)));
            } else {
                WebExtensionController c = Extensions.controller();
                String base = ext.metaData.baseUrl;
                String popupUrl = info.popupPath != null && base != null
                        ? (base.endsWith("/") ? base : base + "/") + info.popupPath : null;
                if (popupUrl != null && ext.metaData.enabled && !popupUrl.equals(ext.metaData.optionsPageUrl)) {
                    row.addView(button("Open", true, v -> Extensions.openPage(popupUrl, info.name)));
                }
                if (ext.metaData.optionsPageUrl != null && ext.metaData.enabled) {
                    row.addView(button("Settings", true,
                            v -> Extensions.openPage(ext.metaData.optionsPageUrl, info.name)));
                }
                row.addView(button(ext.metaData.enabled ? "Turn off" : "Turn on", false, v -> run(info,
                        ext.metaData.enabled
                                ? c.disable(ext, WebExtensionController.EnableSource.USER)
                                : c.enable(ext, WebExtensionController.EnableSource.USER), null)));
                row.addView(button("Update", false, v -> run(info, c.update(ext), "update")));
                row.addView(button("Remove", false, v -> run(info, c.uninstall(ext), null)));
            }
        }
        HorizontalScrollView rowScroll = new HorizontalScrollView(this);
        rowScroll.setHorizontalScrollBarEnabled(false);
        rowScroll.addView(row);
        card.addView(rowScroll);
        return card;
    }

    /** Paste an addons.mozilla.org link (or an add-on's name from one) to install it. */
    private void addFromLink() {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("addons.mozilla.org/…/addon/…");
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        android.content.ClipboardManager clipboard = getSystemService(android.content.ClipboardManager.class);
        if (clipboard != null && clipboard.hasPrimaryClip() && clipboard.getPrimaryClip().getItemCount() > 0) {
            CharSequence copied = clipboard.getPrimaryClip().getItemAt(0).getText();
            if (copied != null && Extensions.downloadFor(copied.toString()) != null
                    && copied.toString().contains("addons.mozilla.org")) {
                input.setText(copied); // a link you just copied
            }
        }
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Add from link")
                .setMessage("Paste the link to an add-on's page on addons.mozilla.org.")
                .setView(box)
                .setPositiveButton("Add", (d, w) -> {
                    String url = Extensions.downloadFor(input.getText().toString());
                    if (url == null) {
                        toast("That isn't a link to an add-on.");
                        return;
                    }
                    toast("Getting the add-on…");
                    Extensions.installFromUrl(url).accept(
                            ext -> {
                                toast(ext.metaData.name + " added.");
                                refresh();
                            },
                            e -> toast("The add-on wasn't added. Check the link and your connection."));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void install(Extensions.Info info) {
        run(info, Extensions.install(info), "install");
    }

    private <T> void run(Extensions.Info info, org.mozilla.geckoview.GeckoResult<T> op, String kind) {
        mBusy.add(info.id);
        refresh();
        op.accept(value -> {
            mBusy.remove(info.id);
            if ("update".equals(kind)) {
                toast(value == null ? info.name + " is up to date." : info.name + " updated.");
            } else if ("install".equals(kind)) {
                toast(info.name + " installed.");
            }
            refresh();
        }, e -> {
            mBusy.remove(info.id);
            Log.e(GeckoApp.TAG, info.name + " failed", e);
            toast(info.name + ": something went wrong. Check your connection and try again.");
            refresh();
        });
    }

    private void checkForUpdates() {
        toast("Checking for updates…");
        Extensions.updateAll(this).accept(n -> {
            toast(n == 0 ? "Everything is up to date." : n + (n == 1 ? " extension" : " extensions") + " updated.");
            refresh();
        }, e -> toast("Couldn't check for updates. Check your connection and try again."));
    }

    private void openAutofillSettings() {
        Intent intent = new Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivity(intent);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void openPasskeySettings() {
        try {
            PendingIntent pi = CredentialManager.create(this).createSettingsPendingIntent();
            if (Build.VERSION.SDK_INT >= 34) {
                pi.send(android.app.ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(
                                android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                        .toBundle());
            } else {
                pi.send();
            }
        } catch (Exception e) {
            openAutofillSettings();
        }
    }

    private static final int[] CROSSFADE_CHOICES = {0, 3, 6, 9, 12};

    private static String crossfadeLabel(int seconds) {
        return seconds == 0 ? "Off" : seconds + " seconds";
    }

    /** YouTube Music crossfade length; tapping opens a list of choices. */
    private View crossfadeRow() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(6), 0, dp(6));
        int current = Extensions.prefs(this).getInt(Extensions.PREF_CROSSFADE, Extensions.CROSSFADE_DEFAULT);
        TextView title = new TextView(this);
        title.setText("Crossfade (Music tab): " + crossfadeLabel(current));
        title.setTextColor(TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        box.addView(title);
        TextView t = body("Non-stop playback: the next song starts and fades in while the current one "
                + "fades out, without the usual pause between tracks.");
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        box.addView(t);
        String last = Extensions.prefs(this).getString("status_crossfade", null);
        long contact = Extensions.prefs(this).getLong("helper_contact", 0);
        String text;
        if (last != null) {
            long when = Extensions.prefs(this).getLong("status_time_crossfade", 0);
            text = "Last song change: " + last + " ("
                    + android.text.format.DateUtils.getRelativeTimeSpanString(when) + ")";
        } else if (contact > 0) {
            text = "No song change seen yet. Let a song in the Music tab play to its end. (Page helper "
                    + "connected " + android.text.format.DateUtils.getRelativeTimeSpanString(contact) + ")";
        } else {
            text = "The page helper hasn't reached the app yet: open the Music tab, then check here again.";
        }
        TextView status = body(text);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setTextColor(0xFF3EA6FF);
        box.addView(status);
        box.setOnClickListener(v -> {
            String[] labels = new String[CROSSFADE_CHOICES.length];
            int checked = 0;
            for (int i = 0; i < labels.length; i++) {
                labels[i] = crossfadeLabel(CROSSFADE_CHOICES[i]);
                if (CROSSFADE_CHOICES[i] == current) {
                    checked = i;
                }
            }
            new android.app.AlertDialog.Builder(this, android.app.AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                    .setTitle("Crossfade")
                    .setSingleChoiceItems(labels, checked, (d, which) -> {
                        Extensions.prefs(this).edit().putInt(Extensions.PREF_CROSSFADE, CROSSFADE_CHOICES[which]).apply();
                        d.dismiss();
                        toast("Crossfade: " + labels[which] + ". Applies to songs started after this.");
                        refresh();
                    })
                    .show();
        });
        return box;
    }

    private View toggle(String title, String summary, String pref) {
        return toggle(title, summary, pref, true, null);
    }

    private View toggle(String title, String summary, String pref, boolean defaultOn,
                        java.util.function.Consumer<Boolean> onChange) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(6), 0, dp(6));
        Switch s = new Switch(this);
        s.setText(title);
        s.setTextColor(TEXT);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        s.setChecked(Extensions.prefs(this).getBoolean(pref, defaultOn));
        s.setOnCheckedChangeListener((b, on) -> {
            Extensions.prefs(this).edit().putBoolean(pref, on).apply();
            if (onChange != null) {
                onChange.accept(on);
            }
        });
        box.addView(s);
        TextView t = body(summary);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        box.addView(t);
        return box;
    }

    private TextView heading(String text, int sp) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(sp > 20 ? 4 : 24), 0, dp(4));
        return t;
    }

    private TextView body(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(TEXT_DIM);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        return t;
    }

    private LinearLayout buttonRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, 0);
        return row;
    }

    private Button button(String label, boolean primary, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(primary ? 0xFFFFFFFF : TEXT);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(primary ? ACCENT : 0xFF3A3A3A);
        bg.setCornerRadius(dp(18));
        b.setBackground(bg);
        b.setMinHeight(dp(36));
        b.setMinimumHeight(dp(36));
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(36));
        lp.rightMargin = dp(8);
        b.setLayoutParams(lp);
        b.setOnClickListener(onClick);
        return b;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
