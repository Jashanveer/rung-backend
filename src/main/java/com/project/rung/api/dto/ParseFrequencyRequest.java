package com.project.rung.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body for {@code POST /api/habits/parse-frequency}. The client sends the
 * raw user-typed habit title; the server returns a {@link ParseFrequencyResponse}
 * with the cleaned title and an optional weekly target inferred via LLM.
 *
 * Capped at 200 chars to keep prompt costs bounded — anything longer is
 * almost certainly not a habit title.
 */
public record ParseFrequencyRequest(
        @NotBlank
        @Size(max = 200)
        String text
) {}
