package com.project.habit_tracker.api.dto;

import java.util.Map;

public record HabitResponse(
        Long id,
        String title,
        Map<String, Boolean> checksByDate
) {
}