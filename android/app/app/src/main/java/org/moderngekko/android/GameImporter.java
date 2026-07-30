package org.moderngekko.android;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Imports a user-provided legal game dump and its matching ARM64 recomp module. */
public final class GameImporter {
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
            try {
                listener.onProgress("Abriendo la imagen del juego…");
                ContentResolver resolver = context.getContentResolver();
                try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(imageUri, "r")) {
                    if (descriptor == null)
                        throw new IOException("Android no pudo abrir la imagen seleccionada");

                    String descriptorPath = "/proc/self/fd/" + descriptor.getFd();
                    String header = NativeBridge.inspectDiscImage(descriptorPath);
                    if (!NativeBridge.resultIsOk(header))
                        throw new IOException(NativeBridge.resultError(header));

                    staging = prepareStagingDirectory();
                    listener.onProgress("Extrayendo la partición del juego; puede tardar varios minutos…");
                    String extraction = NativeBridge.extractDiscImage(
                            descriptorPath,
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
                }
            } catch (Throwable error) {
                if (staging != null)
                    deleteRecursively(staging);
                listener.onError(error.getMessage() == null ? error.toString() : error.getMessage(), error);
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
    }

    public void clearModule(String discId) {
        if (discId != null && !discId.isEmpty())
            deleteRecursively(getModuleFile(discId));
    }

    public void shutdown() {
        executor.shutdownNow();
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
