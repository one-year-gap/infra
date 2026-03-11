package com.myorg.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnalysisServerReadinessConfigTest {

    @Test
    @DisplayName("scale-up desired count는 1 이상이어야 한다.")
    void should_reject_zero_scale_up_desired_count() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> AnalysisServerReadinessConfig.parseScaleUpDesiredCount("0")
        );

        assertEquals(
                EnvKey.ON_DEMAND_ANALYSIS_SERVER_SCALE_UP_DESIRED_COUNT.key() + " 값은 1 이상이어야 합니다.",
                exception.getMessage()
        );
    }

    @Test
    @DisplayName("scale-down desired count는 0을 허용해야 한다.")
    void should_allow_zero_scale_down_desired_count() {
        assertDoesNotThrow(() -> {
            int desiredCount = AnalysisServerReadinessConfig.parseScaleDownDesiredCount("0");
            assertEquals(0, desiredCount);
        });
    }
}
