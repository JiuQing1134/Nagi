package com.nemu.updateguard;

/** Pure parsing helper for the root stat snapshot check. */
final class ProbeStat {
    private ProbeStat() {
    }

    /** True when either stat failed for an existing file. */
    static boolean hasError(String stat) {
        return stat.contains("DB_ERROR") || stat.contains("WAL_ERROR");
    }

    /** True only when the WAL line exists and its size is non-zero. */
    static boolean walHasData(String stat) {
        int separator = stat.indexOf("---");
        if (separator < 0) return false;
        String wal = stat.substring(separator + 3).trim();
        if (wal.isEmpty()) return false;
        return !wal.startsWith("WAL_MISSING")
                && !wal.startsWith("WAL_ERROR")
                && !wal.startsWith("0:");
    }
}
