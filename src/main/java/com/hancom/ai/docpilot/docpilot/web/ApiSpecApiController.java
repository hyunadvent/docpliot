package com.hancom.ai.docpilot.docpilot.web;

import com.hancom.ai.docpilot.docpilot.config.ConfigLoaderService;
import com.hancom.ai.docpilot.docpilot.config.model.ConfluenceStructure;
import com.hancom.ai.docpilot.docpilot.source.gitlab.GitLabSourceService;
import com.hancom.ai.docpilot.docpilot.webhook.ApiSpecInitializationService;
import com.hancom.ai.docpilot.docpilot.webhook.DjangoApiSpecService;
import com.hancom.ai.docpilot.docpilot.webhook.ExpressApiSpecService;
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
    private final ExpressApiSpecService expressApiSpecService;
    private final DjangoApiSpecService djangoApiSpecService;
    private final ProcessingStatusTracker processingStatusTracker;

    public ApiSpecApiController(ConfigLoaderService configLoaderService,
                                GitLabSourceService gitLabSourceService,
                                ApiSpecInitializationService apiSpecInitializationService,
                                ExpressApiSpecService expressApiSpecService,
                                DjangoApiSpecService djangoApiSpecService,
                                ProcessingStatusTracker processingStatusTracker) {
        this.configLoaderService = configLoaderService;
        this.gitLabSourceService = gitLabSourceService;
        this.apiSpecInitializationService = apiSpecInitializationService;
        this.expressApiSpecService = expressApiSpecService;
        this.djangoApiSpecService = djangoApiSpecService;
        this.processingStatusTracker = processingStatusTracker;
    }

    /**
     * 현재 처리 중인 Controller 목록을 반환합니다.
     */
    @GetMapping("/processing")
    public ResponseEntity<Map<String, Object>> getProcessingStatus() {
        Set<String> processing = processingStatusTracker.getProcessingControllers();
        Set<Long> projectIds = processingStatusTracker.getProcessingProjectIds();
        Set<Long> initializingProjects = processingStatusTracker.getInitializingProjects();
        Map<String, ProcessingStatusTracker.ControllerProgress> progress = processingStatusTracker.getControllerProgressMap();

        // progress를 직렬화 가능한 형태로 변환
        Map<String, Map<String, Object>> progressMap = new java.util.HashMap<>();
        for (var entry : progress.entrySet()) {
            ProcessingStatusTracker.ControllerProgress p = entry.getValue();
            progressMap.put(entry.getKey(), Map.of(
                    "completedApis", p.getCompletedApis(),
                    "totalApis", p.getTotalApis(),
                    "currentApiName", p.getCurrentApiName() != null ? p.getCurrentApiName() : ""
            ));
        }

        return ResponseEntity.ok(Map.of(
                "processing", processing,
                "count", processing.size(),
                "projectIds", projectIds,
                "initializingProjects", initializingProjects,
                "progress", progressMap
        ));
    }

    /**
     * DB와 Confluence 간 동기화를 수동으로 실행합니다.
     */
    @PostMapping("/sync")
    public ResponseEntity<Map<String, String>> sync() {
        try {
            com.hancom.ai.docpilot.docpilot.config.model.ConfluenceStructure structure =
                    configLoaderService.getConfluenceStructure();
            String spaceKey = structure.getSpaceKey();
            apiSpecInitializationService.runSync(spaceKey, structure);
            return ResponseEntity.ok(Map.of("message", "동기화가 완료되었습니다."));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", "동기화 실패: " + e.getMessage()));
        }
    }

    /**
     * 특정 Controller의 API 명세서를 재생성합니다.
     */
    @PostMapping("/regenerate")
    public ResponseEntity<Map<String, String>> regenerate(@RequestBody Map<String, Object> request) {
        Long projectId = Long.valueOf(request.get("projectId").toString());
        String controllerPath = (String) request.get("controllerPath");

        if (!processingStatusTracker.canAcceptMore()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "동시에 2개까지만 실행할 수 있습니다. 완료 후 다시 시도하세요."));
        }

        ConfluenceStructure.ProjectMapping project = configLoaderService.getProjectMapping(projectId);
        if (project == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "프로젝트를 찾을 수 없습니다."));
        }

        try {
            String spaceKey = configLoaderService.getConfluenceStructure().getSpaceKey();
            String defaultBranch = gitLabSourceService.getDefaultBranch(projectId);

            String platform = project.getPlatform() != null ? project.getPlatform() : "springboot";
            switch (platform) {
                case "express" -> expressApiSpecService.processControllerUpdate(
                        spaceKey, project, List.of(controllerPath), defaultBranch);
                case "django" -> djangoApiSpecService.processControllerUpdate(
                        spaceKey, project, List.of(controllerPath), defaultBranch);
                default -> apiSpecInitializationService.processControllerUpdate(
                        spaceKey, project, List.of(controllerPath), defaultBranch);
            }

            log.info("API 명세서 재생성 완료: project={}, controller={}", projectId, controllerPath);
            return ResponseEntity.ok(Map.of("message", "API 명세서가 재생성되었습니다."));
        } catch (Exception e) {
            log.error("API 명세서 재생성 실패: {}", controllerPath, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", "재생성 실패: " + e.getMessage()));
        }
    }
}
