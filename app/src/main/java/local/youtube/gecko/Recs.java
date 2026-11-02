package local.youtube.gecko;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Your YouTube Music home recommendations (Quick picks, Listen again, Mixed for you, ...), for
 * choosing music in Android Auto. Fetched by the Music tab's page (music-recs.js) and kept here,
 * so the car has them even before the page has loaded again.
 */
final class Recs {
    private static final String KEY_DATA = "music_recs";
    private static final String KEY_TIME = "music_recs_time";
    private static final long FRESH_MS = 30 * 60 * 1000;

    static final class Item {
        final String title;
        final String subtitle;
        final String thumb;
        final String url;

        Item(String title, String subtitle, String thumb, String url) {
            this.title = title;
            this.subtitle = subtitle;
            this.thumb = thumb;
            this.url = url;
        }
    }

    static final class Shelf {
        final String title;
        final List<Item> items = new ArrayList<>();

        Shelf(String title) {
            this.title = title;
        }
    }

    private Recs() {
    }

    private static SharedPreferences prefs(Context context) {
        return Extensions.prefs(context);
    }

    /** New recommendations from the page; kept only what's valid. */
    static void save(Context context, String json) {
        List<Shelf> shelves = parse(json);
        if (!shelves.isEmpty()) {
            prefs(context).edit().putString(KEY_DATA, json).putLong(KEY_TIME, System.currentTimeMillis()).apply();
        }
    }

    /** Whether the page should fetch them again. */
    static boolean stale(Context context) {
        return System.currentTimeMillis() - prefs(context).getLong(KEY_TIME, 0) > FRESH_MS;
    }

    static List<Shelf> load(Context context) {
        return parse(prefs(context).getString(KEY_DATA, null));
    }

    private static List<Shelf> parse(String json) {
        List<Shelf> shelves = new ArrayList<>();
        if (json == null) {
            return shelves;
        }
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length() && i < 8; i++) {
                JSONObject s = array.getJSONObject(i);
                Shelf shelf = new Shelf(s.optString("title"));
                JSONArray items = s.optJSONArray("items");
                for (int j = 0; items != null && j < items.length() && j < 20; j++) {
                    JSONObject it = items.getJSONObject(j);
                    String url = playUrl(it.optString("url"));
                    String thumb = it.optString("thumb");
                    if (url != null && !it.optString("title").isEmpty()) {
                        shelf.items.add(new Item(it.optString("title"), it.optString("subtitle"),
                                thumb.startsWith("https://") ? thumb : null, url));
                    }
                }
                if (!shelf.title.isEmpty() && !shelf.items.isEmpty()) {
                    shelves.add(shelf);
                }
            }
        } catch (JSONException e) {
            shelves.clear();
        }
        return shelves;
    }

    /** A YouTube Music watch page for a song or playlist, or null for anything else. */
    private static String playUrl(String path) {
        if (!path.startsWith("/watch?")) {
            return null;
        }
        Uri uri = Uri.parse("https://music.youtube.com" + path);
        String v = uri.getQueryParameter("v");
        String list = uri.getQueryParameter("list");
        boolean ok = (v != null && v.matches("[\\w-]{6,20}")) || (list != null && list.matches("[\\w-]{6,80}"));
        return ok ? uri.toString() : null;
    }
}
