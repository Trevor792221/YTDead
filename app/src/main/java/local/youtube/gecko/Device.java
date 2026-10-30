package local.youtube.gecko;

import android.app.UiModeManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.view.Display;

/** What this device is and can do, for picking settings that keep playback smooth. */
final class Device {
    private static Boolean sAv1Hardware;
    private static Boolean sVp9Hardware;

    private Device() {
    }

    static boolean isTv(Context context) {
        UiModeManager ui = context.getSystemService(UiModeManager.class);
        return (ui != null && ui.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION)
                || context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }

    /** Whether AV1 video can be decoded in hardware (otherwise it would load the processor). */
    static synchronized boolean av1Hardware() {
        if (sAv1Hardware == null) {
            sAv1Hardware = hasHardwareDecoder("video/av01");
        }
        return sAv1Hardware;
    }

    /** Whether VP9 video can be decoded in hardware. */
    static synchronized boolean vp9Hardware() {
        if (sVp9Hardware == null) {
            sVp9Hardware = hasHardwareDecoder("video/x-vnd.on2.vp9");
        }
        return sVp9Hardware;
    }

    private static boolean hasHardwareDecoder(String mime) {
        try {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (info.isEncoder()) {
                    continue;
                }
                for (String type : info.getSupportedTypes()) {
                    if (type.equalsIgnoreCase(mime) && isHardware(info)) {
                        return true;
                    }
                }
            }
        } catch (RuntimeException e) {
            // Unknown: don't restrict anything.
            return true;
        }
        return false;
    }

    private static boolean isHardware(MediaCodecInfo info) {
        if (Build.VERSION.SDK_INT >= 29) {
            return info.isHardwareAccelerated() && !info.isAlias();
        }
        String name = info.getName().toLowerCase(java.util.Locale.ROOT);
        return !(name.startsWith("omx.google.") || name.startsWith("c2.android.")
                || name.startsWith("omx.ffmpeg.") || name.contains(".sw."));
    }

    /**
     * The tallest video worth playing: the screen's short side in real pixels (1080 on a 1080p TV
     * or most phones, 2160 on a 4K TV). Anything higher only costs data and processing.
     */
    static int maxVideoHeight(Context context) {
        DisplayManager dm = context.getSystemService(DisplayManager.class);
        Display display = dm != null ? dm.getDisplay(Display.DEFAULT_DISPLAY) : null;
        if (display == null) {
            return 0; // unknown: no cap
        }
        int max = 0;
        // A TV may support a higher mode than the one it's in; use the best it can show.
        for (Display.Mode mode : display.getSupportedModes()) {
            max = Math.max(max, Math.min(mode.getPhysicalWidth(), mode.getPhysicalHeight()));
        }
        return max;
    }
}
