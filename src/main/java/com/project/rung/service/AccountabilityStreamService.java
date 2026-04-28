package com.project.rung.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class AccountabilityStreamService {

    private static final Logger log = LoggerFactory.getLogger(AccountabilityStreamService.class);
    // Finite timeout so dropped mobile connections get reaped instead of
    // accumulating dead emitters forever. Clients already re-connect
    // with exponential backoff, so a 30-minute cap is a safe trade-off
    // between reconnect churn and leaked emitter count.
    private static final long EMITTER_TIMEOUT_MS = 30L * 60L * 1000L;
    private static final int MAX_EVENT_HISTORY = 200;
    private static final long HEARTBEAT_SECONDS = 15;

    private final AtomicLong emitterIdSeq = new AtomicLong(0);
    private final AtomicLong eventIdSeq = new AtomicLong(0);
    private final ConcurrentMap<Long, ConcurrentMap<Long, SseEmitter>> emittersByMatch = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, ArrayDeque<MatchStreamEvent>> eventHistoryByMatch = new ConcurrentHashMap<>();
    /// Parallel tracker for per-user SSE channels. Used by the
    /// real-time cross-device sync path — HabitService publishes
    /// "habits.changed" events to the owning user's emitters whenever
    /// a habit write lands, so every client the user has open gets
    /// nudged to refresh within seconds.
    private final ConcurrentMap<Long, ConcurrentMap<Long, SseEmitter>> emittersByUser = new ConcurrentHashMap<>();
    /// Parallel map tracking which client platform owns each emitter
    /// (ios / macos / unknown). Used for diagnostic logging so the
    /// publisher's subscribers=N line can show ios:1, macos:1 etc.
    /// instead of leaving the developer to guess whether two
    /// subscribers are actually two distinct devices.
    private final ConcurrentMap<Long, String> emitterPlatform = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "accountability-sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public AccountabilityStreamService() {
        heartbeatExecutor.scheduleAtFixedRate(this::broadcastHeartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    public SseEmitter subscribe(Long matchId, Long userId, String lastEventId) {
        long emitterId = emitterIdSeq.incrementAndGet();
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        emittersByMatch.computeIfAbsent(matchId, key -> new ConcurrentHashMap<>()).put(emitterId, emitter);

        emitter.onCompletion(() -> unregister(matchId, emitterId));
        emitter.onTimeout(() -> unregister(matchId, emitterId));
        emitter.onError(error -> unregister(matchId, emitterId));

        sendEvent(emitter, "stream.ready", String.valueOf(eventIdSeq.incrementAndGet()), new StreamReady(matchId, userId, Instant.now()));
        replayFromLastEventId(matchId, emitter, lastEventId);
        return emitter;
    }

    /// Registers a per-user SSE channel. One channel per
    /// authenticated connection; a user on 2 devices gets 2 emitters
    /// (different emitterIds) both subscribed to the same userId.
    /// History replay is intentionally skipped — the client responds
    /// to each event by triggering a full reconcile sync which catches
    /// up whatever was missed during a disconnect.
    public SseEmitter subscribeUser(Long userId, String platform) {
        long emitterId = emitterIdSeq.incrementAndGet();
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        String tag = (platform == null || platform.isBlank()) ? "unknown" : platform.trim().toLowerCase();
        emittersByUser.computeIfAbsent(userId, key -> new ConcurrentHashMap<>()).put(emitterId, emitter);
        emitterPlatform.put(emitterId, tag);

        emitter.onCompletion(() -> {
            log.info("user-stream completed userId={} platform={} emitterId={} remaining={}",
                    userId, tag, emitterId, openUserEmitterCount(userId) - 1);
            unregisterUser(userId, emitterId);
        });
        emitter.onTimeout(() -> {
            log.info("user-stream timeout userId={} platform={} emitterId={}", userId, tag, emitterId);
            unregisterUser(userId, emitterId);
        });
        emitter.onError(error -> {
            log.info("user-stream error userId={} platform={} emitterId={} error={}",
                    userId, tag, emitterId, error.toString());
            unregisterUser(userId, emitterId);
        });

        sendEvent(emitter, "stream.ready", String.valueOf(eventIdSeq.incrementAndGet()),
                new StreamReady(null, userId, Instant.now()));
        log.info("user-stream subscribed userId={} platform={} emitterId={} {}",
                userId, tag, emitterId, platformBreakdown(userId));
        return emitter;
    }

    private int openUserEmitterCount(Long userId) {
        Map<Long, SseEmitter> map = emittersByUser.get(userId);
        return map == null ? 0 : map.size();
    }

    /// Returns a human-readable breakdown like
    /// "total=2 ios=1 macos=1 unknown=0" for a userId.
    /// Used in diagnostic logs so the developer can tell whether
    /// "subscribers=2" actually means two different devices or one
    /// device with two stale emitters.
    private String platformBreakdown(Long userId) {
        Map<Long, SseEmitter> map = emittersByUser.get(userId);
        if (map == null || map.isEmpty()) return "total=0 ios=0 macos=0 unknown=0";
        int ios = 0, macos = 0, unknown = 0;
        for (Long emitterId : map.keySet()) {
            String tag = emitterPlatform.getOrDefault(emitterId, "unknown");
            switch (tag) {
                case "ios" -> ios++;
                case "macos" -> macos++;
                default -> unknown++;
            }
        }
        return String.format("total=%d ios=%d macos=%d unknown=%d", map.size(), ios, macos, unknown);
    }

    /// Broadcasts a notification to every emitter the user has open.
    /// Used by HabitService write paths to trigger a cross-device
    /// sync within seconds instead of on the next 5-minute tick.
    /// Payload is intentionally tiny (just "at" timestamp) — clients
    /// respond by hitting the reconcile endpoint, not by applying
    /// deltas directly, so the event body doesn't need to carry the
    /// changed data.
    public void publishToUser(Long userId, String eventName, Object payload) {
        Map<Long, SseEmitter> emitters = emittersByUser.get(userId);
        if (emitters == null || emitters.isEmpty()) {
            log.info("publishToUser userId={} event={} subscribers=0 — no devices online to receive",
                    userId, eventName);
            return;
        }
        String eventId = String.valueOf(eventIdSeq.incrementAndGet());
        log.info("publishToUser userId={} event={} {}", userId, eventName, platformBreakdown(userId));
        for (Map.Entry<Long, SseEmitter> entry : emitters.entrySet()) {
            Long emitterId = entry.getKey();
            String tag = emitterPlatform.getOrDefault(emitterId, "unknown");
            boolean delivered = sendEvent(entry.getValue(), eventName, eventId, payload);
            if (delivered) {
                log.info("  → delivered userId={} platform={} emitterId={}", userId, tag, emitterId);
            } else {
                log.info("  ✗ delivery FAILED userId={} platform={} emitterId={} — unregistering",
                        userId, tag, emitterId);
                unregisterUser(userId, emitterId);
            }
        }
    }

    public void publishToMatch(Long matchId, String eventName, Object payload) {
        String eventId = String.valueOf(eventIdSeq.incrementAndGet());
        MatchStreamEvent event = new MatchStreamEvent(eventId, eventName, payload, Instant.now());
        appendEvent(matchId, event);

        Map<Long, SseEmitter> emitters = emittersByMatch.get(matchId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        for (Map.Entry<Long, SseEmitter> entry : emitters.entrySet()) {
            if (!sendEvent(entry.getValue(), eventName, eventId, payload)) {
                unregister(matchId, entry.getKey());
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        heartbeatExecutor.shutdownNow();
    }

    private void replayFromLastEventId(Long matchId, SseEmitter emitter, String lastEventId) {
        OptionalLong lastSeenId = parseEventId(lastEventId);
        if (lastSeenId.isEmpty()) {
            return;
        }

        ArrayDeque<MatchStreamEvent> history = eventHistoryByMatch.get(matchId);
        if (history == null || history.isEmpty()) {
            return;
        }

        long threshold = lastSeenId.getAsLong();
        for (MatchStreamEvent event : history) {
            long current = Long.parseLong(event.id());
            if (current <= threshold) {
                continue;
            }
            sendEvent(emitter, event.name(), event.id(), event.payload());
        }
    }

    private void appendEvent(Long matchId, MatchStreamEvent event) {
        ArrayDeque<MatchStreamEvent> history = eventHistoryByMatch.computeIfAbsent(matchId, key -> new ArrayDeque<>());
        synchronized (history) {
            history.addLast(event);
            while (history.size() > MAX_EVENT_HISTORY) {
                history.removeFirst();
            }
        }
    }

    private OptionalLong parseEventId(String raw) {
        if (raw == null || raw.isBlank()) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(raw.trim()));
        } catch (NumberFormatException ignored) {
            return OptionalLong.empty();
        }
    }

    private void unregister(Long matchId, Long emitterId) {
        Map<Long, SseEmitter> emitters = emittersByMatch.get(matchId);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitterId);
        if (emitters.isEmpty()) {
            emittersByMatch.remove(matchId);
        }
    }

    private void unregisterUser(Long userId, Long emitterId) {
        Map<Long, SseEmitter> emitters = emittersByUser.get(userId);
        if (emitters == null) {
            emitterPlatform.remove(emitterId);
            return;
        }
        emitters.remove(emitterId);
        emitterPlatform.remove(emitterId);
        if (emitters.isEmpty()) {
            emittersByUser.remove(userId);
        }
    }

    private void broadcastHeartbeat() {
        for (Map.Entry<Long, ConcurrentMap<Long, SseEmitter>> matchEntry : emittersByMatch.entrySet()) {
            Long matchId = matchEntry.getKey();
            for (Map.Entry<Long, SseEmitter> emitterEntry : matchEntry.getValue().entrySet()) {
                if (!sendEvent(emitterEntry.getValue(), "ping", null, Map.of("at", Instant.now().toString()))) {
                    unregister(matchId, emitterEntry.getKey());
                }
            }
        }
        for (Map.Entry<Long, ConcurrentMap<Long, SseEmitter>> userEntry : emittersByUser.entrySet()) {
            Long userId = userEntry.getKey();
            for (Map.Entry<Long, SseEmitter> emitterEntry : userEntry.getValue().entrySet()) {
                if (!sendEvent(emitterEntry.getValue(), "ping", null, Map.of("at", Instant.now().toString()))) {
                    unregisterUser(userId, emitterEntry.getKey());
                }
            }
        }
    }

    private boolean sendEvent(SseEmitter emitter, String eventName, String eventId, Object payload) {
        try {
            SseEmitter.SseEventBuilder builder = SseEmitter.event().name(eventName).data(payload);
            if (eventId != null) {
                builder.id(eventId);
            }
            emitter.send(builder);
            return true;
        } catch (IOException | IllegalStateException ignored) {
            return false;
        }
    }

    private record MatchStreamEvent(String id, String name, Object payload, Instant at) {}

    private record StreamReady(Long matchId, Long userId, Instant at) {}
}
