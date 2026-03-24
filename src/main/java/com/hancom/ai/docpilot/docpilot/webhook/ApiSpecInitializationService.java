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

        // 서식 템플릿 페이지를 한 번 읽어서 캐싱
        loadTemplateFormat(spaceKey);

        for (ConfluenceStructure.ProjectMapping project : structure.getProjects()) {
            try {
                initializeApiSpec(spaceKey, project);
            } catch (Exception e) {
                log.error("API 명세서 초기화 실패: {}", project.getGitlabPath(), e);
            }
        }

        log.info("=== API 명세서 초기화 완료 ===");
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
            // maxControllers 제한 (0이면 무제한)
            if (maxControllers > 0 && processedCount >= maxControllers) {
                log.info("Controller 처리 수 제한({}) 도달, 나머지는 다음 실행에서 처리", maxControllers);
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

        // LLM으로 Controller 분석 → JSON
        String analysisPrompt = promptTemplateService.getPrompt(
                "controller_analysis", code, project.getGitlabPath());
        String analysisResult = llmRouter.generate(analysisPrompt);
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
            Map<String, Object> created = confluenceTargetService.createPage(
                    spaceKey, controllerPageTitle, controllerPageBody, apiSpecPageId);
            controllerPageId = toLong(created.get("id"));
            log.info("Controller 페이지 생성: {}", controllerPageTitle);
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

        String method = api.has("method") ? api.get("method").asText() : "";
        String path = api.has("path") ? api.get("path").asText() : "";

        String apiCode = String.format(
                "대상 API: %s %s (메서드명: %s, 한국어명: %s)\n\n코드:\n%s",
                method, path, methodName, koreanName, methodCode);

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
            // forceUpdate: 기존 페이지 업데이트
            apiConfluencePageId = toLong(existing.get("id"));
            @SuppressWarnings("unchecked")
            Map<String, Object> versionObj = (Map<String, Object>) existing.get("version");
            int currentVersion = ((Number) versionObj.get("number")).intValue();
            confluenceTargetService.updatePage(apiConfluencePageId, apiPageTitle, detailDoc, currentVersion + 1);
            confluenceTargetService.setPageWidthNarrow(apiConfluencePageId);
            log.info("API 페이지 업데이트: {}", apiPageTitle);
        } else {
            Map<String, Object> created = confluenceTargetService.createPage(spaceKey, apiPageTitle, detailDoc, controllerPageId);
            apiConfluencePageId = toLong(created.get("id"));
            confluenceTargetService.setPageWidthNarrow(apiConfluencePageId);
            log.info("API 페이지 생성: {}", apiPageTitle);
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
     * Controller 소스코드에서 특정 메서드의 코드를 추출합니다.
     * 클래스 레벨 @RequestMapping과 해당 메서드의 어노테이션 + 본문을 반환합니다.
     */
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
