package com.nemu.updateguard;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Read-only view of LSPosed's module state. The hook report tells us about the
 * current boot; this database probe additionally tells us when the user turns
 * the module OFF in the manager, which cannot be inferred from a past report
 * alone. Root is required only for this probe; without root the app falls back
 * to the persisted hook report.
 */
final class LsposedDb {
    private static final String TAG = "UpdateGuard";
    private static final String SRC_DB =
            "/data/adb/lspd/config/modules_config.db";
    private static final String MODULE = "com.nemu.windstop";

    private LsposedDb() {
    }

    /** @return TRUE/FALSE when the manager state was read, null otherwise. */
    static Boolean queryEnabled(Context context) {
        File dir = new File(context.getCacheDir(), "lspd_probe");
        try {
            deleteTree(dir);
            if (!dir.mkdirs() && !dir.isDirectory()) {
                Log.w(TAG, "lspd probe: cannot create cache dir");
                return null;
            }
            File db = new File(dir, "modules_config.db");
            File wal = new File(dir, "modules_config.db-wal");
            if (!copyAsRoot(SRC_DB, db, true)) {
                Log.w(TAG, "lspd probe: main db copy failed");
                return null;
            }
            // The -shm file is a transient WAL index, not a persistent part of
            // the database. Copying it can make SQLite trust an index that no
            // longer matches the copied WAL; without it SQLite rebuilds the
            // index from the WAL, so only the db and wal are copied.
            copyAsRoot(SRC_DB + "-wal", wal, false);

            SQLiteDatabase sql = SQLiteDatabase.openDatabase(
                    db.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
            try {
                Cursor c = sql.rawQuery(
                        "SELECT enabled FROM modules_state "
                                + "WHERE module_pkg_name=? AND user_id=0",
                        new String[]{MODULE});
                try {
                    if (c.moveToFirst()) {
                        return c.getInt(0) != 0;
                    }
                } finally {
                    c.close();
                }
                return null;
            } finally {
                sql.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "lspd probe: query failed", t);
            return null;
        } finally {
            deleteTree(dir);
        }
    }

    private static boolean copyAsRoot(String source, File target, boolean required) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(
                    new String[]{"su", "-c", "cat " + source});
            final Process running = process;
            final FileOutputStream out = new FileOutputStream(target);
            Thread pump = new Thread(() -> {
                try (InputStream in = running.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "lspd copy pump failed: " + source, t);
                } finally {
                    try {
                        out.close();
                    } catch (Throwable ignored) {
                    }
                }
            }, "lspd-copy");
            pump.setDaemon(true);
            pump.start();
            // First-time root permission can take longer than 5s while the
            // KernelSU/Magisk prompt is on screen. The pump thread also has to
            // be bounded: if the prompt is never answered, a direct read on
            // stdout would otherwise block this probe forever.
            boolean finished = process.waitFor(15, TimeUnit.SECONDS);
            if (!finished) {
                process.destroy();
                pump.join(1000);
                Log.w(TAG, "lspd copy timeout: " + source);
                return false;
            }
            int exit = process.exitValue();
            String error = readSmall(process.getErrorStream());
            pump.join(2000);
            if (required && (exit != 0 || target.length() == 0)) {
                Log.w(TAG, "lspd copy failed: " + source + " exit=" + exit
                        + " bytes=" + target.length()
                        + (error.isEmpty() ? "" : " stderr=" + error));
            }
            return exit == 0 && target.length() > 0;
        } catch (Throwable t) {
            Log.w(TAG, "lspd copy failed: " + source, t);
            return false;
        } finally {
            if (process != null) process.destroy();
        }
    }

    private static String readSmall(InputStream stream) {
        try (InputStream in = stream) {
            byte[] buffer = new byte[1024];
            int read = in.read(buffer);
            if (read <= 0) return "";
            return new String(buffer, 0, read, StandardCharsets.UTF_8).trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private static void deleteTree(File file) {
        try {
            if (file == null || !file.exists()) return;
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteTree(child);
            }
            file.delete();
        } catch (Throwable ignored) {
        }
    }
}
