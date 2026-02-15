package com.myorg.config;

import java.util.List;

public record NetworkStackConfig(
        List<String> adminAllowedCidrs,
        int adminServerPort,
        int adminWebPort,
        int customerServerPort,
        int customerWebPort
) {
}
