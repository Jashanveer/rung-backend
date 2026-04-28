package com.project.rung.service.event;

public record MenteeMessageReceivedEvent(Long matchId, String menteeText) {
}
