package com.sonnet.wyf.gitreport.console;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class EventStreamService {
    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(long runId, List<WorkflowRunEvent> existingEvents) throws IOException {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.computeIfAbsent(runId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(runId, emitter));
        emitter.onTimeout(() -> remove(runId, emitter));
        emitter.onError(error -> remove(runId, emitter));
        for (WorkflowRunEvent event : existingEvents) {
            emitter.send(SseEmitter.event().id(Long.toString(event.id())).name(event.eventType()).data(event));
        }
        return emitter;
    }

    public void publish(WorkflowRunEvent event) {
        for (SseEmitter emitter : emitters.getOrDefault(event.runId(), List.of())) {
            try {
                emitter.send(SseEmitter.event().id(Long.toString(event.id())).name(event.eventType()).data(event));
            } catch (IOException exception) {
                remove(event.runId(), emitter);
            }
        }
    }

    private void remove(long runId, SseEmitter emitter) {
        List<SseEmitter> runEmitters = emitters.get(runId);
        if (runEmitters != null) {
            runEmitters.remove(emitter);
        }
    }
}
