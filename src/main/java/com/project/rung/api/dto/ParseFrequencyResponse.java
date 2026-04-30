package com.project.rung.api.dto;

/**
 * Response for {@code POST /api/habits/parse-frequency}.
 * <p>
 * {@code didMatch} is {@code false} when the LLM couldn't extract a
 * weekly cadence — clients use this to decide whether to apply the
 * suggestion or leave the user's input untouched. {@code weeklyTarget}
 * is {@code null} for daily/unspecified cadence even when the parse
 * succeeded.
 */
public record ParseFrequencyResponse(
        String cleanedTitle,
        Integer weeklyTarget,
        boolean didMatch
) {}
