package com.hancom.ai.docpilot.docpilot.web;

import com.hancom.ai.docpilot.docpilot.entity.WebhookEventEntity;
import com.hancom.ai.docpilot.docpilot.repository.WebhookEventRepository;
import com.hancom.ai.docpilot.docpilot.web.dto.WebhookEvent;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Webhook 이벤트를 H2 DB에 저장하고 조회합니다.
 */
@Component
public class WebhookEventStore {

    private final WebhookEventRepository repository;

    public WebhookEventStore(WebhookEventRepository repository) {
        this.repository = repository;
    }

    public void add(WebhookEvent event) {
        repository.save(WebhookEventEntity.builder()
                .timestamp(event.getTimestamp())
                .eventType(event.getEventType())
                .projectName(event.getProjectName())
                .branch(event.getBranch())
                .fileCount(event.getFileCount())
                .status(event.getStatus())
                .message(event.getMessage())
                .build());
    }

    public List<WebhookEvent> getAll() {
        return repository.findTop100ByOrderByTimestampDesc().stream()
                .map(this::toDto)
                .toList();
    }

    public List<WebhookEvent> getByStatus(String status) {
        return repository.findByStatusOrderByTimestampDesc(status).stream()
                .map(this::toDto)
                .toList();
    }

    private WebhookEvent toDto(WebhookEventEntity entity) {
        return WebhookEvent.builder()
                .timestamp(entity.getTimestamp())
                .eventType(entity.getEventType())
                .projectName(entity.getProjectName())
                .branch(entity.getBranch())
                .fileCount(entity.getFileCount())
                .status(entity.getStatus())
                .message(entity.getMessage())
                .build();
    }
}
