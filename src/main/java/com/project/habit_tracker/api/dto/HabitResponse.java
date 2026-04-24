package com.project.habit_tracker.api.dto;

import java.util.Map;

/**
 * Habit read payload. Verification fields mirror the Swift model so clients
 * round-trip them verbatim. Legacy rows stored pre-Verification surface
 * nulls for every new field, which the client maps to "daily + self-report"
 * defaults.
 */
public record HabitResponse(
        Long id,
        String title,
        String reminderWindow,
        Map<String, Boolean> checksByDate,
        String canonicalKey,
        String verificationTier,
        String verificationSource,
        Double verificationParam,
        Integer weeklyTarget
) {
}
