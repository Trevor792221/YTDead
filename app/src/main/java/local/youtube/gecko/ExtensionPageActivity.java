package local.youtube.gecko;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.window.OnBackInvokedDispatcher;

import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

/** Shows an extension's own page, such as SponsorBlock's options or uBlock Origin's dashboard. */
public class ExtensionPageActivity extends Activity {
    static final String EXTRA_URL = "url";
    static final String EXTRA_TITLE = "title";

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
        mSession.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public void onCanGoBack(GeckoSession session, boolean canGoBack) {
                mCanGoBack = canGoBack;
            }
        });
        mSession.open(GeckoApp.get().runtime());
        // Not a tab of its own: extensions acting on "the current tab" (uBlock Origin's pop-up,
        // Bitwarden) should act on the YouTube or Music page behind it.
        GeckoApp.get().runtime().getWebExtensionController().setTabActive(mSession, false);
        mView.setSession(mSession);
        mSession.loadUri(url);

        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
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
