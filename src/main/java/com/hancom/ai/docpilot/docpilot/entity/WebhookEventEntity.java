package com.hancom.ai.docpilot.docpilot.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "webhook_events", indexes = {
        @Index(name = "idx_webhook_status", columnList = "status"),
        @Index(name = "idx_webhook_timestamp", columnList = "timestamp")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebhookEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDateTime timestamp;
    private String eventType;
    private String projectName;
    private String branch;
    private int fileCount;
    private String status;

    @Column(length = 1000)
    private String message;
}
