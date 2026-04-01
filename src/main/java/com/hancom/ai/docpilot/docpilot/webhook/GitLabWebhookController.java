package com.hancom.ai.docpilot.docpilot.webhook;

import com.hancom.ai.docpilot.docpilot.config.ConfigLoaderService;
import com.hancom.ai.docpilot.docpilot.config.SystemSettingService;
import com.hancom.ai.docpilot.docpilot.web.WebhookEventStore;
import com.hancom.ai.docpilot.docpilot.web.dto.WebhookEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/webhook")
public class GitLabWebhookController {

    private final ConfigLoaderService configLoaderService;
    private final DocumentPipelineService documentPipelineService;
    private final WebhookEventStore webhookEventStore;
    private final SystemSettingService settingService;

    public GitLabWebhookController(ConfigLoaderService configLoaderService,
                                   DocumentPipelineService documentPipelineService,
                                   WebhookEventStore webhookEventStore,
                                   SystemSettingService settingService) {
        this.configLoaderService = configLoaderService;
        this.documentPipelineService = documentPipelineService;
        this.webhookEventStore = webhookEventStore;
        this.settingService = settingService;
    }

    @PostMapping("/gitlab")
    public ResponseEntity<Map<String, String>> handleWebhook(
            @RequestHeader(value = "X-Gitlab-Token", required = false) String token,
            @RequestHeader(value = "X-Gitlab-Event", required = false) String event,
            @RequestBody Map<String, Object> payload) {

        // 1. 토큰 검증
        String webhookSecret = settingService.get(SystemSettingService.GITLAB_WEBHOOK_SECRET);
        if (token == null || !token.equals(webhookSecret)) {
            log.warn("Webhook 토큰 불일치: received=[{}], expected=[{}]", token, webhookSecret);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Unauthorized"));
        }

        // 2. 이벤트 유형 판별
        String eventType = resolveEventType(event);
        if (eventType == null) {
            log.info("처리 대상이 아닌 이벤트: {}", event);
            return ok("지원하지 않는 이벤트 유형, 무시");
        }

        // 3. payload 파싱
        Long projectId = extractProjectId(payload, eventType);
        String projectName = extractProjectName(payload, eventType);
        String branch = extractBranch(payload, eventType);
        List<String> changedFiles = extractChangedFiles(payload, eventType);
        String commitSha = extractCommitSha(payload, eventType);

        log.info("Webhook 수신: event={}, project={}, branch={}, files={}",
                eventType, projectName, branch, changedFiles.size());

        // 4. skip keyword 확인
        String skipKeyword = configLoaderService.getConfluenceStructure().getOptions().getSkipKeyword();
        if (commitSha != null && skipKeyword != null) {
            String commitMessage = extractCommitMessage(payload, eventType);
            if (commitMessage != null && commitMessage.contains(skipKeyword)) {
                log.info("Skip keyword '{}' 감지, 처리 건너뜀", skipKeyword);
                recordEvent(eventType, projectName, branch, changedFiles.size(), "SKIP", "Skip keyword 감지");
                return ok("Skip keyword 감지, 처리 건너뜀");
            }
        }

        // 5. 브랜치 필터
        if (!configLoaderService.isTriggerBranch(branch)) {
            log.info("트리거 대상 브랜치가 아님: {}", branch);
            recordEvent(eventType, projectName, branch, changedFiles.size(), "SKIP", "트리거 대상 브랜치 아님");
            return ok("트리거 대상 브랜치가 아님");
        }

        // 6. 비동기 처리 시작
        documentPipelineService.processEvent(eventType, projectId, projectName, branch, changedFiles, commitSha);
        recordEvent(eventType, projectName, branch, changedFiles.size(), "SUCCESS", "처리 시작");

        return ok("처리 시작");
    }

    private String resolveEventType(String event) {
        if (event == null) return null;
        return switch (event) {
            case "Push Hook" -> "push";
            case "Merge Request Hook" -> "merge_request";
            default -> null;
        };
    }

