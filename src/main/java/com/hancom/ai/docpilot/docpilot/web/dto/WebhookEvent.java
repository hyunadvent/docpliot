package com.hancom.ai.docpilot.docpilot.web.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class WebhookEvent {
    private LocalDateTime timestamp;
    private String eventType;
    private String projectName;
    private String branch;
    private int fileCount;
    private String status;     // SUCCESS, SKIP, ERROR
    private String message;
}
