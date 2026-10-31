package local.youtube.gecko;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import org.json.JSONObject;
import org.mozilla.geckoview.GeckoRuntime;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * GeckoView reports its own (native) crashes here, in a process of its own since the app's may
 * be gone. Keeps the crash reason for CrashLog; the dump itself isn't kept or sent anywhere.
 */
public class CrashHandlerService extends Service {
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && GeckoRuntime.ACTION_CRASHED.equals(intent.getAction())) {
            String visibility = intent.getStringExtra(GeckoRuntime.EXTRA_CRASH_PROCESS_VISIBILITY);
            boolean appDied = GeckoRuntime.CRASHED_PROCESS_VISIBILITY_MAIN.equals(visibility);
            StringBuilder details = new StringBuilder("GeckoView crash (")
                    .append(appDied ? "the app itself" : "a page process: " + intent.getStringExtra(
                            GeckoRuntime.EXTRA_CRASH_PROCESS_TYPE) + " " + intent.getStringExtra(
                            GeckoRuntime.EXTRA_CRASH_REMOTE_TYPE))
                    .append(")\n");
            String extrasPath = intent.getStringExtra(GeckoRuntime.EXTRA_EXTRAS_PATH);
            if (extrasPath != null) {
                try {
                    JSONObject extras = new JSONObject(new String(
                            Files.readAllBytes(new File(extrasPath).toPath()), StandardCharsets.UTF_8));
                    for (String key : new String[] {"MozCrashReason", "ProcessType", "JavaStackTrace",
                            "ipc_channel_error", "AbortMessage", "Version", "BuildID"}) {
                        if (extras.has(key)) {
                            details.append(key).append(": ").append(extras.optString(key)).append('\n');
                        }
                    }
                } catch (Exception e) {
                    details.append("(no crash details: ").append(e).append(")\n");
                }
            }
            // A page process crash is recovered from (the page reloads); only the app's own counts.
            if (appDied) {
                CrashLog.record(this, details.toString(), true);
            }
        }
        stopSelf(startId);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
