package com.nemu.updateguard;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * Read-only view of LSPosed's module state. The hook report tells us about the
 * current boot; this database probe additionally tells us when the user turns
 * the module OFF in the manager, which cannot be inferred from a past report
 * alone. Root is required only for this probe; without root the app falls back
 * to the persisted hook report.
 */
final class LsposedDb {
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
            if (!dir.mkdirs() && !dir.isDirectory()) return null;
            File db = new File(dir, "modules_config.db");
            if (!copyAsRoot(SRC_DB, db)) return null;
            copyAsRoot(SRC_DB + "-wal", new File(dir, "modules_config.db-wal"));
            copyAsRoot(SRC_DB + "-shm", new File(dir, "modules_config.db-shm"));

            SQLiteDatabase sql = SQLiteDatabase.openDatabase(
                    db.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
            try {
                Cursor c = sql.rawQuery(
                        "SELECT enabled FROM modules_state "
                                + "WHERE module_pkg_name=? AND user_id=0",
                        new String[]{MODULE});
                try {
                    if (c.moveToFirst()) return c.getInt(0) != 0;
                } finally {
                    c.close();
                }
                return null;
            } finally {
                sql.close();
            }
        } catch (Throwable t) {
            return null;
        } finally {
            deleteTree(dir);
        }
    }

    private static boolean copyAsRoot(String source, File target) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(
                    new String[]{"su", "-c", "cat " + source});
            try (InputStream in = process.getInputStream();
                 FileOutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0 && target.length() > 0;
        } catch (Throwable t) {
            return false;
        } finally {
            if (process != null) process.destroy();
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
