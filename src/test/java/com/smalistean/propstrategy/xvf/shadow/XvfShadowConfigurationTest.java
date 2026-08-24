package com.smalistean.propstrategy.xvf.shadow;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class XvfShadowConfigurationTest {

    @Test
    void defaultAttemptIdentityUsesTheRequestedDailySchedulerSlot() {
        assertEquals("daily-2026-08-21", XvfShadowConfiguration.defaultScheduledAttemptId(
                LocalDate.of(2026, 8, 21)));
    }
}
