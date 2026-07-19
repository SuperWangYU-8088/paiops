package com.paiagent.service;

import com.paiagent.dto.ExecutionEvent;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class TaskEventBroker {

    private static final int HISTORY_LIMIT = 200;
    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final Map<Long, Deque<ExecutionEvent>> history = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long executionId) {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        subscribers.computeIfAbsent(executionId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(executionId, emitter));
        emitter.onTimeout(() -> remove(executionId, emitter));
        emitter.onError(error -> remove(executionId, emitter));
        for (ExecutionEvent event : snapshot(executionId)) {
            send(emitter, event);
        }
        return emitter;
    }

    public void publish(Long executionId, ExecutionEvent event) {
        Deque<ExecutionEvent> events = history.computeIfAbsent(executionId, ignored -> new ArrayDeque<>());
        synchronized (events) {
            events.addLast(event);
            while (events.size() > HISTORY_LIMIT) {
                events.removeFirst();
            }
        }
        CopyOnWriteArrayList<SseEmitter> currentSubscribers =
                subscribers.getOrDefault(executionId, new CopyOnWriteArrayList<>());
        currentSubscribers
                .forEach(emitter -> {
                    if (!send(emitter, event)) {
                        remove(executionId, emitter);
                    }
                });
        if ("WORKFLOW_COMPLETE".equals(event.getEventType())) {
            currentSubscribers.forEach(SseEmitter::complete);
            subscribers.remove(executionId);
        }
    }

    private List<ExecutionEvent> snapshot(Long executionId) {
        Deque<ExecutionEvent> events = history.get(executionId);
        if (events == null) {
            return List.of();
        }
        synchronized (events) {
            return new ArrayList<>(events);
        }
    }

    private boolean send(SseEmitter emitter, ExecutionEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event.getEventType())
                    .data(event, MediaType.APPLICATION_JSON));
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private void remove(Long executionId, SseEmitter emitter) {
        List<SseEmitter> emitters = subscribers.get(executionId);
        if (emitters != null) {
            emitters.remove(emitter);
        }
    }
}
