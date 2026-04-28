package com.project.rung.api.dto;

import java.util.Map;

public record TaskResponse(
        Long id,
        String title,
        Map<String, Boolean> checksByDate
) {
}
