package org.moderngekko.android;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Arrays;

public final class MainActivity extends Activity {
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createContentView());
        loadNativeBridge();
    }

    private ScrollView createContentView() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Color.rgb(235, 246, 255));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(28), dp(24), dp(28), dp(24));

        TextView title = new TextView(this);
        title.setText("ModernGekko Android");
        title.setTextSize(28);
        title.setTextColor(Color.rgb(25, 40, 65));
        title.setGravity(Gravity.CENTER);
        content.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("Primera prueba ARM64 del port");
        subtitle.setTextSize(17);
        subtitle.setTextColor(Color.DKGRAY);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = matchWrap();
        subtitleParams.topMargin = dp(8);
        content.addView(subtitle, subtitleParams);

        statusView = new TextView(this);
        statusView.setText("Cargando biblioteca nativa…");
        statusView.setTextSize(16);
        statusView.setTextColor(Color.BLACK);
        statusView.setTextIsSelectable(true);
        statusView.setPadding(dp(18), dp(18), dp(18), dp(18));
        statusView.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        statusParams.topMargin = dp(22);
        content.addView(statusView, statusParams);

        Button inspectButton = new Button(this);
        inspectButton.setText("Probar inspector del juego");
        inspectButton.setAllCaps(false);
        inspectButton.setOnClickListener(view -> runInspectionSmokeTest());
        LinearLayout.LayoutParams buttonParams = matchWrap();
        buttonParams.topMargin = dp(18);
        content.addView(inspectButton, buttonParams);

        TextView note = new TextView(this);
        note.setText("Esta APK todavía no inicia Kirby. Esta pantalla confirma que Java puede cargar y llamar el código C++ de ModernGekko en Android.");
        note.setTextSize(14);
        note.setTextColor(Color.DKGRAY);
        note.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams noteParams = matchWrap();
        noteParams.topMargin = dp(16);
        content.addView(note, noteParams);

        scrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        return scrollView;
    }

    private void loadNativeBridge() {
        try {
            String version = NativeBridge.nativeVersion();
            statusView.setText(
                    "✅ Puente JNI cargado correctamente\n\n" +
                    version + "\n" +
                    "ABIs del dispositivo: " + Arrays.toString(Build.SUPPORTED_ABIS) + "\n" +
                    "Carpeta interna: " + getFilesDir().getAbsolutePath()
            );
        } catch (Throwable error) {
            statusView.setText(
                    "❌ No se pudo cargar la biblioteca nativa\n\n" +
                    error.getClass().getSimpleName() + ": " + error.getMessage()
            );
        }
    }

    private void runInspectionSmokeTest() {
        try {
            String result = NativeBridge.inspectExtractedGame(getFilesDir().getAbsolutePath());
            statusView.setText(
                    "✅ La llamada JNI funcionó.\n" +
                    "El error de archivos faltantes es esperado en esta prueba:\n\n" + result
            );
        } catch (Throwable error) {
            statusView.setText(
                    "❌ Falló la llamada al inspector nativo\n\n" +
                    error.getClass().getSimpleName() + ": " + error.getMessage()
            );
        }
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
