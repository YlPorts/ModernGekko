package org.moderngekko.android;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class GameActivity extends Activity implements SurfaceHolder.Callback {
    public static final String EXTRA_GAME_ROOT = "game_root";
    public static final String EXTRA_MODULE_PATH = "module_path";

    private final ExecutorService gameThread = Executors.newSingleThreadExecutor();
    private SurfaceView surfaceView;
    private TextView statusView;
    private volatile boolean started;
    private String gameRoot;
    private String modulePath;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        gameRoot = getIntent().getStringExtra(EXTRA_GAME_ROOT);
        if (gameRoot == null)
            gameRoot = new File(getFilesDir(), "games/current").getAbsolutePath();
        modulePath = getIntent().getStringExtra(EXTRA_MODULE_PATH);
        setContentView(createView());
        enterImmersiveMode();
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersiveMode();
        if (started)
            NativeBridge.resumeGame();
    }

    @Override
    protected void onPause() {
        if (started)
            NativeBridge.pauseGame();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        NativeBridge.stopGame();
        gameThread.shutdownNow();
        super.onDestroy();
    }

    private View createView() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        statusView = new TextView(this);
        statusView.setText("Preparando superficie de juego…");
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(15);
        statusView.setGravity(Gravity.CENTER);
        statusView.setBackgroundColor(0x99000000);
        statusView.setPadding(dp(18), dp(12), dp(18), dp(12));
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
        );
        root.addView(statusView, statusParams);

        Button close = new Button(this);
        close.setText("Salir");
        close.setAllCaps(false);
        close.setOnClickListener(view -> finish());
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(
                dp(100),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END
        );
        closeParams.setMargins(dp(10), dp(10), dp(10), dp(10));
        root.addView(close, closeParams);

        return root;
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (started)
            return;
        started = true;
        Surface surface = holder.getSurface();
        statusView.setText("Iniciando ModernGekko…");
        gameThread.execute(() -> {
            String result;
            try {
                if (modulePath == null || modulePath.isEmpty())
                    throw new IllegalStateException("No se recibió el módulo recompilado ARM64");
                result = NativeBridge.runGame(
                        gameRoot,
                        getFilesDir().getAbsolutePath(),
                        surface
                );
            } catch (Throwable error) {
                result = "{\"ok\":false,\"error\":\"" + error.getClass().getSimpleName() +
                        ": " + String.valueOf(error.getMessage()).replace("\"", "'") + "\"}";
            }
            String finalResult = result;
            runOnUiThread(() -> {
                started = false;
                if (NativeBridge.resultIsOk(finalResult)) {
                    statusView.setText("El juego terminó.");
                } else {
                    statusView.setVisibility(View.VISIBLE);
                    statusView.setText("No se pudo iniciar\n\n" + NativeBridge.resultError(finalResult));
                }
            });
        });
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        NativeBridge.updateSurface(holder.getSurface(), width, height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        NativeBridge.clearSurface();
        if (isFinishing())
            NativeBridge.stopGame();
    }

    private void enterImmersiveMode() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                );
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
