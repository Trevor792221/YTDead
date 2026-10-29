package local.youtube.gecko;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSession.PromptDelegate;

import java.util.ArrayList;
import java.util.List;

/** Native dialogs for alert(), confirm(), prompt(), <select>, HTTP sign-in, pop-ups and sharing. */
class Prompts implements PromptDelegate {
    private final Activity mActivity;

    Prompts(Activity activity) {
        mActivity = activity;
    }

    private AlertDialog.Builder dialog(String title, String message) {
        AlertDialog.Builder b = new AlertDialog.Builder(mActivity, AlertDialog.THEME_DEVICE_DEFAULT_DARK);
        if (title != null && !title.isEmpty()) {
            b.setTitle(title);
        }
        if (message != null && !message.isEmpty()) {
            b.setMessage(message);
        }
        return b;
    }

    private boolean gone() {
        return mActivity.isFinishing() || mActivity.isDestroyed();
    }

    @Override
    public GeckoResult<PromptResponse> onAlertPrompt(GeckoSession session, AlertPrompt prompt) {
        if (gone()) {
            return GeckoResult.fromValue(prompt.dismiss());
        }
        GeckoResult<PromptResponse> res = new GeckoResult<>();
        dialog(prompt.title, prompt.message)
                .setPositiveButton(android.R.string.ok, null)
                .setOnDismissListener(d -> res.complete(prompt.dismiss()))
                .show();
        return res;
    }

    @Override
    public GeckoResult<PromptResponse> onButtonPrompt(GeckoSession session, ButtonPrompt prompt) {
        if (gone()) {
            return GeckoResult.fromValue(prompt.dismiss());
        }
        GeckoResult<PromptResponse> res = new GeckoResult<>();
        dialog(prompt.title, prompt.message)
                .setPositiveButton(android.R.string.ok,
                        (d, w) -> res.complete(prompt.confirm(ButtonPrompt.Type.POSITIVE)))
                .setNegativeButton(android.R.string.cancel,
                        (d, w) -> res.complete(prompt.confirm(ButtonPrompt.Type.NEGATIVE)))
                .setOnCancelListener(d -> res.complete(prompt.dismiss()))
                .show();
        return res;
    }

    @Override
    public GeckoResult<PromptResponse> onTextPrompt(GeckoSession session, TextPrompt prompt) {
        if (gone()) {
            return GeckoResult.fromValue(prompt.dismiss());
        }
        GeckoResult<PromptResponse> res = new GeckoResult<>();
        EditText input = new EditText(mActivity);
        input.setText(prompt.defaultValue);
        dialog(prompt.title, prompt.message)
                .setView(padded(input))
                .setPositiveButton(android.R.string.ok,
                        (d, w) -> res.complete(prompt.confirm(input.getText().toString())))
                .setNegativeButton(android.R.string.cancel, (d, w) -> res.complete(prompt.dismiss()))
                .setOnCancelListener(d -> res.complete(prompt.dismiss()))
                .show();
        return res;
    }

    @Override
    public GeckoResult<PromptResponse> onAuthPrompt(GeckoSession session, AuthPrompt prompt) {
        if (gone()) {
            return GeckoResult.fromValue(prompt.dismiss());
        }
        GeckoResult<PromptResponse> res = new GeckoResult<>();
        boolean passwordOnly = (prompt.authOptions.flags & AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD) != 0;
        LinearLayout box = new LinearLayout(mActivity);
        box.setOrientation(LinearLayout.VERTICAL);
        EditText user = new EditText(mActivity);
        user.setHint("Username");
        user.setText(prompt.authOptions.username);
        user.setAutofillHints(android.view.View.AUTOFILL_HINT_USERNAME);
        EditText pass = new EditText(mActivity);
        pass.setHint("Password");
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pass.setAutofillHints(android.view.View.AUTOFILL_HINT_PASSWORD);
        if (!passwordOnly) {
            box.addView(user);
        }
        box.addView(pass);
        dialog(prompt.title, prompt.message)
                .setView(padded(box))
                .setPositiveButton(android.R.string.ok, (d, w) -> res.complete(passwordOnly
                        ? prompt.confirm(pass.getText().toString())
                        : prompt.confirm(user.getText().toString(), pass.getText().toString())))
                .setNegativeButton(android.R.string.cancel, (d, w) -> res.complete(prompt.dismiss()))
                .setOnCancelListener(d -> res.complete(prompt.dismiss()))
                .show();
        return res;
    }

    @Override
    public GeckoResult<PromptResponse> onChoicePrompt(GeckoSession session, ChoicePrompt prompt) {
        if (gone()) {
            return GeckoResult.fromValue(prompt.dismiss());
        }
        List<ChoicePrompt.Choice> choices = new ArrayList<>();
        flatten(prompt.choices, choices);
        String[] labels = new String[choices.size()];
        boolean[] checked = new boolean[choices.size()];
        int selected = -1;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = choices.get(i).label;
            checked[i] = choices.get(i).selected;
            if (checked[i] && selected < 0) {
                selected = i;
            }
        }
        GeckoResult<PromptResponse> res = new GeckoResult<>();
        AlertDialog.Builder b = dialog(prompt.title, null)
                .setOnCancelListener(d -> res.complete(prompt.dismiss()));
        if (prompt.type == ChoicePrompt.Type.MULTIPLE) {
            b.setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        List<ChoicePrompt.Choice> picked = new ArrayList<>();
                        for (int i = 0; i < checked.length; i++) {
                            if (checked[i]) {
                                picked.add(choices.get(i));
                            }
                        }
                        res.complete(prompt.confirm(picked.toArray(new ChoicePrompt.Choice[0])));
                    })
                    .setNegativeButton(android.R.string.cancel, (d, w) -> res.complete(prompt.dismiss()));
        } else {
            b.setSingleChoiceItems(labels, selected, (d, which) -> {
                res.complete(prompt.confirm(choices.get(which)));
                d.dismiss();
            });
        }
        b.show();
        return res;
    }

    private static void flatten(ChoicePrompt.Choice[] in, List<ChoicePrompt.Choice> out) {
        if (in == null) {
            return;
        }
        for (ChoicePrompt.Choice c : in) {
            if (c.items != null) {
                flatten(c.items, out);
            } else if (!c.separator && !c.disabled) {
                out.add(c);
            }
        }
    }

    @Override
    public GeckoResult<PromptResponse> onPopupPrompt(GeckoSession session, PopupPrompt prompt) {
        // Sign-in flows open pop-ups; onNewSession then loads them in the same view.
        return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW));
    }

    @Override
    public GeckoResult<PromptResponse> onBeforeUnloadPrompt(GeckoSession session, BeforeUnloadPrompt prompt) {
        return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW));
    }

    @Override
    public GeckoResult<PromptResponse> onRepostConfirmPrompt(GeckoSession session, RepostConfirmPrompt prompt) {
        return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW));
    }

    @Override
    public GeckoResult<PromptResponse> onSharePrompt(GeckoSession session, SharePrompt prompt) {
        String text = prompt.uri != null ? prompt.uri : prompt.text;
        if (text == null || gone()) {
            return GeckoResult.fromValue(prompt.dismiss());
        }
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text);
        if (prompt.title != null) {
            send.putExtra(Intent.EXTRA_SUBJECT, prompt.title);
        }
        mActivity.startActivity(Intent.createChooser(send, prompt.title));
        return GeckoResult.fromValue(prompt.confirm(SharePrompt.Result.SUCCESS));
    }

    private android.view.View padded(android.view.View v) {
        int pad = Math.round(20 * mActivity.getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(mActivity);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(v, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return box;
    }
}
