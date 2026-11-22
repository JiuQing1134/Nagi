package com.nemu.updateguard;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ProbeStatTest {
    @Test
    public void walWithFramesHasData() {
        assertTrue(ProbeStat.walHasData(
                "61440:2026-11-22 22:20:18.191213854 +0800\n---\n"
                        + "482072:2026-11-23 00:35:04.971210769 +0800"));
    }

    @Test
    public void missingWalHasNoData() {
        assertFalse(ProbeStat.walHasData(
                "61440:2026-11-22 22:20:18.191213854 +0800\n---\nWAL_MISSING"));
    }

    @Test
    public void zeroLengthWalHasNoData() {
        assertFalse(ProbeStat.walHasData(
                "61440:2026-11-22 22:20:18.191213854 +0800\n---\n"
                        + "0:2026-11-23 00:35:04.971210769 +0800"));
    }

    @Test
    public void malformedOutputHasNoData() {
        assertFalse(ProbeStat.walHasData(""));
        assertFalse(ProbeStat.walHasData("61440:2026-11-22 22:20:18.191213854 +0800"));
        assertFalse(ProbeStat.walHasData("---\n"));
    }

    @Test
    public void statErrorsAreDetected() {
        assertTrue(ProbeStat.hasError(
                "DB_ERROR\n---\n482072:2026-11-23 00:35:04.971210769 +0800"));
        assertTrue(ProbeStat.hasError(
                "61440:2026-11-22 22:20:18.191213854 +0800\n---\nWAL_ERROR"));
        assertFalse(ProbeStat.hasError(
                "61440:2026-11-22 22:20:18.191213854 +0800\n---\nWAL_MISSING"));
        assertFalse(ProbeStat.walHasData(
                "61440:2026-11-22 22:20:18.191213854 +0800\n---\nWAL_ERROR"));
    }
}