    @SuppressWarnings("unchecked")
    private Long extractProjectId(Map<String, Object> payload, String eventType) {
        if ("merge_request".equals(eventType)) {
            Map<String, Object> project = (Map<String, Object>) payload.get("project");
            return toLong(project.get("id"));
        }
        return toLong(payload.get("project_id"));
    }

    @SuppressWarnings("unchecked")
    private String extractProjectName(Map<String, Object> payload, String eventType) {
        if ("merge_request".equals(eventType)) {
            Map<String, Object> project = (Map<String, Object>) payload.get("project");
            return (String) project.get("name");
        }
        Map<String, Object> project = (Map<String, Object>) payload.get("project");
        return project != null ? (String) project.get("name") : "unknown";
    }

    @SuppressWarnings("unchecked")
    private String extractBranch(Map<String, Object> payload, String eventType) {
        if ("merge_request".equals(eventType)) {
            Map<String, Object> attrs = (Map<String, Object>) payload.get("object_attributes");
            return (String) attrs.get("target_branch");
        }
        // Push: ref 필드에서 refs/heads/ 제거
        String ref = (String) payload.get("ref");
        if (ref != null && ref.startsWith("refs/heads/")) {
            return ref.substring("refs/heads/".length());
        }
        return ref;
    }

    @SuppressWarnings("unchecked")
    private List<String> extractChangedFiles(Map<String, Object> payload, String eventType) {
        List<String> files = new ArrayList<>();

        if ("push".equals(eventType)) {
            List<Map<String, Object>> commits = (List<Map<String, Object>>) payload.get("commits");
            if (commits != null) {
                for (Map<String, Object> commit : commits) {
                    addAll(files, (List<String>) commit.get("added"));
                    addAll(files, (List<String>) commit.get("modified"));
                    addAll(files, (List<String>) commit.get("removed"));
                }
            }
        } else if ("merge_request".equals(eventType)) {
            Map<String, Object> attrs = (Map<String, Object>) payload.get("object_attributes");
            if (attrs != null && attrs.get("last_commit") != null) {
                // MR에서는 last_commit 정보만 payload에 포함됨
                // 실제 변경 파일은 GitLab API로 조회해야 하므로 여기선 빈 목록이 될 수 있음
                // 모든 패턴에 매칭되도록 와일드카드 파일 추가
                files.add("*");
            }
        }

        return files.stream().distinct().collect(java.util.stream.Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private String extractCommitSha(Map<String, Object> payload, String eventType) {
        if ("push".equals(eventType)) {
            return (String) payload.get("after");
        }
        if ("merge_request".equals(eventType)) {
            Map<String, Object> attrs = (Map<String, Object>) payload.get("object_attributes");
            if (attrs != null && attrs.get("last_commit") != null) {
                Map<String, Object> lastCommit = (Map<String, Object>) attrs.get("last_commit");
                return (String) lastCommit.get("id");
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private String extractCommitMessage(Map<String, Object> payload, String eventType) {
        if ("push".equals(eventType)) {
            List<Map<String, Object>> commits = (List<Map<String, Object>>) payload.get("commits");
            if (commits != null && !commits.isEmpty()) {
                // 마지막 커밋 메시지로 확인
                return (String) commits.get(commits.size() - 1).get("message");
            }
        }
        if ("merge_request".equals(eventType)) {
            Map<String, Object> attrs = (Map<String, Object>) payload.get("object_attributes");
            return attrs != null ? (String) attrs.get("title") : null;
        }
        return null;
    }

    private void recordEvent(String eventType, String projectName, String branch,
                             int fileCount, String status, String message) {
        webhookEventStore.add(WebhookEvent.builder()
                .timestamp(LocalDateTime.now())
                .eventType(eventType)
                .projectName(projectName)
                .branch(branch)
                .fileCount(fileCount)
                .status(status)
                .message(message)
                .build());
    }

    private void addAll(List<String> target, List<String> source) {
        if (source != null) {
            target.addAll(source);
        }
    }

    private Long toLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return value != null ? Long.parseLong(value.toString()) : null;
    }

    private ResponseEntity<Map<String, String>> ok(String message) {
        return ResponseEntity.ok(Map.of("message", message));
    }
}
