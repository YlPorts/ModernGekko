package org.moderngekko.android;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.StatFs;
import android.provider.OpenableColumns;

import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Imports a user-provided legal game dump and its matching ARM64 recomp module. */
public final class GameImporter {
    private static final long COPY_PROGRESS_STEP = 128L * 1024L * 1024L;
    private static final long COPY_SPACE_MARGIN = 128L * 1024L * 1024L;

    public interface Listener {
        void onProgress(String message);
        void onComplete(String nativeResult, File gameRoot);
        void onError(String message, Throwable error);
    }

    public interface ModuleListener {
        void onProgress(String message);
        void onComplete(File moduleFile);
        void onError(String message, Throwable error);
    }

    private static final class SourceInfo {
        final String fileName;
        final long size;

        SourceInfo(String fileName, long size) {
            this.fileName = fileName;
            this.size = size;
        }
    }

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public GameImporter(Context context) {
        this.context = context.getApplicationContext();
    }

    public File getCurrentGameRoot() {
        return new File(context.getFilesDir(), "games/current");
    }

    public File getModuleFile(String discId) {
        return new File(
                new File(context.getFilesDir(), "StaticRecompModules"),
                "g" + discId + "_recomp.so"
        );
    }

    public void importExtractedTree(Uri treeUri, Listener listener) {
        executor.execute(() -> {
            try {
                listener.onProgress("Leyendo la carpeta seleccionada…");
                DocumentFile selected = DocumentFile.fromTreeUri(context, treeUri);
                if (selected == null || !selected.isDirectory())
                    throw new IOException("No se pudo abrir la carpeta seleccionada");

                DocumentFile gameRoot = findGameRoot(selected);
                if (gameRoot == null) {
                    throw new IOException(
                            "La carpeta debe contener sys/main.dol, sys/boot.bin y la carpeta files"
                    );
                }

                File staging = prepareStagingDirectory();
                copyDirectory(gameRoot, staging, listener);

                listener.onProgress("Verificando los archivos con ModernGekko…");
                String inspection = NativeBridge.inspectExtractedGame(staging.getAbsolutePath());
                if (!NativeBridge.resultIsOk(inspection)) {
                    deleteRecursively(staging);
                    throw new IOException(NativeBridge.resultError(inspection));
                }

                File installed = publishStaging(staging);
                listener.onComplete(inspection, installed);
            } catch (Throwable error) {
                listener.onError(error.getMessage() == null ? error.toString() : error.getMessage(), error);
            }
        });
    }

    public void importDiscImage(Uri imageUri, Listener listener) {
        executor.execute(() -> {
            File staging = null;
            File localImage = null;
            try {
                SourceInfo source = querySourceInfo(imageUri);
                validateDiscImageName(source.fileName);

                File importCache = prepareDiscImportCache();
                localImage = new File(importCache, source.fileName);
                ensureCopySpace(importCache, source.size);

                listener.onProgress(
                        source.size > 0
                                ? "Copiando " + source.fileName + " al almacenamiento privado (" +
                                formatSize(source.size) + ")…"
                                : "Copiando " + source.fileName + " al almacenamiento privado…"
                );
                copyDiscImage(imageUri, localImage, source.size, listener);

                listener.onProgress("Comprobando la imagen con ModernGekko…");
                String header = NativeBridge.inspectDiscImage(localImage.getAbsolutePath());
                if (!NativeBridge.resultIsOk(header))
                    throw new IOException(NativeBridge.resultError(header));

                staging = prepareStagingDirectory();
                listener.onProgress("Extrayendo la partición del juego; puede tardar varios minutos…");
                String extraction = NativeBridge.extractDiscImage(
                        localImage.getAbsolutePath(),
                        staging.getAbsolutePath()
                );
                if (!NativeBridge.resultIsOk(extraction))
                    throw new IOException(NativeBridge.resultError(extraction));

                listener.onProgress("Verificando la extracción…");
                String inspection = NativeBridge.inspectExtractedGame(staging.getAbsolutePath());
                if (!NativeBridge.resultIsOk(inspection))
                    throw new IOException(NativeBridge.resultError(inspection));

                File installed = publishStaging(staging);
                staging = null;
                listener.onComplete(inspection, installed);
            } catch (Throwable error) {
                if (staging != null)
                    deleteRecursively(staging);
                listener.onError(error.getMessage() == null ? error.toString() : error.getMessage(), error);
            } finally {
                if (localImage != null)
                    deleteRecursively(localImage);
                cleanupDiscImportCache();
            }
        });
    }

