package com.hancom.ai.docpilot.docpilot.repository;

import com.hancom.ai.docpilot.docpilot.entity.WebhookEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WebhookEventRepository extends JpaRepository<WebhookEventEntity, Long> {
    List<WebhookEventEntity> findTop100ByOrderByTimestampDesc();
    List<WebhookEventEntity> findByStatusOrderByTimestampDesc(String status);
}
