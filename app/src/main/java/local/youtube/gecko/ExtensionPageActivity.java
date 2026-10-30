package local.youtube.gecko;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.WebResponse;
import org.mozilla.geckoview.GeckoView;

/**
 * Shows an extension's own page, such as SponsorBlock's options or uBlock Origin's dashboard,
 * or (with EXTRA_STORE) addons.mozilla.org, where "Add to Firefox" installs the add-on.
 */
public class ExtensionPageActivity extends Activity {
    static final String EXTRA_URL = "url";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_STORE = "store";

    private GeckoView mView;
    private GeckoSession mSession;
    private boolean mCanGoBack;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String url = getIntent().getStringExtra(EXTRA_URL);
        if (url == null) {
            finish();
            return;
        }
        setTitle(getIntent().getStringExtra(EXTRA_TITLE));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.background));
        mView = new GeckoView(this);
        root.addView(mView);
        setContentView(root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            }
            return insets;
        });

        mSession = new GeckoSession();
        mSession.setPromptDelegate(new Prompts(this));
        boolean store = getIntent().getBooleanExtra(EXTRA_STORE, false);
        mSession.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public void onCanGoBack(GeckoSession session, boolean canGoBack) {
                mCanGoBack = canGoBack;
            }

            @Override
            public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession session, LoadRequest request) {
                if (store && Extensions.isAddonFile(request.uri)) {
                    install(request.uri);
                    return GeckoResult.deny();
                }
                return null;
            }
        });
        if (store) {
            mSession.setContentDelegate(new GeckoSession.ContentDelegate() {
                @Override
                public void onExternalResponse(GeckoSession session, WebResponse response) {
                    // An install file served as a download.
                    String type = null;
                    for (java.util.Map.Entry<String, String> h : response.headers.entrySet()) {
                        if ("content-type".equalsIgnoreCase(h.getKey())) {
                            type = h.getValue();
                        }
                    }
                    if (Extensions.isAddonFile(response.uri)
                            || (type != null && type.startsWith("application/x-xpinstall"))) {
                        install(response.uri);
                    }
                }
            });
        }
        mSession.open(GeckoApp.get().runtime());
        // Not a tab of its own: extensions acting on "the current tab" (uBlock Origin's pop-up,
        // password managers) should act on the YouTube or Music page behind it.
        GeckoApp.get().runtime().getWebExtensionController().setTabActive(mSession, false);
        mView.setSession(mSession);
        mSession.loadUri(url);

        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
    }

    private void install(String url) {
        Toast.makeText(this, "Getting the add-on…", Toast.LENGTH_SHORT).show();
        Extensions.installFromUrl(url).accept(
                ext -> Toast.makeText(this, ext.metaData.name + " added. Find it in App settings.",
                        Toast.LENGTH_LONG).show(),
                e -> Toast.makeText(this, "The add-on wasn't added.", Toast.LENGTH_SHORT).show());
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        handleBack();
    }

    private void handleBack() {
        if (mCanGoBack) {
            mSession.goBack();
        } else {
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        if (mSession != null) {
            mView.releaseSession();
            mSession.close();
        }
        super.onDestroy();
    }
}
