package local.youtube.gecko;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Shown after a crash with the details to send to the developer. Runs in its own process
 * (":crash", without GeckoView), so it stays up even if the app goes down again.
 */
public class CrashReportActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String report = CrashLog.read(this);
        if (report == null) {
            finish();
            return;
        }
        setTitle("YTDead closed unexpectedly");

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        list.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("YTDead closed unexpectedly");
        title.setTextColor(0xFFF1F1F1);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        list.addView(title);

        TextView body = new TextView(this);
        body.setText("Sorry about that. Sending these details to the developer helps fix it. They don't "
                + "include anything about you or what you watched. If it happened right after "
                + "opening, the app opens on the YouTube home page next time instead of where you "
                + "left off.");
        body.setTextColor(0xFFAAAAAA);
        body.setPadding(0, pad / 2, 0, pad / 2);
        list.addView(body);

        LinearLayout row = new LinearLayout(this);
        Button share = new Button(this);
        share.setText("Share details");
        share.setOnClickListener(v -> startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "YTDead crash report")
                .putExtra(Intent.EXTRA_TEXT, report), "Share crash details")));
        row.addView(share);
        Button copy = new Button(this);
        copy.setText("Copy");
        copy.setOnClickListener(v -> {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("YTDead crash report", report));
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
            }
        });
        row.addView(copy);
        Button close = new Button(this);
        close.setText("Close");
        close.setOnClickListener(v -> finish());
        row.addView(close);
        list.addView(row);

        TextView details = new TextView(this);
        details.setText(report);
        details.setTextColor(0xFFF1F1F1);
        details.setTypeface(Typeface.MONOSPACE);
        details.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        details.setTextIsSelectable(true);
        details.setPadding(0, pad, 0, 0);
        list.addView(details);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF0F0F0F);
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
        // Shown once.
        CrashLog.clear(this);
    }
}
