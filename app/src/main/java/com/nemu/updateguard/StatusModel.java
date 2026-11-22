package com.nemu.updateguard;

/** Pure branch selection for the Nagi status card. */
final class StatusModel {
    private StatusModel() {
    }

    enum State {
        FRESH_ACTIVE,
        FRESH_FAILED,
        NOT_INSTALLED,
        CHECKING_HOOK,
        CHECKING_MANAGER,
        MANAGER_FAILED,
        MANAGER_OFF,
        SCOPE_OFF,
        ENABLED_HISTORICAL_ACTIVE,
        ENABLED_HISTORICAL_FAILED,
        ENABLED,
        UNKNOWN
    }

    /** States that count as "已激活" for the chip and its click action. */
    static boolean isActive(State state) {
        return state == State.FRESH_ACTIVE
                || state == State.ENABLED_HISTORICAL_ACTIVE;
    }

    static State compute(
            boolean installed,
            boolean hookFresh,
            boolean hookActive,
            boolean hookPending,
            boolean probeDone,
            boolean probeFailed,
            Boolean managerEnabled,
            Boolean oucScoped,
            boolean historicalActive,
            boolean historicalFailed) {
        if (hookFresh && hookActive) return State.FRESH_ACTIVE;
        if (hookFresh) return State.FRESH_FAILED;
        if (!installed) return State.NOT_INSTALLED;
        if (hookPending) return State.CHECKING_HOOK;
        if (!probeDone) return State.CHECKING_MANAGER;
        if (probeFailed) return State.MANAGER_FAILED;
        if (Boolean.FALSE.equals(managerEnabled)) return State.MANAGER_OFF;
        if (Boolean.FALSE.equals(oucScoped)) return State.SCOPE_OFF;
        if (Boolean.TRUE.equals(managerEnabled)) {
            if (historicalActive) return State.ENABLED_HISTORICAL_ACTIVE;
            if (historicalFailed) return State.ENABLED_HISTORICAL_FAILED;
            return State.ENABLED;
        }
        return State.UNKNOWN;
    }
}
