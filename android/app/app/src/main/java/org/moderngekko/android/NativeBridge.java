package org.moderngekko.android;

import org.json.JSONObject;

public final class NativeBridge {
    static {
        System.loadLibrary("moderngekko_android");
    }

    private NativeBridge() {
    }

    public static native String nativeVersion();

    public static native String inspectExtractedGame(String gameRoot);

    public static native String inspectDiscImage(String imagePath);

    public static native String extractDiscImage(String imagePath, String outputRoot);

    public static boolean resultIsOk(String result) {
        try {
            return new JSONObject(result).optBoolean("ok", false);
        } catch (Exception ignored) {
            return false;
        }
    }

    public static String resultError(String result) {
        try {
            return new JSONObject(result).optString("error", result);
        } catch (Exception ignored) {
            return result;
        }
    }
}
