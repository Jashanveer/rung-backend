package com.project.rung.api.dto;

public record MeResponse(Long userId, String email, String username, boolean profileSetupCompleted) {}
