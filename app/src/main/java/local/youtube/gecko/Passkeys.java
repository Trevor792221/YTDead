package local.youtube.gecko;

import android.app.Activity;
import android.net.Uri;
import android.os.CancellationSignal;
import android.util.Base64;
import android.util.Log;

import androidx.credentials.CreateCredentialResponse;
import androidx.credentials.CreatePublicKeyCredentialRequest;
import androidx.credentials.CreatePublicKeyCredentialResponse;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.GetPublicKeyCredentialOption;
import androidx.credentials.PublicKeyCredential;
import androidx.credentials.exceptions.CreateCredentialException;
import androidx.credentials.exceptions.GetCredentialException;

import org.json.JSONException;
import org.json.JSONObject;
import org.mozilla.geckoview.GeckoResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Passkeys (WebAuthn) through Android's Credential Manager, acting as a browser: the page's
 * navigator.credentials calls arrive here from the helper extension, and this class builds the
 * clientDataJSON for the page's origin and asks the phone's passkey provider to sign it.
 *
 * GeckoView's own WebAuthn support only works in browsers Google has approved, so it can't be
 * used by this app.
 */
final class Passkeys {
    private Passkeys() {
    }

    static GeckoResult<Object> handle(Activity activity, JSONObject msg) {
        GeckoResult<Object> result = new GeckoResult<>();
        try {
            if (activity == null || activity.isFinishing()) {
                throw new WebAuthnError("NotAllowedError", "The app is not in the foreground.");
            }
            String op = msg.getString("op");
            String origin = msg.getString("origin");
            JSONObject options = msg.getJSONObject("options");
            boolean crossOrigin = msg.optBoolean("crossOrigin", false);

            Uri uri = Uri.parse(origin);
            String host = uri.getHost();
            if (host == null || !("https".equals(uri.getScheme()) || "localhost".equals(host))) {
                throw new WebAuthnError("SecurityError", "Passkeys need a secure (https) page.");
            }

            if ("create".equals(op)) {
                JSONObject rp = options.optJSONObject("rp");
                if (rp == null) {
                    throw new WebAuthnError("TypeError", "Missing rp.");
                }
                rp.put("id", checkRpId(rp.optString("id", host), host));
                String clientData = clientDataJson("webauthn.create", options.getString("challenge"), origin, crossOrigin);
                create(activity, options.toString(), clientData, origin, result);
            } else if ("get".equals(op)) {
                options.put("rpId", checkRpId(options.optString("rpId", host), host));
                String clientData = clientDataJson("webauthn.get", options.getString("challenge"), origin, crossOrigin);
                get(activity, options.toString(), clientData, origin, result);
            } else {
                throw new WebAuthnError("NotSupportedError", "Unknown operation.");
            }
        } catch (WebAuthnError e) {
            result.complete(error(e.name, e.getMessage()));
        } catch (JSONException e) {
            result.complete(error("TypeError", "Invalid passkey request: " + e.getMessage()));
        }
        return result;
    }

    private static void create(Activity activity, String requestJson, String clientData, String origin,
                               GeckoResult<Object> result) {
        CreatePublicKeyCredentialRequest request =
                new CreatePublicKeyCredentialRequest(requestJson, sha256(clientData), false, origin);
        CredentialManager.create(activity).createCredentialAsync(activity, request, new CancellationSignal(),
                activity.getMainExecutor(),
                new CredentialManagerCallback<CreateCredentialResponse, CreateCredentialException>() {
                    @Override
                    public void onResult(CreateCredentialResponse response) {
                        if (response instanceof CreatePublicKeyCredentialResponse) {
                            result.complete(success(
                                    ((CreatePublicKeyCredentialResponse) response).getRegistrationResponseJson(),
                                    clientData));
                        } else {
                            result.complete(error("NotAllowedError", "Unexpected response."));
                        }
                    }

                    @Override
                    public void onError(CreateCredentialException e) {
                        Log.w(GeckoApp.TAG, "Passkey create failed: " + e.getType(), e);
                        result.complete(error(errorName(e.getType()), String.valueOf(e.getMessage())));
                    }
                });
    }

    private static void get(Activity activity, String requestJson, String clientData, String origin,
                            GeckoResult<Object> result) {
        GetCredentialRequest request = new GetCredentialRequest.Builder()
                .addCredentialOption(new GetPublicKeyCredentialOption(requestJson, sha256(clientData)))
                .setOrigin(origin)
                .build();
        CredentialManager.create(activity).getCredentialAsync(activity, request, new CancellationSignal(),
                activity.getMainExecutor(),
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse response) {
                        Credential credential = response.getCredential();
                        if (credential instanceof PublicKeyCredential) {
                            result.complete(success(
                                    ((PublicKeyCredential) credential).getAuthenticationResponseJson(), clientData));
                        } else {
                            result.complete(error("NotAllowedError", "Unexpected credential type."));
                        }
                    }

                    @Override
                    public void onError(GetCredentialException e) {
                        Log.w(GeckoApp.TAG, "Passkey get failed: " + e.getType(), e);
                        result.complete(error(errorName(e.getType()), String.valueOf(e.getMessage())));
                    }
                });
    }

    /** The relying party ID must be the page's host or a parent domain of it. */
    private static String checkRpId(String rpId, String host) throws WebAuthnError {
        if (rpId.isEmpty()) {
            return host;
        }
        if (host.equals(rpId) || (host.endsWith("." + rpId) && rpId.contains("."))) {
            return rpId;
        }
        throw new WebAuthnError("SecurityError", "The relying party ID is not valid for this page.");
    }

    private static String clientDataJson(String type, String challenge, String origin, boolean crossOrigin) {
        return "{\"type\":" + JSONObject.quote(type)
                + ",\"challenge\":" + JSONObject.quote(challenge)
                + ",\"origin\":" + JSONObject.quote(origin)
                + ",\"crossOrigin\":" + crossOrigin + "}";
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The provider signed our clientDataJSON's hash, so the page gets our clientDataJSON. */
    private static JSONObject success(String responseJson, String clientData) {
        try {
            JSONObject credential = new JSONObject(responseJson);
            credential.getJSONObject("response").put("clientDataJSON", Base64.encodeToString(
                    clientData.getBytes(StandardCharsets.UTF_8),
                    Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP));
            return new JSONObject().put("ok", true).put("credential", credential);
        } catch (JSONException e) {
            return error("UnknownError", "Invalid response from the passkey provider.");
        }
    }

    private static JSONObject error(String name, String message) {
        try {
            return new JSONObject().put("ok", false).put("name", name).put("message", message);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String errorName(String type) {
        if (type != null && type.contains("InvalidStateError")) {
            return "InvalidStateError";
        }
        if (type != null && type.contains("NotSupported")) {
            return "NotSupportedError";
        }
        return "NotAllowedError";
    }

    private static final class WebAuthnError extends Exception {
        final String name;

        WebAuthnError(String name, String message) {
            super(message);
            this.name = name;
        }
    }
}
