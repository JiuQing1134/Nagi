package com.nemu.updateguard;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.io.ByteArrayOutputStream;
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
    private static final String OUC = "com.hihonor.ouc";

    private LsposedDb() {
    }

    /** Manager switch plus whether OUC is still in the module's scope. */
    static final class State {
        final boolean enabled;
        /** null means the scope table could not be read. */
        final Boolean oucScoped;

        State(boolean enabled, Boolean oucScoped) {
            this.enabled = enabled;
            this.oucScoped = oucScoped;
        }
    }

    /** @return the manager state, or null when it could not be read. */
    static State queryState(Context context) {
        File dir = new File(context.getCacheDir(), "lspd_probe");
        try {
            deleteTree(dir);
            if (!dir.mkdirs() && !dir.isDirectory()) {
                Log.w(TAG, "lspd probe: cannot create cache dir");
                return null;
            }
            File db = new File(dir, "modules_config.db");
            File wal = new File(dir, "modules_config.db-wal");
            // Copying the main DB and WAL with two root calls is not atomic.
            // Take a source stat before and after; if LSPosed wrote or
            // checkpointed in between, retry once instead of trusting a torn
            // snapshot. The -shm file is not copied: it is a transient index
            // that SQLite rebuilds from the WAL.
            boolean stable = false;
            for (int attempt = 0; attempt < 2 && !stable; attempt++) {
                String before = statAsRoot();
                if (before.isEmpty()) {
                    Log.w(TAG, "lspd probe: source stat failed");
                    return null;
                }
                boolean walHasData = ProbeStat.walHasData(before);
                if (!copyAsRoot(SRC_DB, db, true)) {
                    Log.w(TAG, "lspd probe: main db copy failed");
                    continue;
                }
                if (walHasData) {
                    if (!copyAsRoot(SRC_DB + "-wal", wal, true)) {
                        Log.w(TAG, "lspd probe: wal copy failed");
                        continue;
                    }
                } else if (wal.exists() && !wal.delete()) {
                    Log.w(TAG, "lspd probe: stale wal target could not be removed");
                    return null;
                }
                String after = statAsRoot();
                if (after.isEmpty()) {
                    Log.w(TAG, "lspd probe: source stat after copy failed");
                    continue;
                }
                if (before.equals(after)) {
                    stable = true;
                } else {
                    Log.w(TAG, "lspd probe: source changed during copy, retrying");
                }
            }
            if (!stable) {
                Log.w(TAG, "lspd probe: unstable snapshot");
                return null;
            }

            SQLiteDatabase sql = SQLiteDatabase.openDatabase(
                    db.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
            try {
                boolean enabled = queryEnabled(sql);
                Boolean oucScoped = null;
                try {
                    oucScoped = queryOucScope(sql);
                } catch (Throwable t) {
                    // Older/forked LSPosed databases may not have scope.
                    Log.w(TAG, "lspd probe: scope query failed", t);
                }
                return new State(enabled, oucScoped);
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

    private static boolean queryEnabled(SQLiteDatabase sql) {
        Cursor c = sql.rawQuery(
                "SELECT enabled FROM modules_state "
                        + "WHERE module_pkg_name=? AND user_id=0",
                new String[]{MODULE});
        try {
            if (c.moveToFirst()) return c.getInt(0) != 0;
        } finally {
            c.close();
        }
        // No row means the module is not registered/enabled, not that the
        // database read failed.
        return false;
    }

    private static Boolean queryOucScope(SQLiteDatabase sql) {
        Cursor c = sql.rawQuery(
                "SELECT 1 FROM scope WHERE module_pkg_name=? "
                        + "AND app_pkg_name=? AND user_id=0",
                new String[]{MODULE, OUC});
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }

    private static String statAsRoot() {
        String command = "stat -c '%s:%y' " + SRC_DB
                + " 2>/dev/null || echo DB_MISSING; echo '---'; "
                + "stat -c '%s:%y' " + SRC_DB + "-wal"
                + " 2>/dev/null || echo WAL_MISSING";
        return runAsRoot(command, 15);
    }

    /** Runs a short fixed root command and returns its stdout, or "". */
    private static String runAsRoot(String command, int timeoutSeconds) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            final Process running = process;
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread pump = new Thread(() -> {
                try (InputStream in = running.getInputStream()) {
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "lspd stat pump failed", t);
                }
            }, "lspd-stat");
            pump.setDaemon(true);
            pump.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroy();
                pump.join(1000);
                Log.w(TAG, "lspd stat timeout");
                return "";
            }
            int exit = process.exitValue();
            pump.join(2000);
            if (exit != 0) {
                Log.w(TAG, "lspd stat exit=" + exit);
                return "";
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
        } catch (Throwable t) {
            Log.w(TAG, "lspd stat failed", t);
            return "";
        } finally {
            if (process != null) process.destroy();
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
