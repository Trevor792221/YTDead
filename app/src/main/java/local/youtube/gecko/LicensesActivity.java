package local.youtube.gecko;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Open-source licenses: the components built into the app, with their licenses and sources.
 * Started with EXTRA_LICENSE, it shows one license's full text instead.
 */
public class LicensesActivity extends Activity {
    static final String EXTRA_LICENSE = "license";

    private static final int TEXT = 0xFFF1F1F1;
    private static final int TEXT_DIM = 0xFFAAAAAA;
    private static final int CARD = 0xFF212121;

    private static final String MPL = "MPL-2.0.txt";
    private static final String APACHE = "Apache-2.0.txt";
    private static final String GECKO_SOURCE = "https://archive.mozilla.org/pub/firefox/releases/159.0/source/";

    /** One component (or family of components) built into the app. */
    private static final class Component {
        final String name;
        final String detail;
        final String license;
        /** Full text in assets/licenses, or null if only linked. */
        final String licenseFile;
        final String licenseUrl;
        final String sourceUrl;

        Component(String name, String detail, String license, String licenseFile, String licenseUrl,
                String sourceUrl) {
            this.name = name;
            this.detail = detail;
            this.license = license;
            this.licenseFile = licenseFile;
            this.licenseUrl = licenseUrl;
            this.sourceUrl = sourceUrl;
        }
    }

    private static final Component[] COMPONENTS = {
        new Component("GeckoView 159",
                "Mozilla's browser engine (the engine of Firefox for Android). Used unmodified.",
                "Mozilla Public License 2.0", MPL, null, GECKO_SOURCE),
        new Component("AndroidX (Android Jetpack)",
                "Activity, Annotation, AppCompat, Biometric, Collection, Concurrent, Core, Credentials, "
                        + "Fragment, Lifecycle, Media3, SavedState, Startup, Tracing and others, by Google.",
                "Apache License 2.0", APACHE, null, "https://github.com/androidx/androidx"),
        new Component("Guava", "Core Java libraries by Google.",
                "Apache License 2.0", APACHE, null, "https://github.com/google/guava"),
        new Component("Kotlin standard library, kotlinx.coroutines, kotlinx.serialization",
                "By JetBrains, with JetBrains annotations.",
                "Apache License 2.0", APACHE, null, "https://github.com/JetBrains/kotlin"),
        new Component("JSpecify", "Nullness annotations.",
                "Apache License 2.0", APACHE, null, "https://github.com/jspecify/jspecify"),
        new Component("SnakeYAML", "YAML parser, used by GeckoView.",
                "Apache License 2.0", APACHE, null, "https://bitbucket.org/snakeyaml/snakeyaml"),
        new Component("Google Play services (Auth, FIDO, Identity) and Google ID",
                "Passkeys and passwords through your phone's providers. Not open source.",
                "Android Software Development Kit License", null,
                "https://developer.android.com/studio/terms", null),
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(getColor(R.color.background));
        scroll.setFillViewport(true);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        list.setPadding(pad, pad, pad, pad);
        scroll.addView(list);
        setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            }
            return insets;
        });

        String file = getIntent().getStringExtra(EXTRA_LICENSE);
        if (file != null) {
            showLicense(list, file);
        } else {
            showComponents(list);
        }
    }

    private void showComponents(LinearLayout list) {
        setTitle("Open-source licenses");
        list.addView(heading("Open-source licenses", 22));
        list.addView(body("YTDead is built on these components. Each is used under its license; tap "
                + "License for the full text and Source code for where to get it."));

        for (Component c : COMPONENTS) {
            LinearLayout card = card();
            card.addView(title(c.name));
            card.addView(body(c.detail));
            TextView license = body(c.license);
            license.setTextColor(TEXT);
            license.setPadding(0, dp(6), 0, 0);
            card.addView(license);
            LinearLayout row = buttonRow();
            if (c.licenseFile != null) {
                row.addView(button("License", v -> startActivity(new Intent(this, LicensesActivity.class)
                        .putExtra(EXTRA_LICENSE, c.licenseFile))));
            } else if (c.licenseUrl != null) {
                row.addView(button("License", v -> openLink(c.licenseUrl)));
            }
            if (c.sourceUrl != null) {
                row.addView(button("Source code", v -> openLink(c.sourceUrl)));
            }
            if (MPL.equals(c.licenseFile)) {
                // GeckoView's own list of the third-party code inside it.
                row.addView(button("Included components", v -> startActivity(
                        new Intent(this, ExtensionPageActivity.class)
                                .putExtra(ExtensionPageActivity.EXTRA_URL, "about:license")
                                .putExtra(ExtensionPageActivity.EXTRA_TITLE, "GeckoView licenses"))));
            }
            card.addView(scrollable(row));
            list.addView(card);
        }

        list.addView(heading("Extensions", 18));
        list.addView(body("The extensions in Settings aren't part of YTDead: they're downloaded from "
                + "addons.mozilla.org when you install them. Each is under its author's license, shown "
                + "on its add-on page."));
        LinearLayout row = buttonRow();
        for (Extensions.Info info : Extensions.CATALOG) {
            row.addView(button(info.name,
                    v -> openLink("https://addons.mozilla.org/firefox/addon/" + info.slug + "/")));
        }
        list.addView(scrollable(row));
    }

    private void showLicense(LinearLayout list, String file) {
        String text;
        try (InputStream in = getAssets().open("licenses/" + file)) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            finish();
            return;
        }
        String name = file.startsWith("MPL") ? "Mozilla Public License 2.0" : "Apache License 2.0";
        setTitle(name);
        list.addView(heading(name, 22));
        TextView t = body(text);
        t.setTextColor(TEXT);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTextIsSelectable(true);
        list.addView(t);
    }

    private void openLink(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (ActivityNotFoundException e) {
            // No browser: nothing to open it with.
        }
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(12));
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        card.setLayoutParams(lp);
        return card;
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

    private TextView title(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
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

    private View scrollable(LinearLayout row) {
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.addView(row);
        return scroller;
    }

    private Button button(String label, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF3A3A3A);
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
