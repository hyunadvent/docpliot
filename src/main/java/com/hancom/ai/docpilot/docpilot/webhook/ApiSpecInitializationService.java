package com.hancom.ai.docpilot.docpilot.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hancom.ai.docpilot.docpilot.config.ConfigLoaderService;
import com.hancom.ai.docpilot.docpilot.config.model.ConfluenceStructure;
import com.hancom.ai.docpilot.docpilot.entity.ApiPageMappingEntity;
import com.hancom.ai.docpilot.docpilot.entity.ControllerPageMappingEntity;
import com.hancom.ai.docpilot.docpilot.llm.LLMRouter;
import com.hancom.ai.docpilot.docpilot.llm.PromptTemplateService;
import com.hancom.ai.docpilot.docpilot.repository.ApiPageMappingRepository;
import com.hancom.ai.docpilot.docpilot.repository.ControllerPageMappingRepository;
import com.hancom.ai.docpilot.docpilot.source.gitlab.GitLabSourceService;
import com.hancom.ai.docpilot.docpilot.target.confluence.ConfluenceTargetService;
import com.hancom.ai.docpilot.docpilot.web.ProcessingStatusTracker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class ApiSpecInitializationService {

    private static final int MAX_APIS_PER_CONTROLLER = 25;

    @Value("${api-spec.max-controllers:0}")
    private int maxControllers;

    @Value("${api-spec.template-page-title:}")
    private String templatePageTitle;

    /** 서버 시작 시 한 번 읽어서 캐싱하는 서식 템플릿 XML */
    private String cachedTemplateXml;

    private final ConfigLoaderService configLoaderService;
    private final GitLabSourceService gitLabSourceService;
    private final LLMRouter llmRouter;
    private final PromptTemplateService promptTemplateService;
    private final ConfluenceTargetService confluenceTargetService;
    private final ObjectMapper objectMapper;
    private final ControllerPageMappingRepository controllerPageMappingRepository;
    private final ApiPageMappingRepository apiPageMappingRepository;
    private final ProcessingStatusTracker processingStatusTracker;

    public ApiSpecInitializationService(ConfigLoaderService configLoaderService,
                                        GitLabSourceService gitLabSourceService,
                                        LLMRouter llmRouter,
                                        PromptTemplateService promptTemplateService,
                                        ConfluenceTargetService confluenceTargetService,
                                        ObjectMapper objectMapper,
                                        ControllerPageMappingRepository controllerPageMappingRepository,
                                        ApiPageMappingRepository apiPageMappingRepository,
                                        ProcessingStatusTracker processingStatusTracker) {
        this.configLoaderService = configLoaderService;
        this.gitLabSourceService = gitLabSourceService;
        this.llmRouter = llmRouter;
        this.promptTemplateService = promptTemplateService;
        this.confluenceTargetService = confluenceTargetService;
        this.objectMapper = objectMapper;
        this.processingStatusTracker = processingStatusTracker;
        this.controllerPageMappingRepository = controllerPageMappingRepository;
        this.apiPageMappingRepository = apiPageMappingRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(2)
    public void initialize() {
        log.info("=== API 명세서 초기화 시작 ===");

        ConfluenceStructure structure = configLoaderService.getConfluenceStructure();
        String spaceKey = structure.getSpaceKey();

        runSync(spaceKey, structure);

        log.info("=== API 명세서 초기화 완료 ===");
    }

    /**
     * DB와 Confluence 간 동기화를 실행합니다.
     * 서버 시작 시 자동 호출되며, UI에서 수동으로도 호출 가능합니다.
     */
    public void runSync(String spaceKey, ConfluenceStructure structure) {
        // 서식 템플릿 페이지를 한 번 읽어서 캐싱 (UI 생성에서도 사용하므로 항상 로드)
        loadTemplateFormat(spaceKey);

        // DB와 Confluence 간 동기화 (삭제된 페이지 감지)
        validateDbWithConfluence(spaceKey, structure);

        // Confluence에 존재하지만 DB에 없는 Controller를 DB에 동기화
        for (ConfluenceStructure.ProjectMapping project : structure.getProjects()) {
            try {
                syncProjectControllers(spaceKey, project);
            } catch (Exception e) {
                log.debug("프로젝트 동기화 실패: {}", project.getGitlabPath(), e);
            }
        }
    }

    /**
     * UI에서 프로젝트 추가 시 호출. 해당 프로젝트의 API 명세서를 maxControllers만큼 생성합니다.
     */
    public void initializeApiSpecForProject(String spaceKey, ConfluenceStructure.ProjectMapping project, int maxCount) {
        try {
            // 서식 템플릿이 아직 로드되지 않았으면 로드
            if (cachedTemplateXml == null) {
                loadTemplateFormat(spaceKey);
            }
            initializeApiSpec(spaceKey, project, maxCount);
        } catch (Exception e) {
            log.error("프로젝트 API 명세서 초기화 실패: {}", project.getGitlabPath(), e);
        }
    }

    /**
     * 개별 프로젝트의 Confluence → DB 동기화
     */
    private void syncProjectControllers(String spaceKey, ConfluenceStructure.ProjectMapping project) throws Exception {
        Long projectId = project.getGitlabProjectId();
        String prefix = (project.getPageTitlePrefix() != null && !project.getPageTitlePrefix().isBlank())
                ? project.getPageTitlePrefix() + " " : "";
        String apiSpecTitle = prefix + "API 명세서";

        Long parentId = confluenceTargetService.ensureParentPages(spaceKey, project.getConfluenceParentPages());
        Map<String, Object> apiSpecPage = confluenceTargetService.getChildPage(parentId, apiSpecTitle);
        if (apiSpecPage == null) return;

        Long apiSpecPageId = toLong(apiSpecPage.get("id"));

        String defaultBranch = gitLabSourceService.getDefaultBranch(projectId);
        List<String> controllerFiles = gitLabSourceService.findFiles(projectId, defaultBranch, "Controller.java");
        controllerFiles = controllerFiles.stream()
                .filter(f -> !f.contains("ErrorHandler") && !f.contains("CustomError"))
                .filter(f -> f.contains("/controller/"))
                .toList();

        Set<String> dbPaths = controllerPageMappingRepository.findProcessedControllerPaths(projectId);
        Set<String> confluencePaths = getExistingControllerPaths(apiSpecPageId, controllerFiles);

        // Confluence에 있지만 DB에 없는 Controller 동기화
        Set<String> missingInDb = new HashSet<>(confluencePaths);
        missingInDb.removeAll(dbPaths);
        if (!missingInDb.isEmpty()) {
            syncExistingControllersToDb(projectId, apiSpecPageId, confluencePaths, dbPaths);
        }
    }

    /**
     * DB에 처리 완료로 기록된 Controller의 Confluence 페이지가 실제 존재하는지 검증합니다.
     * 삭제된 페이지는 DB에서 미처리 상태로 초기화합니다.
     */
    private void validateDbWithConfluence(String spaceKey, ConfluenceStructure structure) {
        log.info("DB-Confluence 동기화 검증 시작");

        for (ConfluenceStructure.ProjectMapping project : structure.getProjects()) {
            Long projectId = project.getGitlabProjectId();
            List<ControllerPageMappingEntity> mappings = controllerPageMappingRepository.findByGitlabProjectId(projectId);

            if (mappings.isEmpty()) continue;

            // API 명세서 페이지의 실제 자식 페이지 ID 수집
            Set<Long> existingPageIds = new HashSet<>();
            try {
                String prefix = (project.getPageTitlePrefix() != null && !project.getPageTitlePrefix().isBlank())
                        ? project.getPageTitlePrefix() + " " : "";
                String apiSpecTitle = prefix + "API 명세서";

                Long parentId = confluenceTargetService.ensureParentPages(spaceKey, project.getConfluenceParentPages());
                Map<String, Object> apiSpecPage = confluenceTargetService.getChildPage(parentId, apiSpecTitle);

                if (apiSpecPage != null) {
                    Long apiSpecPageId = toLong(apiSpecPage.get("id"));
                    existingPageIds = getChildPageIds(apiSpecPageId);
                }
            } catch (Exception e) {
                log.warn("Confluence 페이지 조회 실패, 동기화 건너뜀: project={}", project.getGitlabPath());
                continue;
            }

            // DB 레코드 검증
            for (ControllerPageMappingEntity mapping : mappings) {
                if (!mapping.isProcessed()) continue;

                if (mapping.getConfluencePageId() == null || !existingPageIds.contains(mapping.getConfluencePageId())) {
                    log.info("Confluence 페이지 삭제 감지, DB 초기화: {} (pageId={})",
                            mapping.getControllerPath(), mapping.getConfluencePageId());

                    // 하위 API 매핑 삭제
                    apiPageMappingRepository.deleteAll(
                            apiPageMappingRepository.findByControllerMappingId(mapping.getId()));

                    // Controller 매핑 삭제
                    controllerPageMappingRepository.delete(mapping);
                }
            }
        }

        log.info("DB-Confluence 동기화 검증 완료");
    }

    /**
     * 특정 페이지의 모든 자식 페이지 ID를 수집합니다.
     */
    @SuppressWarnings("unchecked")
    private Set<Long> getChildPageIds(Long parentId) {
        Set<Long> ids = new HashSet<>();
        try {
            Map<String, Object> response = confluenceTargetService.getChildPagesFull(parentId);
            List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");
            if (results != null) {
                for (Map<String, Object> page : results) {
                    ids.add(toLong(page.get("id")));
                }
            }
        } catch (Exception e) {
            log.debug("자식 페이지 ID 조회 실패: parentId={}", parentId);
        }
        return ids;
    }

    /**
     * Webhook에서 Controller 파일 변경 감지 시 호출됩니다.
     * 변경된 Controller의 API 명세서 페이지를 업데이트합니다.
     */
    public void processControllerUpdate(String spaceKey, ConfluenceStructure.ProjectMapping project,
                                         List<String> controllerPaths, String branch) {
        String titlePrefix = project.getPageTitlePrefix();
        String prefix = (titlePrefix != null && !titlePrefix.isBlank()) ? titlePrefix + " " : "";
        String apiSpecTitle = prefix + "API 명세서";

        try {
            Long parentId = confluenceTargetService.ensureParentPages(spaceKey, project.getConfluenceParentPages());

            Map<String, Object> apiSpecPage = confluenceTargetService.getChildPage(parentId, apiSpecTitle);
            Long apiSpecPageId;

            if (apiSpecPage == null) {
                Map<String, Object> created = confluenceTargetService.createPage(
                        spaceKey, apiSpecTitle, "<p>API 명세서 - 하위 페이지에서 각 API를 확인하세요.</p>", parentId);
                apiSpecPageId = toLong(created.get("id"));
                log.info("API 명세서 페이지 생성: {}", apiSpecTitle);
            } else {
                apiSpecPageId = toLong(apiSpecPage.get("id"));
            }

            for (String controllerPath : controllerPaths) {
                try {
                    processController(spaceKey, project, controllerPath, branch, apiSpecPageId, prefix, true);
                } catch (Exception e) {
                    log.error("Controller 업데이트 실패: {}", controllerPath, e);
                }
            }
        } catch (Exception e) {
            log.error("Controller 업데이트 중 오류: project={}", project.getGitlabPath(), e);
        }
    }

    private void initializeApiSpec(String spaceKey, ConfluenceStructure.ProjectMapping project) throws Exception {
        initializeApiSpec(spaceKey, project, -1);
    }

    private void initializeApiSpec(String spaceKey, ConfluenceStructure.ProjectMapping project, int maxCount) throws Exception {
        String titlePrefix = project.getPageTitlePrefix();
        String prefix = (titlePrefix != null && !titlePrefix.isBlank()) ? titlePrefix + " " : "";
        String apiSpecTitle = prefix + "API 명세서";

        Long projectId = project.getGitlabProjectId();
        String defaultBranch = gitLabSourceService.getDefaultBranch(projectId);

        Long parentId = confluenceTargetService.ensureParentPages(spaceKey, project.getConfluenceParentPages());

        Map<String, Object> apiSpecPage = confluenceTargetService.getChildPage(parentId, apiSpecTitle);
        Long apiSpecPageId;

        if (apiSpecPage == null) {
            Map<String, Object> created = confluenceTargetService.createPage(
                    spaceKey, apiSpecTitle, "<p>API 명세서 - 하위 페이지에서 각 API를 확인하세요.</p>", parentId);
            apiSpecPageId = toLong(created.get("id"));
            log.info("API 명세서 페이지 생성: {}", apiSpecTitle);
        } else {
            apiSpecPageId = toLong(apiSpecPage.get("id"));
            log.info("API 명세서 페이지 이미 존재: {}", apiSpecTitle);
        }

        List<String> controllerFiles = gitLabSourceService.findFiles(projectId, defaultBranch, "Controller.java");

        controllerFiles = controllerFiles.stream()
                .filter(f -> !f.contains("ErrorHandler") && !f.contains("CustomError"))
                .filter(f -> f.contains("/controller/"))
                .toList();

        log.info("Controller 파일 {}개 발견: {}", controllerFiles.size(), project.getGitlabPath());

        // DB에서 이미 처리된 Controller 경로 조회 (DB 우선, 없으면 Confluence 폴백)
        Set<String> dbPaths = controllerPageMappingRepository.findProcessedControllerPaths(projectId);
        Set<String> existingControllerPaths;
        if (!dbPaths.isEmpty()) {
            existingControllerPaths = dbPaths;
        } else {
            existingControllerPaths = getExistingControllerPaths(apiSpecPageId, controllerFiles);
        }
        log.info("이미 처리된 Controller {}개: {}", existingControllerPaths.size(), existingControllerPaths);

        // Confluence에 존재하지만 DB에 없는 Controller를 DB에 동기화
        syncExistingControllersToDb(projectId, apiSpecPageId, existingControllerPaths, dbPaths);

        int processedCount = 0;
        for (String controllerPath : controllerFiles) {
            if (existingControllerPaths.contains(controllerPath)) {
                log.info("Controller 이미 처리됨, skip: {}", controllerPath);
                continue;
            }
            // maxCount 제한 (-1이면 무제한, 양수이면 해당 수만큼, 0이면 생성 안 함)
            if (maxCount == 0) {
                break;
            }
            if (maxCount > 0 && processedCount >= maxCount) {
                log.info("Controller 처리 수 제한({}) 도달, 나머지는 다음 실행에서 처리", maxCount);
                break;
            }
            try {
                processController(spaceKey, project, controllerPath, defaultBranch,
                        apiSpecPageId, prefix, false);
                processedCount++;
            } catch (Exception e) {
                log.error("Controller 처리 실패: {}", controllerPath, e);
            }
        }
        log.info("이번 실행에서 Controller {}개 처리 완료", processedCount);
    }

    /**
     * Controller를 분석하여 API 명세서 페이지를 생성/업데이트합니다.
     *
     * @param forceUpdate true면 기존 페이지도 업데이트 (webhook), false면 기존 페이지 skip (초기화)
     */
    private void processController(String spaceKey, ConfluenceStructure.ProjectMapping project,
                                   String controllerPath, String branch,
                                   Long apiSpecPageId, String prefix, boolean forceUpdate) throws Exception {
        Long projectId = project.getGitlabProjectId();

        processingStatusTracker.markProcessing(controllerPath);
        try {
            processControllerInternal(spaceKey, project, controllerPath, branch, apiSpecPageId, prefix, forceUpdate);
        } finally {
            processingStatusTracker.markDone(controllerPath);
        }
    }

    private void processControllerInternal(String spaceKey, ConfluenceStructure.ProjectMapping project,
                                            String controllerPath, String branch,
                                            Long apiSpecPageId, String prefix, boolean forceUpdate) throws Exception {
        Long projectId = project.getGitlabProjectId();

        String code = gitLabSourceService.getFileContent(projectId, controllerPath, branch);
        log.info("Controller 분석 시작: {}", controllerPath);

        // Controller 소스에서 API 시그니처만 추출 (함수 본문, private 메서드 제거)
        String apiSignatures = extractApiSignatures(code);
        log.debug("Controller 시그니처 추출 결과:\n{}", apiSignatures);

        // LLM으로 Controller 분석 → JSON
        String analysisPrompt = promptTemplateService.getPrompt(
                "controller_analysis", apiSignatures, project.getGitlabPath());
        String analysisResult = llmRouter.generate(analysisPrompt);
        log.debug("Controller 분석 LLM 응답:\n{}", analysisResult);
        analysisResult = extractJson(analysisResult);

        JsonNode analysis;
        try {
            analysis = objectMapper.readTree(analysisResult);
        } catch (Exception e) {
            log.warn("Controller 분석 JSON 파싱 실패, skip: {} ({})", controllerPath, e.getMessage());
            return;
        }

        JsonNode controllerNameNode = analysis.get("controllerKoreanName");
        if (controllerNameNode == null) {
            log.warn("controllerKoreanName 없음, skip: {}", controllerPath);
            return;
        }

        String controllerKoreanName = controllerNameNode.asText();
        JsonNode apis = analysis.get("apis");

        String controllerPageTitle = prefix + controllerKoreanName;

        // Controller 그룹 페이지 본문 생성 (Controller 정보 + API 목록 + 하위 페이지 목차)
        String controllerPageBody = buildControllerPageBody(
                controllerKoreanName, controllerPath, apis, prefix);

        // Controller 그룹 페이지 확인/생성 (controller-path로 기존 페이지 검색)
        Map<String, Object> existingPage = findPageByControllerPath(apiSpecPageId, controllerPath);
        Long controllerPageId;

        if (existingPage == null) {
            // 신규 생성
            try {
                Map<String, Object> created = confluenceTargetService.createPage(
                        spaceKey, controllerPageTitle, controllerPageBody, apiSpecPageId);
                controllerPageId = toLong(created.get("id"));
                log.info("Controller 페이지 생성: {}", controllerPageTitle);
            } catch (Exception e) {
                // 동일 제목 페이지가 이미 존재 → 기존 페이지를 찾아서 업데이트
                log.warn("Controller 페이지 생성 실패, 기존 페이지 검색: {} ({})", controllerPageTitle, e.getMessage());
                Map<String, Object> existingByTitle = confluenceTargetService.getPage(spaceKey, controllerPageTitle);
                if (existingByTitle != null) {
                    controllerPageId = toLong(existingByTitle.get("id"));
                    @SuppressWarnings("unchecked")
                    Map<String, Object> vObj = (Map<String, Object>) existingByTitle.get("version");
                    int ver = ((Number) vObj.get("number")).intValue();
                    confluenceTargetService.updatePage(controllerPageId, controllerPageTitle, controllerPageBody, ver + 1);
                    log.info("Controller 페이지 기존 업데이트: {}", controllerPageTitle);
                } else {
                    throw e;
                }
            }
        } else if (!forceUpdate) {
            log.info("Controller 페이지 이미 존재, 하위 API 처리 skip: {}", controllerPageTitle);
            return;
        } else {
            // Webhook 업데이트 모드: 기존 페이지 내용 업데이트 후 하위 API도 처리
            controllerPageId = toLong(existingPage.get("id"));
            @SuppressWarnings("unchecked")
            Map<String, Object> versionObj = (Map<String, Object>) existingPage.get("version");
            int currentVersion = ((Number) versionObj.get("number")).intValue();
            confluenceTargetService.updatePage(controllerPageId, controllerPageTitle, controllerPageBody, currentVersion + 1);
            log.info("Controller 페이지 업데이트: {}", controllerPageTitle);
        }

        // DB에 Controller 매핑 저장 (API 처리 전에 먼저 저장하여 ID 확보)
        ControllerPageMappingEntity controllerMapping = controllerPageMappingRepository
                .findByGitlabProjectIdAndControllerPath(projectId, controllerPath)
                .orElse(ControllerPageMappingEntity.builder()
                        .gitlabProjectId(projectId)
                        .controllerPath(controllerPath)
                        .build());
        controllerMapping.setControllerKoreanName(controllerKoreanName);
        controllerMapping.setConfluencePageId(controllerPageId);
        controllerMapping.setConfluencePageTitle(controllerPageTitle);
        controllerMapping.setLastUpdated(LocalDateTime.now());
        controllerMapping = controllerPageMappingRepository.save(controllerMapping);

        // 재생성 시 기존 API 매핑 초기화
        if (forceUpdate) {
            apiPageMappingRepository.deleteAll(
                    apiPageMappingRepository.findByControllerMappingId(controllerMapping.getId()));
            log.debug("기존 API 매핑 초기화: controllerId={}", controllerMapping.getId());
        }

        // 각 API 엔드포인트 페이지 생성/업데이트 (최대 25개)
        int apiCount = 0;
        if (apis != null && apis.isArray()) {
            for (JsonNode api : apis) {
                if (apiCount >= MAX_APIS_PER_CONTROLLER) {
                    log.info("API 최대 처리 수({}) 도달, 나머지 skip: {}", MAX_APIS_PER_CONTROLLER, controllerPath);
                    break;
                }
                try {
                    processApiEndpoint(spaceKey, project, code, api,
                            controllerPageId, prefix, forceUpdate, controllerMapping);
                    apiCount++;
                } catch (Exception e) {
                    log.warn("API 엔드포인트 처리 실패, skip: {} ({})", api.toString(), e.getMessage());
                }
            }
        }

        controllerMapping.setApiCount(apiCount);
        controllerMapping.setProcessed(true);
        controllerPageMappingRepository.save(controllerMapping);
    }

    private void processApiEndpoint(String spaceKey, ConfluenceStructure.ProjectMapping project,
                                    String controllerCode, JsonNode api,
                                    Long controllerPageId, String prefix, boolean forceUpdate,
                                    ControllerPageMappingEntity controllerMapping) throws Exception {
        JsonNode koreanNameNode = api.get("koreanName");
        if (koreanNameNode == null) {
            log.warn("API koreanName 없음, skip: {}", api.toString());
            return;
        }

        String koreanName = koreanNameNode.asText();
        String apiPageTitle = prefix + "API - " + koreanName;

        String methodName = api.has("methodName") ? api.get("methodName").asText() : "";

        // 기존 페이지 확인
        Map<String, Object> existing = confluenceTargetService.getChildPage(controllerPageId, apiPageTitle);

        if (existing != null && !forceUpdate) {
            log.info("API 페이지 이미 존재, skip: {}", apiPageTitle);
            return;
        }

        // Controller에서 해당 메서드 코드만 추출
        String methodCode = extractMethodCode(controllerCode, methodName);

        // 참조 DTO/Bean 클래스 소스코드 수집 (현재 프로젝트 + 의존 프로젝트 + 공통 라이브러리)
        List<Long> allDependencies = new ArrayList<>();
        if (project.getDependencyProjects() != null) {
            allDependencies.addAll(project.getDependencyProjects());
        }
        List<ConfluenceStructure.CommonLibrary> commonLibs = configLoaderService.getConfluenceStructure().getCommonLibraries();
        if (commonLibs != null) {
            for (ConfluenceStructure.CommonLibrary lib : commonLibs) {
                if (!allDependencies.contains(lib.getGitlabProjectId())) {
                    allDependencies.add(lib.getGitlabProjectId());
                }
            }
        }
        String dtoSources = collectReferencedDtoSources(
                project.getGitlabProjectId(), controllerCode, methodCode,
                allDependencies.isEmpty() ? null : allDependencies);

        String method = api.has("method") ? api.get("method").asText() : "";
        String path = api.has("path") ? api.get("path").asText() : "";

        // Map 파라미터의 key 정보 추출
        String mapKeyHints = extractMapKeyHints(methodCode);

        StringBuilder apiCodeBuilder = new StringBuilder();
        apiCodeBuilder.append(String.format(
                "대상 API: %s %s (메서드명: %s, 한국어명: %s)\n\n", method, path, methodName, koreanName));
        apiCodeBuilder.append("=== Controller 메서드 코드 ===\n").append(methodCode);
        if (!dtoSources.isEmpty()) {
            apiCodeBuilder.append("\n\n=== 참조 DTO/Bean 클래스 (Request/Response 필드 정보) ===\n");
            apiCodeBuilder.append(dtoSources);
        }
        if (!mapKeyHints.isEmpty()) {
            apiCodeBuilder.append("\n\n=== Map 파라미터 key 분석 결과 ===\n");
            apiCodeBuilder.append(mapKeyHints);
        }
        String apiCode = apiCodeBuilder.toString();

        String detailPrompt;
        if (cachedTemplateXml != null) {
            detailPrompt = promptTemplateService.getPromptWithTemplate(
                    apiCode, project.getGitlabPath(), cachedTemplateXml);
        } else {
            detailPrompt = promptTemplateService.getPrompt(
                    "api_detail", apiCode, project.getGitlabPath());
        }
        String detailDoc = cleanLlmResponse(llmRouter.generate(detailPrompt));

        Long apiConfluencePageId;
        if (existing != null) {
            // 기존 페이지 업데이트
            apiConfluencePageId = toLong(existing.get("id"));
            @SuppressWarnings("unchecked")
            Map<String, Object> versionObj = (Map<String, Object>) existing.get("version");
            int currentVersion = ((Number) versionObj.get("number")).intValue();
            confluenceTargetService.updatePage(apiConfluencePageId, apiPageTitle, detailDoc, currentVersion + 1);
            confluenceTargetService.setPageWidthNarrow(apiConfluencePageId);
            log.info("API 페이지 업데이트: {}", apiPageTitle);
        } else {
            try {
                Map<String, Object> created = confluenceTargetService.createPage(spaceKey, apiPageTitle, detailDoc, controllerPageId);
                apiConfluencePageId = toLong(created.get("id"));
                confluenceTargetService.setPageWidthNarrow(apiConfluencePageId);
                log.info("API 페이지 생성: {}", apiPageTitle);
            } catch (Exception e) {
                // 동일 제목 페이지가 이미 존재하는 경우 (다른 Controller에서 같은 이름 생성)
                // 스페이스 전체에서 검색하여 업데이트 시도
                log.warn("API 페이지 생성 실패, 기존 페이지 검색: {} ({})", apiPageTitle, e.getMessage());
                Map<String, Object> existingByTitle = confluenceTargetService.getPage(spaceKey, apiPageTitle);
                if (existingByTitle != null) {
                    apiConfluencePageId = toLong(existingByTitle.get("id"));
                    @SuppressWarnings("unchecked")
                    Map<String, Object> vObj = (Map<String, Object>) existingByTitle.get("version");
                    int ver = ((Number) vObj.get("number")).intValue();
                    confluenceTargetService.updatePage(apiConfluencePageId, apiPageTitle, detailDoc, ver + 1);
                    confluenceTargetService.setPageWidthNarrow(apiConfluencePageId);
                    log.info("API 페이지 기존 업데이트: {}", apiPageTitle);
                } else {
                    throw e;
                }
            }
        }

        // DB에 API 매핑 저장
        ApiPageMappingEntity apiMapping = apiPageMappingRepository
                .findByControllerMappingIdAndMethodName(controllerMapping.getId(), methodName)
                .orElse(ApiPageMappingEntity.builder()
                        .controllerMapping(controllerMapping)
                        .methodName(methodName)
                        .build());
        apiMapping.setHttpMethod(method);
        apiMapping.setPath(path);
        apiMapping.setKoreanName(koreanName);
        apiMapping.setConfluencePageId(apiConfluencePageId);
        apiMapping.setConfluencePageTitle(apiPageTitle);
        apiMapping.setLastUpdated(LocalDateTime.now());
        apiPageMappingRepository.save(apiMapping);
    }

    /**
     * Controller 그룹 페이지의 본문을 생성합니다.
     * Controller 파일 경로, API 목록 테이블, 하위 페이지 목차를 포함합니다.
     */
    /**
     * API 명세서 하위의 기존 Controller 그룹 페이지들에서 controller-path를 추출합니다.
     * 서버 시작 시 한 번 호출하여, 이미 처리된 Controller를 판별합니다.
     */
    /**
     * API 명세서 하위 페이지에서 이미 처리된 Controller 파일 경로를 추출합니다.
     * 1순위: <!-- controller-path:xxx --> 식별자 (신규 생성 페이지)
     * 2순위: 본문에 Controller 파일 경로 텍스트가 포함되어 있는지 (기존 페이지 호환)
     */
    /**
     * Confluence에 존재하지만 DB에 없는 Controller 정보를 DB에 동기화합니다.
     * 서버 최초 시작 시 또는 DB 초기화 후 기존 Confluence 페이지를 DB에 반영합니다.
     */
    @SuppressWarnings("unchecked")
    private void syncExistingControllersToDb(Long projectId, Long apiSpecPageId,
                                              Set<String> existingPaths, Set<String> dbPaths) {
        // DB에 이미 모든 경로가 있으면 동기화 불필요
        Set<String> missingInDb = new HashSet<>(existingPaths);
        missingInDb.removeAll(dbPaths);
        if (missingInDb.isEmpty()) return;

        log.info("Confluence → DB 동기화 시작: {}개 Controller", missingInDb.size());

        try {
            Map<String, Object> response = confluenceTargetService.getChildPagesFull(apiSpecPageId);
            List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");
            if (results == null) return;

            for (Map<String, Object> page : results) {
                Map<String, Object> body = (Map<String, Object>) page.get("body");
                if (body == null) continue;
                Map<String, Object> storage = (Map<String, Object>) body.get("storage");
                if (storage == null) continue;
                String content = (String) storage.get("value");
                if (content == null) continue;

                // 이 페이지가 어떤 Controller에 해당하는지 확인
                String matchedPath = null;
                Matcher matcher = CONTROLLER_PATH_PATTERN.matcher(content);
                if (matcher.find()) {
                    matchedPath = matcher.group(1);
                } else {
                    for (String path : missingInDb) {
                        if (content.contains(path)) {
                            matchedPath = path;
                            break;
                        }
                    }
                }

                if (matchedPath == null || !missingInDb.contains(matchedPath)) continue;

                Long pageId = toLong(page.get("id"));
                String pageTitle = (String) page.get("title");

                // 하위 API 페이지 수 조회
                int apiCount = 0;
                try {
                    Map<String, Object> children = confluenceTargetService.getChildPagesFull(pageId);
                    List<Map<String, Object>> childResults = (List<Map<String, Object>>) children.get("results");
                    if (childResults != null) {
                        apiCount = childResults.size();

                        // API 페이지 매핑도 DB에 저장
                        ControllerPageMappingEntity mapping = controllerPageMappingRepository
                                .findByGitlabProjectIdAndControllerPath(projectId, matchedPath)
                                .orElse(ControllerPageMappingEntity.builder()
                                        .gitlabProjectId(projectId)
                                        .controllerPath(matchedPath)
                                        .build());
                        mapping.setConfluencePageId(pageId);
                        mapping.setConfluencePageTitle(pageTitle);
                        mapping.setProcessed(true);
                        mapping.setApiCount(apiCount);
                        mapping.setLastUpdated(LocalDateTime.now());
                        mapping = controllerPageMappingRepository.save(mapping);

                        // 하위 API 페이지도 DB에 저장
                        for (Map<String, Object> child : childResults) {
                            String childTitle = (String) child.get("title");
                            Long childPageId = toLong(child.get("id"));

                            // 제목에서 메서드명 추출은 어려우므로 제목 기반으로 저장
                            boolean exists = apiPageMappingRepository
                                    .findByControllerMappingId(mapping.getId()).stream()
                                    .anyMatch(a -> childPageId.equals(a.getConfluencePageId()));
                            if (!exists) {
                                apiPageMappingRepository.save(ApiPageMappingEntity.builder()
                                        .controllerMapping(mapping)
                                        .methodName(childTitle)
                                        .confluencePageId(childPageId)
                                        .confluencePageTitle(childTitle)
                                        .lastUpdated(LocalDateTime.now())
                                        .build());
                            }
                        }

                        log.info("DB 동기화 완료: {} (API {}개)", matchedPath, apiCount);
                    }
                } catch (Exception e) {
                    // API 수 조회 실패해도 Controller 매핑은 저장
                    ControllerPageMappingEntity mapping = controllerPageMappingRepository
                            .findByGitlabProjectIdAndControllerPath(projectId, matchedPath)
                            .orElse(ControllerPageMappingEntity.builder()
                                    .gitlabProjectId(projectId)
                                    .controllerPath(matchedPath)
                                    .build());
                    mapping.setConfluencePageId(pageId);
                    mapping.setConfluencePageTitle(pageTitle);
                    mapping.setProcessed(true);
                    mapping.setLastUpdated(LocalDateTime.now());
                    controllerPageMappingRepository.save(mapping);
                    log.debug("API 하위 페이지 조회 실패, Controller만 동기화: {}", matchedPath);
                }

                missingInDb.remove(matchedPath);
            }
        } catch (Exception e) {
            log.warn("Confluence → DB 동기화 실패: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public Set<String> getExistingControllerPaths(Long apiSpecPageId) {
        return getExistingControllerPaths(apiSpecPageId, null);
    }

    @SuppressWarnings("unchecked")
    public Set<String> getExistingControllerPaths(Long apiSpecPageId, List<String> knownControllerPaths) {
        Set<String> paths = new HashSet<>();
        try {
            Map<String, Object> response = confluenceTargetService.getChildPagesFull(apiSpecPageId);
            List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");
            if (results == null) return paths;

            for (Map<String, Object> page : results) {
                Map<String, Object> body = (Map<String, Object>) page.get("body");
                if (body == null) continue;
                Map<String, Object> storage = (Map<String, Object>) body.get("storage");
                if (storage == null) continue;
                String content = (String) storage.get("value");
                if (content == null) continue;

                // 1순위: controller-path 식별자
                Matcher matcher = CONTROLLER_PATH_PATTERN.matcher(content);
                if (matcher.find()) {
                    paths.add(matcher.group(1));
                    continue;
                }

                // 2순위: 본문에 Controller 파일 경로가 포함되어 있는지 (기존 페이지 호환)
                if (knownControllerPaths != null) {
                    for (String controllerPath : knownControllerPaths) {
                        if (content.contains(controllerPath)) {
                            paths.add(controllerPath);
                            log.debug("기존 페이지에서 Controller 경로 감지 (폴백): {}", controllerPath);
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("기존 Controller 페이지 조회 실패: {}", e.getMessage());
        }
        return paths;
    }

    /**
     * API 명세서 하위에서 특정 controller-path를 가진 페이지를 찾습니다.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> findPageByControllerPath(Long parentId, String controllerPath) {
        try {
            Map<String, Object> response = confluenceTargetService.getChildPagesFull(parentId);
            List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");
            if (results == null) return null;

            String searchTag = CONTROLLER_PATH_PREFIX + controllerPath + CONTROLLER_PATH_SUFFIX;
            for (Map<String, Object> page : results) {
                Map<String, Object> body = (Map<String, Object>) page.get("body");
                if (body == null) continue;
                Map<String, Object> storage = (Map<String, Object>) body.get("storage");
                if (storage == null) continue;
                String content = (String) storage.get("value");
                if (content != null && content.contains(searchTag)) {
                    return page;
                }
            }
        } catch (Exception e) {
            log.warn("Controller 페이지 검색 실패: {}", e.getMessage());
        }
        return null;
    }

    private static final String CONTROLLER_PATH_PREFIX = "<!-- controller-path:";
    private static final String CONTROLLER_PATH_SUFFIX = " -->";
    private static final Pattern CONTROLLER_PATH_PATTERN =
            Pattern.compile("<!-- controller-path:(.+?) -->");

    private String buildControllerPageBody(String controllerKoreanName, String controllerPath,
                                            JsonNode apis, String prefix) {
        StringBuilder sb = new StringBuilder();

        // Controller 파일 경로 식별자 (중복 판별용)
        sb.append(CONTROLLER_PATH_PREFIX).append(controllerPath).append(CONTROLLER_PATH_SUFFIX).append("\n");

        // Controller 정보
        sb.append("<h2>Controller 정보</h2>");
        sb.append("<table><tbody>");
        sb.append("<tr><th>항목</th><th>내용</th></tr>");
        sb.append("<tr><td>Controller 이름</td><td>").append(controllerKoreanName).append("</td></tr>");
        sb.append("<tr><td>파일 경로</td><td>").append(controllerPath).append("</td></tr>");
        sb.append("</tbody></table>");

        // API 목록 테이블
        sb.append("<h2>API 목록</h2>");
        if (apis != null && apis.isArray() && apis.size() > 0) {
            sb.append("<table><tbody>");
            sb.append("<tr><th>메서드</th><th>경로</th><th>API명</th></tr>");

            int count = 0;
            for (JsonNode api : apis) {
                if (count >= MAX_APIS_PER_CONTROLLER) break;
                String method = api.has("method") ? api.get("method").asText() : "";
                String path = api.has("path") ? api.get("path").asText() : "";
                String koreanName = api.has("koreanName") ? api.get("koreanName").asText() : "";
                sb.append("<tr>");
                sb.append("<td>").append(method).append("</td>");
                sb.append("<td>").append(path).append("</td>");
                sb.append("<td>").append(koreanName).append("</td>");
                sb.append("</tr>");
                count++;
            }
            sb.append("</tbody></table>");
        } else {
            sb.append("<p>API 엔드포인트가 없습니다.</p>");
        }

        // 하위 페이지 목차 매크로
        sb.append("<h2>API 상세 명세</h2>");
        sb.append("<ac:structured-macro ac:name=\"children\">");
        sb.append("<ac:parameter ac:name=\"sort\">title</ac:parameter>");
        sb.append("<ac:parameter ac:name=\"all\">true</ac:parameter>");
        sb.append("</ac:structured-macro>");

        return sb.toString();
    }

    /**
     * Confluence에서 서식 템플릿 페이지의 Storage Format XML을 읽어 캐싱합니다.
     * 서버 시작 시 한 번만 호출됩니다.
     */
    @SuppressWarnings("unchecked")
    private void loadTemplateFormat(String spaceKey) {
        if (templatePageTitle == null || templatePageTitle.isBlank()) {
            log.info("서식 템플릿 페이지 미설정, 기본 프롬프트 사용");
            cachedTemplateXml = null;
            return;
        }

        try {
            Map<String, Object> page = confluenceTargetService.getPage(spaceKey, templatePageTitle);
            if (page == null) {
                log.warn("서식 템플릿 페이지를 찾을 수 없음: '{}'", templatePageTitle);
                cachedTemplateXml = null;
                return;
            }

            Map<String, Object> body = (Map<String, Object>) page.get("body");
            Map<String, Object> storage = (Map<String, Object>) body.get("storage");
            cachedTemplateXml = (String) storage.get("value");

            log.info("서식 템플릿 로드 완료: '{}' ({}자)", templatePageTitle, cachedTemplateXml.length());
        } catch (Exception e) {
            log.error("서식 템플릿 로드 실패: '{}'", templatePageTitle, e);
            cachedTemplateXml = null;
        }
    }

    /**
     * Controller 소스에서 API 분석에 필요한 시그니처만 추출합니다.
     * - 클래스 레벨 어노테이션 (@RestController, @Controller, @RequestMapping)
     * - public 메서드의 어노테이션 + 시그니처 (함수 본문은 ... 으로 대체)
     * - private/protected 메서드는 제거
     */
    String extractApiSignatures(String controllerCode) {
        String[] lines = controllerCode.split("\n");
        StringBuilder sb = new StringBuilder();

        boolean inMethod = false;
        boolean isPublicMethod = false;
        int braceCount = 0;
        boolean methodBraceStarted = false;

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();

            // 메서드 본문 내부 — skip
            if (inMethod) {
                for (char c : lines[i].toCharArray()) {
                    if (c == '{') braceCount++;
                    else if (c == '}') braceCount--;
                }
                if (methodBraceStarted && braceCount == 0) {
                    if (isPublicMethod) {
                        sb.append("        ...\n    }\n\n");
                    }
                    inMethod = false;
                    methodBraceStarted = false;
                }
                continue;
            }

            // 클래스 레벨 어노테이션
            if (trimmed.startsWith("@RestController") || trimmed.startsWith("@Controller")
                    || trimmed.startsWith("@RequestMapping") || trimmed.startsWith("@CrossOrigin")) {
                sb.append(lines[i]).append("\n");
                continue;
            }

            // 클래스 선언
            if (trimmed.startsWith("public class ") || trimmed.startsWith("public abstract class ")) {
                sb.append(lines[i]).append("\n\n");
                continue;
            }

            // 매핑 어노테이션 (API 메서드 시작 신호)
            if (trimmed.startsWith("@GetMapping") || trimmed.startsWith("@PostMapping")
                    || trimmed.startsWith("@PutMapping") || trimmed.startsWith("@DeleteMapping")
                    || trimmed.startsWith("@PatchMapping") || trimmed.startsWith("@ResponseBody")
                    || (trimmed.startsWith("@RequestMapping") && !trimmed.contains("class "))) {
                sb.append(lines[i]).append("\n");
                continue;
            }

            // 메서드 시그니처 감지 (public 또는 public @ResponseBody 패턴)
            if ((trimmed.startsWith("public ") || trimmed.startsWith("public @")) && trimmed.contains("(")) {
                isPublicMethod = true;
                sb.append(lines[i]).append("\n");

                // 여는 중괄호 찾기
                inMethod = true;
                braceCount = 0;
                methodBraceStarted = false;
                for (char c : lines[i].toCharArray()) {
                    if (c == '{') { braceCount++; methodBraceStarted = true; }
                    else if (c == '}') braceCount--;
                }
                // 같은 줄에 { 가 없으면 다음 줄들에서 찾기
                if (!methodBraceStarted) {
                    // 파라미터가 여러 줄에 걸친 경우 — 시그니처 끝까지 출력
                    for (int j = i + 1; j < lines.length; j++) {
                        sb.append(lines[j]).append("\n");
                        for (char c : lines[j].toCharArray()) {
                            if (c == '{') { braceCount++; methodBraceStarted = true; }
                            else if (c == '}') braceCount--;
                        }
                        if (methodBraceStarted) {
                            i = j;
                            break;
                        }
                    }
                }

                if (methodBraceStarted && braceCount == 0) {
                    // 한 줄짜리 메서드
                    sb.append("\n");
                    inMethod = false;
                    methodBraceStarted = false;
                }
                continue;
            }

            // private/protected 메서드 — 본문 전체 skip
            // 메서드: { 또는 }로 끝남. 필드 선언: ;로 끝남
            if ((trimmed.startsWith("private ") || trimmed.startsWith("protected ")) && trimmed.contains("(")
                    && (trimmed.endsWith("{") || trimmed.endsWith("}"))) {
                isPublicMethod = false;
                inMethod = true;
                braceCount = 0;
                methodBraceStarted = false;
                for (char c : lines[i].toCharArray()) {
                    if (c == '{') { braceCount++; methodBraceStarted = true; }
                    else if (c == '}') braceCount--;
                }
                if (!methodBraceStarted) {
                    for (int j = i + 1; j < lines.length; j++) {
                        for (char c : lines[j].toCharArray()) {
                            if (c == '{') { braceCount++; methodBraceStarted = true; }
                            else if (c == '}') braceCount--;
                        }
                        if (methodBraceStarted) { i = j; break; }
                    }
                }
                if (methodBraceStarted && braceCount == 0) {
                    inMethod = false;
                    methodBraceStarted = false;
                }
                continue;
            }
        }

        String result = sb.toString().trim();
        return result.isEmpty() ? controllerCode : result;
    }

    /**
     * Controller 소스코드에서 특정 메서드의 코드를 추출합니다.
     * 클래스 레벨 @RequestMapping과 해당 메서드의 어노테이션 + 본문을 반환합니다.
     */

    /**
     * Map 파라미터에서 .get("key") 패턴을 추출하여 key 이름과 캐스팅 타입을 분석합니다.
     * 예: (String) map.get("name") → name: String
     *     (Long) map.get("id")    → id: Long
     *     map.get("data")         → data: Object
     */
    private String extractMapKeyHints(String methodCode) {
        // Map 파라미터가 있는지 확인
        if (!methodCode.contains("Map<String,") && !methodCode.contains("Map<String ,")) {
            return "";
        }

        // Map 변수명 추출 (예: @RequestBody Map<String, Object> chatbotMap → chatbotMap)
        Pattern mapParamPattern = Pattern.compile("Map\\s*<\\s*String\\s*,\\s*\\w+\\s*>\\s+(\\w+)");
        Matcher mapParamMatcher = mapParamPattern.matcher(methodCode);

        Map<String, List<String>> mapKeyInfos = new HashMap<>();

        while (mapParamMatcher.find()) {
            String mapVarName = mapParamMatcher.group(1);

            // 해당 변수의 .get("key") 호출을 모두 찾기
            // 패턴: (CastType) varName.get("key") 또는 varName.get("key")
            Pattern getPattern = Pattern.compile(
                    "(?:\\(\\s*([A-Z]\\w+)\\s*\\)\\s*)?" + Pattern.quote(mapVarName) + "\\.get\\(\\s*\"([^\"]+)\"\\s*\\)");
            Matcher getMatcher = getPattern.matcher(methodCode);

            List<String> keys = new ArrayList<>();
            while (getMatcher.find()) {
                String castType = getMatcher.group(1);
                String key = getMatcher.group(2);
                if (castType != null) {
                    keys.add(key + " (" + castType + ")");
                } else {
                    keys.add(key + " (Object)");
                }
            }

            if (!keys.isEmpty()) {
                mapKeyInfos.put(mapVarName, keys);
            }
        }

        if (mapKeyInfos.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("@RequestBody Map 파라미터의 실제 사용 key 목록입니다. Request Parameter 작성 시 이 필드들을 포함하세요:\n");
        for (Map.Entry<String, List<String>> entry : mapKeyInfos.entrySet()) {
            sb.append("변수명: ").append(entry.getKey()).append("\n");
            for (String keyInfo : entry.getValue()) {
                sb.append("  - ").append(keyInfo).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Controller 메서드에서 참조하는 DTO/Bean 클래스의 소스코드를 수집합니다.
     * @RequestBody, @ModelAttribute 파라미터 타입과 반환 타입의 커스텀 클래스를 탐색합니다.
     */
    private String collectReferencedDtoSources(Long projectId, String controllerCode, String methodCode) {
        return collectReferencedDtoSources(projectId, controllerCode, methodCode, null);
    }

    private String collectReferencedDtoSources(Long projectId, String controllerCode, String methodCode,
                                                List<Long> dependencyProjectIds) {
        Set<String> dtoClassNames = new HashSet<>();

        // 1. @RequestBody, @ModelAttribute 파라미터 타입 추출
        Pattern paramPattern = Pattern.compile("@(?:RequestBody|ModelAttribute)\\s+(?:\\w+\\s+)?([A-Z]\\w+)");
        Matcher paramMatcher = paramPattern.matcher(methodCode);
        while (paramMatcher.find()) {
            dtoClassNames.add(paramMatcher.group(1));
        }

        // 2. @RequestParam, @PathVariable 뒤의 커스텀 타입
        Pattern reqParamPattern = Pattern.compile("@(?:RequestParam|PathVariable)[^)]*\\)\\s+([A-Z]\\w+)");
        Matcher reqParamMatcher = reqParamPattern.matcher(methodCode);
        while (reqParamMatcher.find()) {
            dtoClassNames.add(reqParamMatcher.group(1));
        }

        // 3. 반환 타입 추출
        Pattern returnPattern = Pattern.compile("public\\s+(?:@\\w+\\s+)?([A-Z][\\w<>,\\s?]+?)\\s+\\w+\\(");
        Matcher returnMatcher = returnPattern.matcher(methodCode);
        if (returnMatcher.find()) {
            String returnSignature = returnMatcher.group(1);
            Pattern typeInReturn = Pattern.compile("([A-Z][a-zA-Z0-9]+)");
            Matcher typeInReturnMatcher = typeInReturn.matcher(returnSignature);
            while (typeInReturnMatcher.find()) {
                dtoClassNames.add(typeInReturnMatcher.group(1));
            }
        }

        // 4. 제네릭 타입 추출
        Pattern genericPattern = Pattern.compile("<[^>]*?([A-Z][a-zA-Z0-9]+)");
        Matcher genericMatcher = genericPattern.matcher(methodCode);
        while (genericMatcher.find()) {
            dtoClassNames.add(genericMatcher.group(1));
        }

        // import에서 DTO 클래스의 패키지 경로 확인
        Map<String, String> importMap = new HashMap<>();
        Pattern importPattern = Pattern.compile("import\\s+([\\w.]+\\.([A-Z]\\w+))\\s*;");
        Matcher importMatcher = importPattern.matcher(controllerCode);
        while (importMatcher.find()) {
            importMap.put(importMatcher.group(2), importMatcher.group(1));
        }

        // 기본/프레임워크 타입 제거
        dtoClassNames.removeIf(this::isJavaBuiltinType);
        if (dtoClassNames.isEmpty()) return "";

        // 검색 대상 프로젝트 ID 목록 (현재 프로젝트 + 의존 프로젝트)
        List<Long> searchProjectIds = new ArrayList<>();
        searchProjectIds.add(projectId);
        if (dependencyProjectIds != null) {
            searchProjectIds.addAll(dependencyProjectIds);
        }

        StringBuilder sb = new StringBuilder();
        Set<String> notFoundDtos = new HashSet<>();

        for (String className : dtoClassNames) {
            boolean found = false;

            for (Long searchProjectId : searchProjectIds) {
                try {
                    String branch = gitLabSourceService.getDefaultBranch(searchProjectId);
                    String dtoSource = findAndReadDtoSource(searchProjectId, branch, className, importMap);
                    if (dtoSource != null) {
                        sb.append("--- ").append(className).append(".java ---\n");
                        sb.append(dtoSource).append("\n\n");
                        collectNestedDtos(searchProjectId, branch, dtoSource, importMap, sb,
                                dtoClassNames, searchProjectIds);
                        found = true;
                        break;
                    }
                } catch (Exception e) {
                    log.debug("DTO 소스 조회 실패: {} (project={}, {})", className, searchProjectId, e.getMessage());
                }
            }

            if (!found) {
                notFoundDtos.add(className);
                log.info("DTO 소스 미발견: {} (프로젝트 및 의존 라이브러리에서 찾을 수 없음)", className);
            }
        }

        // 못 찾은 DTO에 대한 경고 추가
        if (!notFoundDtos.isEmpty()) {
            sb.append("\n=== 주의: 아래 DTO/클래스의 소스를 찾지 못했습니다 ===\n");
            sb.append("LLM이 코드 컨텍스트에서 필드를 유추하여 작성하세요.\n");
            sb.append("명세서에 해당 DTO의 필드 정보가 부정확할 수 있음을 표기하세요.\n");
            for (String dto : notFoundDtos) {
                sb.append("  - ").append(dto).append("\n");
            }
        }

        return sb.toString();
    }

    private String findAndReadDtoSource(Long projectId, String branch, String className,
                                         Map<String, String> importMap) throws Exception {
        // import에서 패키지 경로를 알면 직접 접근
        String fqcn = importMap.get(className);
        if (fqcn != null) {
            String filePath = "src/main/java/" + fqcn.replace('.', '/') + ".java";
            try {
                return gitLabSourceService.getFileContent(projectId, filePath, branch);
            } catch (Exception ignored) {}
        }

        // 파일명 검색으로 폴백
        List<String> files = gitLabSourceService.findFiles(projectId, branch, className + ".java");
        if (!files.isEmpty()) {
            return gitLabSourceService.getFileContent(projectId, files.get(0), branch);
        }
        return null;
    }

    private void collectNestedDtos(Long projectId, String branch, String dtoSource,
                                    Map<String, String> importMap, StringBuilder sb,
                                    Set<String> alreadyCollected, List<Long> searchProjectIds) {
        Pattern fieldPattern = Pattern.compile("private\\s+(?:List<|Set<)?([A-Z]\\w+)(?:>)?\\s+\\w+");
        Matcher fieldMatcher = fieldPattern.matcher(dtoSource);
        while (fieldMatcher.find()) {
            String nestedType = fieldMatcher.group(1);
            if (!alreadyCollected.contains(nestedType) && !isJavaBuiltinType(nestedType)) {
                alreadyCollected.add(nestedType);
                for (Long searchId : searchProjectIds) {
                    try {
                        String searchBranch = gitLabSourceService.getDefaultBranch(searchId);
                        String nestedSource = findAndReadDtoSource(searchId, searchBranch, nestedType, importMap);
                        if (nestedSource != null) {
                            sb.append("--- ").append(nestedType).append(".java ---\n");
                            sb.append(nestedSource).append("\n\n");
                            break;
                        }
                    } catch (Exception e) {
                        log.debug("중첩 DTO 소스 조회 실패: {} (project={})", nestedType, searchId);
                    }
                }
            }
        }
    }

    private boolean isJavaBuiltinType(String type) {
        return Set.of(
                "String", "Integer", "Long", "Double", "Float", "Boolean", "Byte", "Short",
                "Object", "Void", "Map", "List", "Set", "Collection", "Optional",
                "Date", "LocalDate", "LocalDateTime", "Instant",
                "HttpServletRequest", "HttpServletResponse", "HttpSession",
                "Model", "ModelAndView", "RedirectAttributes",
                "ResponseEntity", "MultipartFile", "BindingResult",
                "Authentication", "Principal"
        ).contains(type);
    }

    String extractMethodCode(String controllerCode, String methodName) {
        if (methodName == null || methodName.isBlank()) {
            return controllerCode;
        }

        String[] lines = controllerCode.split("\n");

        // 클래스 레벨 @RequestMapping 추출
        String classMapping = "";
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("@RequestMapping")) {
                classMapping = trimmed;
                break;
            }
        }

        // 메서드 위치 찾기
        int methodLineIdx = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(" " + methodName + "(") || lines[i].contains("\t" + methodName + "(")) {
                methodLineIdx = i;
                break;
            }
        }

        if (methodLineIdx == -1) {
            // 메서드를 찾지 못하면 전체 코드 반환
            return controllerCode;
        }

        // 메서드 위의 어노테이션 찾기 (빈 줄 만나면 중단)
        int startIdx = methodLineIdx;
        for (int i = methodLineIdx - 1; i >= 0; i--) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("@") || trimmed.startsWith("//") || trimmed.startsWith("/*")
                    || trimmed.startsWith("*") || trimmed.startsWith("*/")) {
                startIdx = i;
            } else if (trimmed.isEmpty()) {
                // 빈 줄 위에 Javadoc이 있을 수 있으므로 한 번 더 확인
                if (i > 0 && lines[i - 1].trim().endsWith("*/")) {
                    continue;
                }
                break;
            } else {
                break;
            }
        }

        // 메서드 끝 찾기 (brace matching)
        int braceCount = 0;
        int endIdx = methodLineIdx;
        boolean braceStarted = false;
        for (int i = methodLineIdx; i < lines.length; i++) {
            for (char c : lines[i].toCharArray()) {
                if (c == '{') {
                    braceCount++;
                    braceStarted = true;
                } else if (c == '}') {
                    braceCount--;
                }
            }
            if (braceStarted && braceCount == 0) {
                endIdx = i;
                break;
            }
        }

        StringBuilder sb = new StringBuilder();
        if (!classMapping.isEmpty()) {
            sb.append("// 클래스 레벨 매핑\n").append(classMapping).append("\n\n");
        }
        for (int i = startIdx; i <= endIdx; i++) {
            sb.append(lines[i]).append("\n");
        }

        return sb.toString();
    }

    /**
     * LLM 응답에서 JSON 부분만 추출합니다.
     */
    private String extractJson(String response) {
        if (response == null) return "{}";
        String trimmed = response.strip();

        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline != -1) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3).strip();
        }

        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }

        return trimmed;
    }

    private String cleanLlmResponse(String response) {
        if (response == null) return "<p></p>";
        String cleaned = response.strip();

        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            if (firstNewline != -1) {
                cleaned = cleaned.substring(firstNewline + 1);
            }
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3).strip();
        }

        cleaned = cleaned.replaceAll("<\\?xml[^?]*\\?>", "").strip();

        if (cleaned.isEmpty()) {
            return "<p>문서 생성에 실패했습니다.</p>";
        }

        return cleaned;
    }

    private Long toLong(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        return Long.parseLong(value.toString());
    }
}