    public void importRecompModule(Uri moduleUri, String discId, ModuleListener listener) {
        executor.execute(() -> {
            File temporary = null;
            try {
                if (discId == null || discId.length() != 6 || !discId.matches("[A-Za-z0-9]{6}"))
                    throw new IOException("Primero importa un juego con un ID válido");

                File modules = new File(context.getFilesDir(), "StaticRecompModules");
                if (!modules.exists() && !modules.mkdirs())
                    throw new IOException("No se pudo crear la carpeta de módulos");

                listener.onProgress("Copiando el módulo recompilado…");
                temporary = new File(modules, "module-importing.so");
                deleteRecursively(temporary);
                copyFile(moduleUri, temporary);

                listener.onProgress("Verificando ELF ARM64…");
                validateArm64Elf(temporary);

                File destination = getModuleFile(discId);
                deleteRecursively(destination);
                if (!temporary.renameTo(destination))
                    throw new IOException("No se pudo instalar el módulo recompilado");
                temporary = null;
                listener.onComplete(destination);
            } catch (Throwable error) {
                if (temporary != null)
                    deleteRecursively(temporary);
                listener.onError(error.getMessage() == null ? error.toString() : error.getMessage(), error);
            }
        });
    }

    public void clearImportedGame() {
        deleteRecursively(getCurrentGameRoot());
        cleanupDiscImportCache();
    }

    public void clearModule(String discId) {
        if (discId != null && !discId.isEmpty())
            deleteRecursively(getModuleFile(discId));
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private SourceInfo querySourceInfo(Uri uri) {
        String displayName = null;
        long size = -1;
        ContentResolver resolver = context.getContentResolver();
        String[] projection = {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        try (Cursor cursor = resolver.query(uri, projection, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameColumn >= 0 && !cursor.isNull(nameColumn))
                    displayName = cursor.getString(nameColumn);
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn))
                    size = cursor.getLong(sizeColumn);
            }
        } catch (RuntimeException ignored) {
            // Some providers do not implement metadata queries. The stream can still be copied.
        }

