package com.project.rung.service.event;

/// Fired right after a fresh AI-mentor match is created with its static
/// fallback welcome row. The async listener regenerates personalised text and
/// updates the row in place so the dashboard endpoint can return immediately
/// without waiting on Anthropic.
public record AiMentorWelcomeRequestedEvent(Long matchId, Long welcomeMessageId) {
}
