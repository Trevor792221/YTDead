package local.youtube.gecko;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Keeps the details of the last crash, Java or GeckoView's own (CrashHandlerService), so the
 * app can show them next time to be sent to the developer, and notices crashes right after
 * starting so the next start can skip what may have caused them (resuming the last video).
 */
final class CrashLog {
    private static final String FILE = "last-crash.txt";
    /** Separate from the app's settings: written from the crash handler's own process too. */
    private static final String PREFS = "crash";
    private static final String PREF_STARTED = "started";
    private static final String PREF_STARTUP_CRASH = "startup_crash";
    /** A crash this soon after starting counts as a crash on start. */
    private static final long STARTUP_MS = 15000;

    private CrashLog() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Records uncaught Java exceptions, then lets Android handle them as usual. */
    static void install(Context context) {
        Context app = context.getApplicationContext();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            try {
                StringWriter trace = new StringWriter();
                e.printStackTrace(new PrintWriter(trace));
                record(app, "Java exception in thread " + thread.getName() + "\n" + trace, true);
            } catch (Throwable ignored) {
                // Never get in the way of the crash itself.
            }
            if (previous != null) {
                previous.uncaughtException(thread, e);
            }
        });
    }

    /** The app's main screen is starting. */
    static void started(Context context) {
        prefs(context).edit().putLong(PREF_STARTED, System.currentTimeMillis()).commit();
    }

    /** Saves a crash report; if the app itself went down soon after starting, flags it. */
    static void record(Context context, String details, boolean appDied) {
        String version;
        try {
            version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            version = "?";
        }
        String report = "YTDead " + version + ", Android " + Build.VERSION.RELEASE + " (" + Build.MANUFACTURER
                + " " + Build.MODEL + ")\n"
                + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + "\n\n" + details;
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), FILE))) {
            out.write(report.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // Nowhere to keep it.
        }
        SharedPreferences prefs = prefs(context);
        if (appDied && System.currentTimeMillis() - prefs.getLong(PREF_STARTED, 0) < STARTUP_MS) {
            prefs.edit().putBoolean(PREF_STARTUP_CRASH, true).commit();
        }
    }

    /** Whether the app crashed right after its last start (asked once). */
    static boolean takeStartupCrash(Context context) {
        SharedPreferences prefs = prefs(context);
        boolean crashed = prefs.getBoolean(PREF_STARTUP_CRASH, false);
        if (crashed) {
            prefs.edit().remove(PREF_STARTUP_CRASH).commit();
        }
        return crashed;
    }

    /** The last crash report, or null. */
    static String read(Context context) {
        File file = new File(context.getFilesDir(), FILE);
        try {
            return file.exists() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        }
    }

    static void clear(Context context) {
        new File(context.getFilesDir(), FILE).delete();
    }
}