        if (displayName == null || displayName.trim().isEmpty()) {
            String last = uri.getLastPathSegment();
            displayName = last == null ? "juego.iso" : Uri.decode(last);
        }
        displayName = safeName(displayName.trim());
        if (displayName.isEmpty())
            displayName = "juego.iso";
        return new SourceInfo(displayName, size);
    }

    private static void validateDiscImageName(String fileName) throws IOException {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".zip") || lower.endsWith(".7z") || lower.endsWith(".rar")) {
            throw new IOException(
                    "El archivo está comprimido. Extrae primero la ISO, RVZ o WBFS del ZIP/7Z/RAR"
            );
        }
        if (!lower.endsWith(".iso") && !lower.endsWith(".rvz") &&
                !lower.endsWith(".wbfs") && !lower.endsWith(".gcz") &&
                !lower.endsWith(".wia") && !lower.endsWith(".ciso")) {
            throw new IOException(
                    "Formato no reconocido: " + fileName +
                            ". Selecciona una imagen ISO, RVZ, WBFS, GCZ, WIA o CISO"
            );
        }
    }

    private File prepareDiscImportCache() throws IOException {
        File base = context.getExternalCacheDir();
        if (base == null)
            base = context.getCacheDir();
        File directory = new File(base, "disc-import");
        deleteRecursively(directory);
        if (!directory.mkdirs())
            throw new IOException("No se pudo crear el espacio temporal para la imagen del juego");
        return directory;
    }

    private void cleanupDiscImportCache() {
        File external = context.getExternalCacheDir();
        if (external != null)
            deleteRecursively(new File(external, "disc-import"));
        deleteRecursively(new File(context.getCacheDir(), "disc-import"));
    }

    private static void ensureCopySpace(File directory, long sourceSize) throws IOException {
        if (sourceSize <= 0)
            return;
        long available = new StatFs(directory.getAbsolutePath()).getAvailableBytes();
        long required = sourceSize + COPY_SPACE_MARGIN;
        if (available < required) {
            throw new IOException(
                    "No hay espacio para la copia temporal. Se necesitan aproximadamente " +
                            formatSize(required) + " y hay " + formatSize(available) + " disponibles"
            );
        }
    }

    private void copyDiscImage(Uri source, File destination, long totalSize, Listener listener)
            throws IOException {
        try (InputStream input = context.getContentResolver().openInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            if (input == null)
                throw new IOException("Android no pudo leer la imagen seleccionada");

            byte[] buffer = new byte[4 * 1024 * 1024];
            long copied = 0;
            long nextProgress = COPY_PROGRESS_STEP;
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                copied += read;
                if (copied >= nextProgress) {
                    if (totalSize > 0) {
                        int percent = (int) Math.min(99, (copied * 100L) / totalSize);
                        listener.onProgress(
                                "Copiando imagen… " + percent + "% (" + formatSize(copied) +
                                        " de " + formatSize(totalSize) + ")"
                        );
                    } else {
                        listener.onProgress("Copiando imagen… " + formatSize(copied));
                    }
                    nextProgress = copied + COPY_PROGRESS_STEP;
                }
            }
            output.getFD().sync();
        }

        if (!destination.isFile() || destination.length() < 0x60)
            throw new IOException("La copia temporal quedó vacía o incompleta");
        if (totalSize > 0 && destination.length() != totalSize)
            throw new IOException("La copia temporal no coincide con el tamaño del archivo original");
    }

    private DocumentFile findGameRoot(DocumentFile selected) {
        if (looksLikeGameRoot(selected))
            return selected;

        for (DocumentFile child : selected.listFiles()) {
            if (child.isDirectory() && looksLikeGameRoot(child))
                return child;
        }
        return null;
    }

    private boolean looksLikeGameRoot(DocumentFile directory) {
        DocumentFile sys = directory.findFile("sys");
        DocumentFile files = directory.findFile("files");
        if (sys == null || !sys.isDirectory() || files == null || !files.isDirectory())
            return false;
        DocumentFile dol = sys.findFile("main.dol");
        DocumentFile boot = sys.findFile("boot.bin");
        return dol != null && dol.isFile() && boot != null && boot.isFile();
    }

    private File prepareStagingDirectory() throws IOException {
        File games = new File(context.getFilesDir(), "games");
        if (!games.exists() && !games.mkdirs())
            throw new IOException("No se pudo crear la carpeta interna de juegos");

        File staging = new File(games, "importing");
        deleteRecursively(staging);
        if (!staging.mkdirs())
            throw new IOException("No se pudo crear la carpeta temporal de importación");
        return staging;
    }

    private File publishStaging(File staging) throws IOException {
        File current = getCurrentGameRoot();
        deleteRecursively(current);
        if (!staging.renameTo(current))
            throw new IOException("No se pudo finalizar la importación del juego");
        return current;
    }

    private void copyDirectory(DocumentFile source, File destination, Listener listener)
            throws IOException {
        DocumentFile[] children = source.listFiles();
        for (DocumentFile child : children) {
            String safeName = safeName(child.getName());
            if (safeName.isEmpty())
                continue;
            File output = new File(destination, safeName);
            if (child.isDirectory()) {
                if (!output.exists() && !output.mkdirs())
                    throw new IOException("No se pudo crear " + output.getName());
                copyDirectory(child, output, listener);
            } else if (child.isFile()) {
                listener.onProgress("Copiando " + safeName + "…");
                copyFile(child.getUri(), output);
            }
        }
    }

    private void copyFile(Uri source, File destination) throws IOException {
        try (InputStream input = context.getContentResolver().openInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            if (input == null)
                throw new IOException("No se pudo leer " + source);
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1)
                output.write(buffer, 0, read);
            output.getFD().sync();
        }
    }

    private static void validateArm64Elf(File file) throws IOException {
        byte[] header = new byte[64];
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file))) {
            int offset = 0;
            while (offset < header.length) {
                int read = input.read(header, offset, header.length - offset);
                if (read < 0)
                    break;
                offset += read;
            }
            if (offset < header.length)
                throw new IOException("El archivo es demasiado pequeño para ser una biblioteca ELF");
        }

        if (header[0] != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F')
            throw new IOException("El archivo seleccionado no es una biblioteca ELF");
        if (header[4] != 2)
            throw new IOException("El módulo no es ELF de 64 bits");
        if (header[5] != 1)
            throw new IOException("El módulo no usa el formato little-endian de Android");

        int machine = (header[18] & 0xff) | ((header[19] & 0xff) << 8);
        if (machine != 183)
            throw new IOException("El módulo no fue compilado para ARM64 (AArch64)");
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024L * 1024L)
            return Math.max(0, bytes / 1024L) + " KB";
        if (bytes < 1024L * 1024L * 1024L)
            return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private static String safeName(String value) {
        if (value == null)
            return "";
        return value.replace('/', '_').replace('\\', '_').replace("..", "_");
    }

    static void deleteRecursively(File file) {
        if (file == null || !file.exists())
            return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children)
                    deleteRecursively(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
