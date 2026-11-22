package com.nemu.updateguard;

/** Pure parsing helper for the root stat snapshot check. */
final class ProbeStat {
    private ProbeStat() {
    }

    /** True only when the WAL line exists and its size is non-zero. */
    static boolean walHasData(String stat) {
        int separator = stat.indexOf("---");
        if (separator < 0) return false;
        String wal = stat.substring(separator + 3).trim();
        if (wal.isEmpty()) return false;
        return !wal.startsWith("WAL_MISSING") && !wal.startsWith("0:");
    }
}
