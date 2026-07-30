package org.moderngekko.android;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.util.Arrays;

public final class MainActivity extends Activity {
    private static final int REQUEST_DISC_IMAGE = 1001;
    private static final int REQUEST_EXTRACTED_FOLDER = 1002;

    private TextView statusView;
    private ProgressBar progressBar;
    private Button importImageButton;
    private Button importFolderButton;
    private Button playButton;
    private Button clearButton;
    private GameImporter importer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        importer = new GameImporter(this);
        setContentView(createContentView());
        loadNativeBridge();
        inspectExistingImport();
    }

    @Override
    protected void onDestroy() {
        importer.shutdown();
        super.onDestroy();
    }

    private ScrollView createContentView() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Color.rgb(235, 246, 255));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(28), dp(20), dp(28), dp(24));

        TextView title = new TextView(this);
        title.setText("ModernGekko Android");
        title.setTextSize(28);
        title.setTextColor(Color.rgb(25, 40, 65));
        title.setGravity(Gravity.CENTER);
        content.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("Importador de Kirby Wii · ARM64");
        subtitle.setTextSize(17);
        subtitle.setTextColor(Color.DKGRAY);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = matchWrap();
        subtitleParams.topMargin = dp(6);
        content.addView(subtitle, subtitleParams);

        statusView = new TextView(this);
        statusView.setText("Cargando biblioteca nativa…");
        statusView.setTextSize(15);
        statusView.setTextColor(Color.BLACK);
        statusView.setTextIsSelectable(true);
        statusView.setPadding(dp(18), dp(16), dp(18), dp(16));
        statusView.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.topMargin = dp(18);
        content.addView(statusView, statusParams);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = matchWrap();
        progressParams.topMargin = dp(10);
        content.addView(progressBar, progressParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);

        importImageButton = makeButton("Importar ISO / RVZ / WBFS");
        importImageButton.setOnClickListener(view -> selectDiscImage());
        actions.addView(importImageButton, weightedButton());

        importFolderButton = makeButton("Importar carpeta extraída");
        importFolderButton.setOnClickListener(view -> selectExtractedFolder());
        actions.addView(importFolderButton, weightedButton());

        LinearLayout.LayoutParams actionsParams = matchWrap();
        actionsParams.topMargin = dp(14);
        content.addView(actions, actionsParams);

        playButton = makeButton("Iniciar Kirby");
        playButton.setEnabled(false);
        playButton.setOnClickListener(view -> launchGame());
        LinearLayout.LayoutParams playParams = matchWrap();
        playParams.topMargin = dp(10);
        content.addView(playButton, playParams);

        clearButton = makeButton("Borrar juego importado");
        clearButton.setEnabled(false);
        clearButton.setOnClickListener(view -> clearImport());
        LinearLayout.LayoutParams clearParams = matchWrap();
        clearParams.topMargin = dp(6);
        content.addView(clearButton, clearParams);

        TextView note = new TextView(this);
        note.setText(
                "Usa tu propia copia legal. La imagen o carpeta se procesa dentro del teléfono y " +
                "no se sube a GitHub. La extracción puede ocupar varios GB."
        );
        note.setTextSize(13);
        note.setTextColor(Color.DKGRAY);
        note.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams noteParams = matchWrap();
        noteParams.topMargin = dp(12);
        content.addView(note, noteParams);

        scrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return scrollView;
    }

    private void loadNativeBridge() {
        try {
            String version = NativeBridge.nativeVersion();
            statusView.setText(
                    "✅ Motor nativo cargado\n" + version + "\n" +
                    "ABIs: " + Arrays.toString(Build.SUPPORTED_ABIS)
            );
        } catch (Throwable error) {
            statusView.setText("❌ No se pudo cargar C++\n" + describe(error));
            setActionsEnabled(false);
        }
    }

    private void inspectExistingImport() {
        File root = importer.getCurrentGameRoot();
        if (!root.isDirectory())
            return;
        setBusy(true, "Verificando el juego guardado…");
        new Thread(() -> {
            try {
                String result = NativeBridge.inspectExtractedGame(root.getAbsolutePath());
                runOnUiThread(() -> showInspection(result, root));
            } catch (Throwable error) {
                runOnUiThread(() -> finishWithError("No se pudo verificar el juego guardado", error));
            }
        }, "ModernGekkoExistingGame").start();
    }

    private void selectDiscImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/octet-stream",
                "application/x-iso9660-image",
                "application/x-gamecube-rom",
                "application/x-wii-rom"
        });
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_DISC_IMAGE);
    }

    private void selectExtractedFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION |
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        );
        startActivityForResult(intent, REQUEST_EXTRACTED_FOLDER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null)
            return;

        Uri uri = data.getData();
        persistPermission(uri, data.getFlags());
        if (requestCode == REQUEST_DISC_IMAGE)
            importer.importDiscImage(uri, importListener());
        else if (requestCode == REQUEST_EXTRACTED_FOLDER)
            importer.importExtractedTree(uri, importListener());
    }

    private GameImporter.Listener importListener() {
        setBusy(true, "Preparando importación…");
        return new GameImporter.Listener() {
            @Override
            public void onProgress(String message) {
                runOnUiThread(() -> setBusy(true, message));
            }

            @Override
            public void onComplete(String nativeResult, File gameRoot) {
                runOnUiThread(() -> showInspection(nativeResult, gameRoot));
            }

            @Override
            public void onError(String message, Throwable error) {
                runOnUiThread(() -> finishWithError(message, error));
            }
        };
    }

    private void showInspection(String result, File gameRoot) {
        setBusy(false, null);
        try {
            JSONObject json = new JSONObject(result);
            if (!json.optBoolean("ok", false)) {
                statusView.setText("❌ Juego inválido\n\n" + json.optString("error", result));
                playButton.setEnabled(false);
                clearButton.setEnabled(gameRoot.exists());
                return;
            }

            statusView.setText(
                    "✅ Juego preparado\n\n" +
                    json.optString("gameName") + "\n" +
                    "ID: " + json.optString("discId") + "\n" +
                    "Plataforma: " + json.optString("platform") + "\n" +
                    "DOL: " + shortHash(json.optString("dolSha256")) + "\n" +
                    "Ruta: " + gameRoot.getAbsolutePath()
            );
            playButton.setEnabled(true);
            clearButton.setEnabled(true);
        } catch (Exception error) {
            finishWithError("El motor devolvió una respuesta inválida", error);
        }
    }

    private void launchGame() {
        Intent intent = new Intent(this, GameActivity.class);
        intent.putExtra(GameActivity.EXTRA_GAME_ROOT, importer.getCurrentGameRoot().getAbsolutePath());
        startActivity(intent);
    }

    private void clearImport() {
        importer.clearImportedGame();
        playButton.setEnabled(false);
        clearButton.setEnabled(false);
        statusView.setText("Juego importado eliminado. Puedes seleccionar otra copia.");
    }

    private void setBusy(boolean busy, String message) {
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        importImageButton.setEnabled(!busy);
        importFolderButton.setEnabled(!busy);
        playButton.setEnabled(!busy && importer.getCurrentGameRoot().isDirectory());
        clearButton.setEnabled(!busy && importer.getCurrentGameRoot().exists());
        if (message != null)
            statusView.setText(message);
    }

    private void finishWithError(String message, Throwable error) {
        setBusy(false, null);
        statusView.setText("❌ " + message + "\n\n" + describe(error));
    }

    private void persistPermission(Uri uri, int resultFlags) {
        int flags = resultFlags & (
                Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        );
        try {
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (SecurityException ignored) {
            // Some document providers grant access only for this activity session.
        }
    }

    private void setActionsEnabled(boolean enabled) {
        importImageButton.setEnabled(enabled);
        importFolderButton.setEnabled(enabled);
        playButton.setEnabled(false);
        clearButton.setEnabled(false);
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        return button;
    }

    private LinearLayout.LayoutParams weightedButton() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMarginStart(dp(4));
        params.setMarginEnd(dp(4));
        return params;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private String shortHash(String hash) {
        return hash.length() > 16 ? hash.substring(0, 16) + "…" : hash;
    }

    private String describe(Throwable error) {
        if (error == null)
            return "Error desconocido";
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
