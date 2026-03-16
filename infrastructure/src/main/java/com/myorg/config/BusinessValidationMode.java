package com.myorg.config;

/**
 * 배치 종료 후 비즈니스 검증 방식.
 */
public enum BusinessValidationMode {
    LEGACY_PAYLOAD,
    OUTBOX_REQUEST_COUNTS;

    public static BusinessValidationMode fromEnv(String raw) {
        if (raw == null || raw.isBlank()) {
            return LEGACY_PAYLOAD;
        }
        return BusinessValidationMode.valueOf(raw.trim().toUpperCase());
    }
}
