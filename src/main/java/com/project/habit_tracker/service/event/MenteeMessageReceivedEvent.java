package com.project.habit_tracker.service.event;

public record MenteeMessageReceivedEvent(Long matchId, String menteeText) {
}
