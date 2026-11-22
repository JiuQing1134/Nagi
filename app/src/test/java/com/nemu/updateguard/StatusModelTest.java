package com.nemu.updateguard;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class StatusModelTest {
    private static StatusModel.State state(
            boolean installed, boolean fresh, boolean active, boolean pending,
            boolean probeDone, boolean probeFailed, Boolean manager, Boolean scope,
            boolean historicalActive, boolean historicalFailed) {
        return StatusModel.compute(installed, fresh, active, pending, probeDone,
                probeFailed, manager, scope, historicalActive, historicalFailed);
    }

    @Test
    public void freshReportWinsBeforeEverythingElse() {
        assertEquals(StatusModel.State.FRESH_ACTIVE,
                state(false, true, true, false, false, false, null, null, false, false));
        assertEquals(StatusModel.State.FRESH_FAILED,
                state(false, true, false, false, false, false, null, null, false, false));
    }

    @Test
    public void installationAndCheckingOrder() {
        assertEquals(StatusModel.State.NOT_INSTALLED,
                state(false, false, false, false, false, false, null, null, false, false));
        assertEquals(StatusModel.State.CHECKING_HOOK,
                state(true, false, false, true, false, false, null, null, false, false));
        assertEquals(StatusModel.State.CHECKING_MANAGER,
                state(true, false, false, false, false, false, null, null, false, false));
    }

    @Test
    public void managerFailureAndSwitchOrder() {
        assertEquals(StatusModel.State.MANAGER_FAILED,
                state(true, false, false, false, true, true, false, false, false, false));
        assertEquals(StatusModel.State.MANAGER_OFF,
                state(true, false, false, false, true, false, false, false, false, false));
        assertEquals(StatusModel.State.SCOPE_OFF,
                state(true, false, false, false, true, false, null, false, false, false));
    }

    @Test
    public void enabledHistoricalVariants() {
        assertEquals(StatusModel.State.ENABLED_HISTORICAL_ACTIVE,
                state(true, false, false, false, true, false, true, true, true, false));
        assertEquals(StatusModel.State.ENABLED_HISTORICAL_FAILED,
                state(true, false, false, false, true, false, true, true, false, true));
        assertEquals(StatusModel.State.ENABLED,
                state(true, false, false, false, true, false, true, true, false, false));
        assertEquals(StatusModel.State.UNKNOWN,
                state(true, false, false, false, true, false, null, null, false, false));
    }
}
