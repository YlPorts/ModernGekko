package org.moderngekko.android;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class DolphinAssets {
    private static final String VERSION = "sys-1";

    private DolphinAssets() {}

    static File prepare(Context context) throws IOException {
        File root = new File(context.getFilesDir(), "dolphin");
        File sys = new File(root, "Sys");
        File marker = new File(sys, ".version");
        if (marker.isFile() && VERSION.equals(readText(marker)))
            return sys;

        if (!root.exists() && !root.mkdirs())
            throw new IOException("No se pudo crear la carpeta interna de Dolphin");

        File staging = new File(root, "Sys-importing");
        GameImporter.deleteRecursively(staging);
        if (!staging.mkdirs())
            throw new IOException("No se pudo crear la carpeta temporal Sys");

        copyTree(context.getAssets(), "Sys", staging);
        writeText(new File(staging, ".version"), VERSION);
        GameImporter.deleteRecursively(sys);
        if (!staging.renameTo(sys))
            throw new IOException("No se pudo instalar la carpeta Sys");
        return sys;
    }

    private static void copyTree(AssetManager assets, String path, File output) throws IOException {
        String[] children = assets.list(path);
        if (children == null)
            throw new IOException("No se pudo leer assets/" + path);
        if (children.length == 0) {
            copyFile(assets, path, output);
            return;
        }
        if (!output.exists() && !output.mkdirs())
            throw new IOException("No se pudo crear " + output.getName());
        for (String child : children)
            copyTree(assets, path + "/" + child, new File(output, child));
    }

    private static void copyFile(AssetManager assets, String path, File output) throws IOException {
        File parent = output.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs())
            throw new IOException("No se pudo crear " + parent.getName());
        try (InputStream input = assets.open(path);
             FileOutputStream destination = new FileOutputStream(output)) {
            byte[] buffer = new byte[262144];
            int read;
            while ((read = input.read(buffer)) != -1)
                destination.write(buffer, 0, read);
        }
    }

    private static String readText(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            byte[] data = new byte[32];
            int read = input.read(data);
            return read <= 0 ? "" : new String(data, 0, read, StandardCharsets.UTF_8);
        }
    }

    private static void writeText(File file, String text) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
