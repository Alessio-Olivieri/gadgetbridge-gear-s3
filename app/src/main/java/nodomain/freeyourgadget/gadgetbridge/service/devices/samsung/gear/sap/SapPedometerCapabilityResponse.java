package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import androidx.core.content.pm.PackageInfoCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/** Samsung Health capability JSON builders for the bounded API999 diagnostic. */
public final class SapPedometerCapabilityResponse {
    public static final String SAMSUNG_HEALTH_PACKAGE = "com.sec.android.app.shealth";
    public static final String REQUEST = "com.samsung.shealth.REQUEST_CAPABILITY";
    public static final String RESPONSE = "com.samsung.shealth.RESPONSE_CAPABILITY";
    public static final String RICH_REQUEST = "com.samsung.wearable.app.shealth.REQUEST_CAPABILITY";
    public static final String RICH_RESPONSE = "com.samsung.android.app.shealth.RESPONSE_CAPABILITY";

    private SapPedometerCapabilityResponse() { }

    public static byte[] managerResponseJson(final Context context) {
        try {
            final long healthVersion = samsungHealthVersionCode(context);
            final JSONObject response = new JSONObject();
            response.put("capability_type", RESPONSE);
            response.put("gear_plugin_version", 0);
            response.put("health_version", healthVersion);
            response.put("os", "android");
            response.put("receiver", "wearable");
            response.put("sender", "wearable_manager");
            response.put("status", healthVersion > 0 ? "installed" : "uninstalled");
            // Samsung's three true message_feature claims are not Gadgetbridge capabilities.
            return response.toString().getBytes(StandardCharsets.UTF_8);
        } catch (final JSONException e) {
            throw new IllegalStateException("Unable to encode health manager capability response", e);
        }
    }

    /**
     * Synthetic diagnostic only. The watch-side parser accepts either modern
     * discriminator, but the exact watch serializer emits both; retain both
     * here while deliberately omitting optional sync/capability claims.
     */
    public static byte[] syntheticRichResponseJson(final Context context) {
        try {
            final JSONObject config = new JSONObject();
            config.put("os", "android");
            final JSONObject response = new JSONObject();
            response.put("capability_type", RESPONSE);
            response.put("message_type", RICH_RESPONSE);
            response.put("sender", "shealth");
            response.put("receiver", "wearable");
            response.put("shealth_version", samsungHealthVersionCode(context));
            response.put("protocol_version", 4.51);
            response.put("config", config);
            return response.toString().getBytes(StandardCharsets.UTF_8);
        } catch (final JSONException e) {
            throw new IllegalStateException("Unable to encode synthetic rich health capability response", e);
        }
    }

    public static boolean isRichCapabilityRequest(final SapPedometerJsonProbe.Result result) {
        return result != null
                && RICH_REQUEST.equals(stringField(result, "message_type"))
                && REQUEST.equals(stringField(result, "capability_type"))
                && "wearable".equals(stringField(result, "sender"))
                && "shealth".equals(stringField(result, "receiver"));
    }

    private static String stringField(final SapPedometerJsonProbe.Result result, final String name) {
        final Object value = result.getTopLevel().get(name);
        return value == null || value == JSONObject.NULL ? null : String.valueOf(value);
    }

    static long samsungHealthVersionCode(final Context context) {
        try {
            final PackageInfo packageInfo = context.getPackageManager().getPackageInfo(SAMSUNG_HEALTH_PACKAGE, 0);
            final long versionCode = PackageInfoCompat.getLongVersionCode(packageInfo);
            return versionCode > 0 ? versionCode : -1;
        } catch (final PackageManager.NameNotFoundException e) {
            return -1;
        }
    }
}
