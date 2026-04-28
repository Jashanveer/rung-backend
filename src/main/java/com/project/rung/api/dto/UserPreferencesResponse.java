package com.project.rung.api.dto;

/// Per-user notification settings. Today only weekly-report email is wired up;
/// new toggles get added as additional fields rather than a flag map so the
/// client remains strictly typed.
public record UserPreferencesResponse(
        boolean emailOptIn
) {
}
