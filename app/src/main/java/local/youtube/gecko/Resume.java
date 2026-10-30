package local.youtube.gecko;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Where you left off in each tab: the video (or song) and the position, saved by the page
 * (page-control.js) when you pause, every few seconds while playing, and when it closes. The
 * next time the app opens it reopens there, and the page goes to that spot, paused.
 */
final class Resume {
    static final int YOUTUBE = 0;
    static final int MUSIC = 1;

    private static final String KEY_URL = "resume_url_";
    private static final String KEY_VIDEO = "resume_video_";
    private static final String KEY_TIME = "resume_time_";
    private static final String KEY_LAST_TAB = "resume_last_tab";

    /** Set at app start for each tab with a saved spot; the page asks for it once. */
    private static final boolean[] sPending = new boolean[2];

    private Resume() {
    }

    private static SharedPreferences prefs(Context context) {
        return Extensions.prefs(context);
    }

    /** Saves (or, once the video has ended, forgets) where you are in a tab. */
    static void save(Context context, boolean music, String url, String videoId, double time, boolean ended) {
        int tab = music ? MUSIC : YOUTUBE;
        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        boolean ours = "https".equals(uri.getScheme()) && host != null
                && (music ? host.equals("music.youtube.com")
                        : host.equals("m.youtube.com") || host.equals("www.youtube.com") || host.equals("youtube.com"));
        if (!ours) {
            return;
        }
        SharedPreferences.Editor edit = prefs(context).edit();
        if (ended || videoId.isEmpty()) {
            edit.remove(KEY_URL + tab).remove(KEY_VIDEO + tab).remove(KEY_TIME + tab);
        } else {
            edit.putString(KEY_URL + tab, url)
                    .putString(KEY_VIDEO + tab, videoId)
                    .putFloat(KEY_TIME + tab, (float) Math.max(0, time))
                    .putInt(KEY_LAST_TAB, tab);
        }
        edit.apply();
    }

    /** At app start: each tab with a saved spot opens there. */
    static void startPending(Context context) {
        for (int tab = 0; tab < 2; tab++) {
            sPending[tab] = prefs(context).getString(KEY_URL + tab, null) != null;
        }
    }

    /** The page to open in a tab instead of its home page, or null. */
    static String pendingUrl(Context context, int tab) {
        return sPending[tab] ? prefs(context).getString(KEY_URL + tab, null) : null;
    }

    /** The tab you were last watching or listening in. */
    static int lastTab(Context context) {
        return prefs(context).getInt(KEY_LAST_TAB, YOUTUBE) == MUSIC ? MUSIC : YOUTUBE;
    }

    /**
     * For the page: the video and position to go to, as JSON text, once per app start; empty
     * if there's nothing to resume.
     */
    static String take(Context context, boolean music) {
        int tab = music ? MUSIC : YOUTUBE;
        if (!sPending[tab]) {
            return "";
        }
        sPending[tab] = false;
        SharedPreferences p = prefs(context);
        try {
            return new JSONObject()
                    .put("videoId", p.getString(KEY_VIDEO + tab, ""))
                    .put("time", (double) p.getFloat(KEY_TIME + tab, 0))
                    .toString();
        } catch (JSONException e) {
            return "";
        }
    }
}
