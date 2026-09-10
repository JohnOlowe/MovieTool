package movies.util;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Small I/O helpers used across the tool. Everything is UTF-8 by default and
 * streams are always closed quietly.
 */
public final class IoUtil {

    public static final Charset UTF_8 = StandardCharsets.UTF_8;

    private static final int BUFFER_SIZE = 64 * 1024;

    private IoUtil() {
    }

    public static long copy(File from, File to) throws IOException {
        FileInputStream in = new FileInputStream(from);
        FileOutputStream out = new FileOutputStream(to);
        try {
            return copy(in, out);
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    public static long copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) >= 0) {
            out.write(buffer, 0, read);
            total += read;
        }
        return total;
    }

    /** Reads a whole (small) file as UTF-8 text. */
    public static String readText(File file) throws IOException {
        byte[] bytes = readAll(file);
        return new String(bytes, UTF_8);
    }

    /** Reads a stream to the end, capping at maxBytes. */
    public static byte[] readAll(InputStream in, int maxBytes) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_SIZE];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) >= 0) {
            total += read;
            if (total > maxBytes) throw new IOException("file too large (over " + maxBytes + " bytes)");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /** Writes raw bytes (parent folders are created as needed). */
    public static void writeAll(File file, byte[] bytes) throws IOException {
        mkdirs(file.getParentFile());
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(bytes);
        } finally {
            out.close();
        }
    }

    public static byte[] readAll(File file) throws IOException {
        FileInputStream in = new FileInputStream(file);
        try {
            long length = file.length();
            if (length > Integer.MAX_VALUE - 8) throw new IOException("File too large: " + file);
            byte[] bytes = new byte[(int) length];
            int off = 0;
            int read;
            while (off < bytes.length && (read = in.read(bytes, off, bytes.length - off)) >= 0) {
                off += read;
            }
            if (off < bytes.length) {
                byte[] exact = new byte[off];
                System.arraycopy(bytes, 0, exact, 0, off);
                return exact;
            }
            return bytes;
        } finally {
            closeQuietly(in);
        }
    }

    /** Reads a text file line by line, normalising line breaks. */
    public static List<String> readLines(File file) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), UTF_8));
        try {
            List<String> lines = new ArrayList<String>();
            String line;
            while ((line = reader.readLine()) != null) lines.add(line);
            return lines;
        } finally {
            closeQuietly(reader);
        }
    }

    /** Writes text as UTF-8, creating parent folders when needed. */
    public static void writeText(File file, String text) throws IOException {
        mkdirs(file.getParentFile());
        Writer writer = new OutputStreamWriter(new FileOutputStream(file), UTF_8);
        try {
            writer.write(text);
        } finally {
            closeQuietly(writer);
        }
    }

    public static void mkdirs(File dir) {
        if (dir != null && !dir.exists()) dir.mkdirs();
    }

    public static void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignore) {
            // Closing is not allowed to mask an earlier failure.
        }
    }

    /** Deletes a file or a folder tree; returns false when something survived. */
    public static boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) return true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursively(child)) return false;
                }
            }
        }
        return file.delete();
    }

    /** Case-insensitive check that name ends with the given extension. */
    public static boolean hasExtension(String name, String extension) {
        return name.toLowerCase().endsWith(extension.toLowerCase());
    }

    /** Extension including the dot, lowercase; empty when the name has none. */
    public static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) return "";
        String ext = name.substring(dot).toLowerCase();
        for (int i = 1; i < ext.length(); i++) {
            char c = ext.charAt(i);
            boolean letterOrDigit = Character.isLetterOrDigit(c);
            if (!letterOrDigit) return "";
        }
        return ext;
    }

    /** Name without its extension (the dot is dropped too). */
    public static String baseName(String name) {
        String ext = extensionOf(name);
        return ext.isEmpty() ? name : name.substring(0, name.length() - ext.length());
    }

    /** Reads a reader fully into a string. */
    public static String readFully(Reader reader) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] buffer = new char[BUFFER_SIZE];
        int read;
        while ((read = reader.read(buffer)) >= 0) sb.append(buffer, 0, read);
        return sb.toString();
    }
}
