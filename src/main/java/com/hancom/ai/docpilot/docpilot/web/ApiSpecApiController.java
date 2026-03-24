package com.hancom.ai.docpilot.docpilot.web;

import com.hancom.ai.docpilot.docpilot.config.ConfigLoaderService;
import com.hancom.ai.docpilot.docpilot.config.model.ConfluenceStructure;
import com.hancom.ai.docpilot.docpilot.source.gitlab.GitLabSourceService;
import com.hancom.ai.docpilot.docpilot.webhook.ApiSpecInitializationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@RestController
@RequestMapping("/api/api-specs")
public class ApiSpecApiController {

    private final ConfigLoaderService configLoaderService;
    private final GitLabSourceService gitLabSourceService;
    private final ApiSpecInitializationService apiSpecInitializationService;
    private final ProcessingStatusTracker processingStatusTracker;

    public ApiSpecApiController(ConfigLoaderService configLoaderService,
                                GitLabSourceService gitLabSourceService,
                                ApiSpecInitializationService apiSpecInitializationService,
                                ProcessingStatusTracker processingStatusTracker) {
        this.configLoaderService = configLoaderService;
        this.gitLabSourceService = gitLabSourceService;
        this.apiSpecInitializationService = apiSpecInitializationService;
        this.processingStatusTracker = processingStatusTracker;
    }

    /**
     * 현재 처리 중인 Controller 목록을 반환합니다.
     */
    @GetMapping("/processing")
    public ResponseEntity<Map<String, Object>> getProcessingStatus() {
        Set<String> processing = processingStatusTracker.getProcessingControllers();
        return ResponseEntity.ok(Map.of(
                "processing", processing,
                "count", processing.size()
        ));
    }

    /**
     * 특정 Controller의 API 명세서를 재생성합니다.
     */
    @PostMapping("/regenerate")
    public ResponseEntity<Map<String, String>> regenerate(@RequestBody Map<String, Object> request) {
        Long projectId = Long.valueOf(request.get("projectId").toString());
        String controllerPath = (String) request.get("controllerPath");

        ConfluenceStructure.ProjectMapping project = configLoaderService.getProjectMapping(projectId);
        if (project == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "프로젝트를 찾을 수 없습니다."));
        }

        try {
            String spaceKey = configLoaderService.getConfluenceStructure().getSpaceKey();
            String defaultBranch = gitLabSourceService.getDefaultBranch(projectId);

            apiSpecInitializationService.processControllerUpdate(
                    spaceKey, project, List.of(controllerPath), defaultBranch);

            log.info("API 명세서 재생성 완료: project={}, controller={}", projectId, controllerPath);
            return ResponseEntity.ok(Map.of("message", "API 명세서가 재생성되었습니다."));
        } catch (Exception e) {
            log.error("API 명세서 재생성 실패: {}", controllerPath, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", "재생성 실패: " + e.getMessage()));
        }
    }
}
