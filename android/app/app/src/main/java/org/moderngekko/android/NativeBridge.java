package org.moderngekko.android;

import android.view.Surface;

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

    public static native String runGame(
            String gameRoot,
            String userDirectory,
            String modulePath,
            String sysDirectory,
            Surface surface
    );

    public static native void updateSurface(Surface surface, int width, int height);

    public static native void clearSurface();

    public static native void pauseGame();

    public static native void resumeGame();

    public static native void stopGame();

    public static boolean resultIsOk(String result) {
        try {
            return new JSONObject(result).optBoolean("ok", false);
        } catch (Exception ignored) {
            return false;
        }
    }

    public static String resultError(String result) {
        String message;
        try {
            message = new JSONObject(result).optString("error", result);
        } catch (Exception ignored) {
            message = result;
        }
        return makeUserFacing(message);
    }

    private static String makeUserFacing(String message) {
        if (message == null || message.isEmpty())
            return "Error desconocido del motor";
        if (message.equals("Dolphin no pudo abrir la imagen ISO/RVZ/WBFS") ||
                message.equals("Dolphin no pudo abrir la imagen. Comprueba que no esté dañada")) {
            return "ModernGekko no pudo leer la imagen. Comprueba que sea ISO, RVZ o WBFS, " +
                    "que no esté comprimida y que la copia esté completa";
        }
        return message
                .replace("Dolphin rechazó", "El motor rechazó")
                .replace("Dolphin no pudo", "ModernGekko no pudo")
                .replace("Dolphin", "el motor");
    }
}
