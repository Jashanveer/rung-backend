package com.project.habit_tracker.api.dto;

import java.util.Map;

public record TaskResponse(
        Long id,
        String title,
        Map<String, Boolean> checksByDate
) {
}
