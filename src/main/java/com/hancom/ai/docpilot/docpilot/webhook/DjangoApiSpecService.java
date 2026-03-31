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
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class DjangoApiSpecService {

    private static final int MAX_APIS_PER_VIEW = 25;
    private static final String VIEW_PATH_PREFIX = "<!-- controller-path:";
    private static final String VIEW_PATH_SUFFIX = " -->";
    private static final Pattern VIEW_PATH_PATTERN = Pattern.compile("<!-- controller-path:(.+?) -->");

    private static final Set<String> PYTHON_BUILTIN_TYPES = Set.of(
            "str", "int", "float", "bool", "list", "dict", "tuple", "set", "None", "bytes",
            "datetime", "date", "Decimal", "UUID", "Any", "Optional",
            "Request", "Response", "HttpRequest", "HttpResponse",
            "QuerySet", "Manager", "Serializer", "ModelSerializer", "HyperlinkedModelSerializer",
            "APIView", "ViewSet", "ModelViewSet", "GenericAPIView", "ReadOnlyModelViewSet",
            "ListAPIView", "CreateAPIView", "RetrieveAPIView", "UpdateAPIView", "DestroyAPIView",
            "Permission", "IsAuthenticated", "AllowAny", "IsAdminUser"
    );

    @Value("${api-spec.template-page-title:}")
    private String templatePageTitle;

    private String cachedTemplateXml;

    private final ConfigLoaderService configLoaderService;
    private final GitLabSourceService gitLabSourceService;
    private final LLMRouter llmRouter;
    private final PromptTemplateService promptTemplateService;
    private final ConfluenceTargetService confluenceTargetService;
    private final ObjectMapper objectMapper;
    private final ProcessingStatusTracker processingStatusTracker;
    private final ControllerPageMappingRepository controllerPageMappingRepository;
    private final ApiPageMappingRepository apiPageMappingRepository;

    public DjangoApiSpecService(ConfigLoaderService configLoaderService,
                                GitLabSourceService gitLabSourceService,
                                LLMRouter llmRouter,
                                PromptTemplateService promptTemplateService,
                                ConfluenceTargetService confluenceTargetService,
                                ObjectMapper objectMapper,
                                ProcessingStatusTracker processingStatusTracker,
                                ControllerPageMappingRepository controllerPageMappingRepository,
                                ApiPageMappingRepository apiPageMappingRepository) {
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

    // ======================== 오케스트레이션 ========================

    public void initializeApiSpecForProject(String spaceKey, ConfluenceStructure.ProjectMapping project, int maxCount) {
        try {
            if (cachedTemplateXml == null) {
                loadTemplateFormat(spaceKey);
            }
            initializeApiSpec(spaceKey, project, maxCount);
        } catch (Exception e) {
            log.error("Django 프로젝트 API 명세서 초기화 실패: {}", project.getGitlabPath(), e);
        }
    }

    public void processControllerUpdate(String spaceKey, ConfluenceStructure.ProjectMapping project,
                                         List<String> viewPaths, String branch) {
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
            } else {
                apiSpecPageId = toLong(apiSpecPage.get("id"));
            }

            for (String viewPath : viewPaths) {
                try {
                    processView(spaceKey, project, viewPath, branch, apiSpecPageId, prefix, true);
                } catch (Exception e) {
                    log.error("Django View 업데이트 실패: {}", viewPath, e);
                }
            }
        } catch (Exception e) {
            log.error("Django View 업데이트 중 오류: project={}", project.getGitlabPath(), e);
        }
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

        List<String> viewFiles = findViewFiles(projectId, defaultBranch);
        log.info("Django View 파일 {}개 발견: {}", viewFiles.size(), project.getGitlabPath());

        Set<String> existingPaths = controllerPageMappingRepository.findProcessedControllerPaths(projectId);
        log.info("이미 처리된 View {}개", existingPaths.size());

        int processedCount = 0;
        for (String viewPath : viewFiles) {
            if (existingPaths.contains(viewPath)) {
                log.info("View 이미 처리됨, skip: {}", viewPath);
                continue;
            }
            if (maxCount == 0) break;
            if (maxCount > 0 && processedCount >= maxCount) {
                log.info("View 처리 수 제한({}) 도달", maxCount);
                break;
            }
            try {
                processView(spaceKey, project, viewPath, defaultBranch, apiSpecPageId, prefix, false);
                processedCount++;
            } catch (Exception e) {
                log.error("View 처리 실패: {}", viewPath, e);
            }
        }
        log.info("이번 실행에서 Django View {}개 처리 완료", processedCount);
    }

    @SuppressWarnings("unchecked")
    private void processView(String spaceKey, ConfluenceStructure.ProjectMapping project,
                             String viewPath, String branch,
                             Long apiSpecPageId, String prefix, boolean forceUpdate) throws Exception {
        Long projectId = project.getGitlabProjectId();

        processingStatusTracker.markProcessing(viewPath);
        try {
            processViewInternal(spaceKey, project, viewPath, branch, apiSpecPageId, prefix, forceUpdate);
        } finally {
            processingStatusTracker.markDone(viewPath);
        }
    }

    @SuppressWarnings("unchecked")
    private void processViewInternal(String spaceKey, ConfluenceStructure.ProjectMapping project,
                                     String viewPath, String branch,
                                     Long apiSpecPageId, String prefix, boolean forceUpdate) throws Exception {
        Long projectId = project.getGitlabProjectId();

        String code = gitLabSourceService.getFileContent(projectId, viewPath, branch);
        log.info("Django View 분석 시작: {}", viewPath);

        // View 소스에서 API 시그니처만 추출
        String apiSignatures = extractApiSignatures(code);

        // 관련 urls.py 내용 수집하여 시그니처에 결합
        String urlsContent = collectUrlsContext(projectId, viewPath, branch);
        if (!urlsContent.isEmpty()) {
            apiSignatures = apiSignatures + "\n\n=== 관련 URL 설정 (urls.py) ===\n" + urlsContent;
        }

        // LLM으로 View 분석 → JSON
        String analysisPrompt = promptTemplateService.getPrompt(
                "django_controller_analysis", apiSignatures, project.getGitlabPath());
        String analysisResult = llmRouter.generate(analysisPrompt);
        analysisResult = extractJson(analysisResult);

        JsonNode analysis;
        try {
            analysis = objectMapper.readTree(analysisResult);
        } catch (Exception e) {
            log.warn("Django View 분석 JSON 파싱 실패, skip: {} ({})", viewPath, e.getMessage());
            return;
        }

        JsonNode controllerNameNode = analysis.get("controllerKoreanName");
        if (controllerNameNode == null) {
            log.warn("controllerKoreanName 없음, skip: {}", viewPath);
            return;
        }

        String controllerKoreanName = controllerNameNode.asText();
        JsonNode apis = analysis.get("apis");

        String controllerPageTitle = prefix + controllerKoreanName;
        String controllerPageBody = buildViewPageBody(controllerKoreanName, viewPath, apis, prefix);

        // View 그룹 페이지 확인/생성
        Map<String, Object> existingPage = findPageByViewPath(apiSpecPageId, viewPath);
        Long controllerPageId;

        if (existingPage == null) {
            try {
                Map<String, Object> created = confluenceTargetService.createPage(
                        spaceKey, controllerPageTitle, controllerPageBody, apiSpecPageId);
                controllerPageId = toLong(created.get("id"));
                log.info("View 페이지 생성: {}", controllerPageTitle);
            } catch (Exception e) {
                log.warn("View 페이지 생성 실패, 기존 페이지 검색: {}", controllerPageTitle);
                Map<String, Object> existingByTitle = confluenceTargetService.getPage(spaceKey, controllerPageTitle);
                if (existingByTitle != null) {
                    controllerPageId = toLong(existingByTitle.get("id"));
                    Map<String, Object> vObj = (Map<String, Object>) existingByTitle.get("version");
                    int ver = ((Number) vObj.get("number")).intValue();
                    confluenceTargetService.updatePage(controllerPageId, controllerPageTitle, controllerPageBody, ver + 1);
                } else {
                    throw e;
                }
            }
        } else if (!forceUpdate) {
            log.info("View 페이지 이미 존재, skip: {}", controllerPageTitle);
            return;
        } else {
            controllerPageId = toLong(existingPage.get("id"));
            Map<String, Object> versionObj = (Map<String, Object>) existingPage.get("version");
            int currentVersion = ((Number) versionObj.get("number")).intValue();
            confluenceTargetService.updatePage(controllerPageId, controllerPageTitle, controllerPageBody, currentVersion + 1);
            log.info("View 페이지 업데이트: {}", controllerPageTitle);
        }

        // DB에 Controller 매핑 저장
        ControllerPageMappingEntity mapping = controllerPageMappingRepository
                .findByGitlabProjectIdAndControllerPath(projectId, viewPath)
                .orElse(ControllerPageMappingEntity.builder()
                        .gitlabProjectId(projectId)
                        .controllerPath(viewPath)
                        .build());
        mapping.setControllerKoreanName(controllerKoreanName);
        mapping.setConfluencePageId(controllerPageId);
        mapping.setConfluencePageTitle(controllerPageTitle);
        mapping.setLastUpdated(LocalDateTime.now());
        mapping = controllerPageMappingRepository.save(mapping);

        if (forceUpdate) {
            apiPageMappingRepository.deleteAll(
                    apiPageMappingRepository.findByControllerMappingId(mapping.getId()));
        }

        int apiCount = 0;
        if (apis != null && apis.isArray()) {
            for (JsonNode api : apis) {
                if (apiCount >= MAX_APIS_PER_VIEW) break;
                try {
                    processApiEndpoint(spaceKey, project, code, api,
                            controllerPageId, prefix, forceUpdate, mapping, viewPath, branch);
                    apiCount++;
                } catch (Exception e) {
                    log.warn("API 엔드포인트 처리 실패, skip: {} ({})", api.toString(), e.getMessage(), e);
                }
            }
        }

        mapping.setApiCount(apiCount);
        mapping.setProcessed(true);
        controllerPageMappingRepository.save(mapping);
    }

    private void processApiEndpoint(String spaceKey, ConfluenceStructure.ProjectMapping project,
                                    String viewCode, JsonNode api,
                                    Long controllerPageId, String prefix, boolean forceUpdate,
                                    ControllerPageMappingEntity controllerMapping,
                                    String viewPath, String branch) throws Exception {
        JsonNode koreanNameNode = api.get("koreanName");
        if (koreanNameNode == null) return;

        String koreanName = koreanNameNode.asText();
        String apiPageTitle = prefix + "API - " + koreanName;
        String methodName = api.has("methodName") ? api.get("methodName").asText() : "";

        Map<String, Object> existing = confluenceTargetService.getChildPage(controllerPageId, apiPageTitle);
        if (existing != null && !forceUpdate) {
            log.info("API 페이지 이미 존재, skip: {}", apiPageTitle);
            return;
        }

        // 메서드 코드 추출
        String methodCode = extractMethodCode(viewCode, methodName);

        // Serializer/Model 소스 수집
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

        String serializerSources = collectReferencedSerializers(
                project.getGitlabProjectId(), viewCode, methodCode, viewPath, branch,
                allDependencies.isEmpty() ? null : allDependencies);

        String method = api.has("method") ? api.get("method").asText() : "";
        String path = api.has("path") ? api.get("path").asText() : "";

        String dictKeyHints = extractDictKeyHints(methodCode);

        // API 코드 블록 조립
        StringBuilder apiCodeBuilder = new StringBuilder();
        apiCodeBuilder.append(String.format(
                "대상 API: %s %s (메서드명: %s, 한국어명: %s)\n\n", method, path, methodName, koreanName));
        apiCodeBuilder.append("=== View 메서드 코드 ===\n").append(methodCode);
        if (!serializerSources.isEmpty()) {
            apiCodeBuilder.append("\n\n=== 참조 Serializer/Model 클래스 (Request/Response 필드 정보) ===\n");
            apiCodeBuilder.append(serializerSources);
        }
        if (!dictKeyHints.isEmpty()) {
            apiCodeBuilder.append("\n\n=== request.data 파라미터 분석 결과 ===\n");
            apiCodeBuilder.append(dictKeyHints);
        }
        String apiCode = apiCodeBuilder.toString();

        String detailPrompt;
        if (cachedTemplateXml != null) {
            detailPrompt = promptTemplateService.getPromptWithTemplate(
                    "django_api_detail_with_template", apiCode, project.getGitlabPath(), cachedTemplateXml);
        } else {
            detailPrompt = promptTemplateService.getPrompt(
                    "django_api_detail", apiCode, project.getGitlabPath());
        }
        String detailDoc = cleanLlmResponse(llmRouter.generate(detailPrompt));

        Long apiConfluencePageId;
        if (existing != null) {
            apiConfluencePageId = toLong(existing.get("id"));
            @SuppressWarnings("unchecked")
            Map<String, Object> versionObj = (Map<String, Object>) existing.get("version");
            int currentVersion = ((Number) versionObj.get("number")).intValue();
            confluenceTargetService.updatePage(apiConfluencePageId, apiPageTitle, detailDoc, currentVersion + 1);
            confluenceTargetService.setPageWidthNarrow(apiConfluencePageId);
        } else {
            try {
                Map<String, Object> created = confluenceTargetService.createPage(spaceKey, apiPageTitle, detailDoc, controllerPageId);
                apiConfluencePageId = toLong(created.get("id"));
                confluenceTargetService.setPageWidthNarrow(apiConfluencePageId);
            } catch (Exception e) {
                Map<String, Object> existingByTitle = confluenceTargetService.getPage(spaceKey, apiPageTitle);
                if (existingByTitle != null) {
                    apiConfluencePageId = toLong(existingByTitle.get("id"));
                    @SuppressWarnings("unchecked")
                    Map<String, Object> vObj = (Map<String, Object>) existingByTitle.get("version");
                    int ver = ((Number) vObj.get("number")).intValue();
                    confluenceTargetService.updatePage(apiConfluencePageId, apiPageTitle, detailDoc, ver + 1);
                    confluenceTargetService.setPageWidthNarrow(apiConfluencePageId);
                } else {
                    throw e;
                }
            }
        }
        log.info("API 페이지 생성/업데이트: {}", apiPageTitle);

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

    // ======================== 파일 탐색 ========================

    public List<String> findViewFiles(Long projectId, String branch) throws Exception {
        List<String> pyFiles = gitLabSourceService.findFiles(projectId, branch, ".py");
        return pyFiles.stream()
                .filter(f -> !f.contains("__pycache__"))
                .filter(f -> !f.contains("/migrations/"))
                .filter(f -> !f.contains("test"))
                .filter(f -> {
                    String fileName = f.contains("/") ? f.substring(f.lastIndexOf('/') + 1) : f;
                    return fileName.equals("views.py")
                            || fileName.endsWith("_views.py")
                            || fileName.equals("viewsets.py")
                            || fileName.endsWith("_viewsets.py")
                            || f.contains("/views/");
                })
                .toList();
    }

    private List<String> findUrlFiles(Long projectId, String branch) throws Exception {
        List<String> pyFiles = gitLabSourceService.findFiles(projectId, branch, "urls.py");
        return pyFiles.stream()
                .filter(f -> !f.contains("__pycache__"))
                .filter(f -> !f.contains("/migrations/"))
                .filter(f -> !f.contains("test"))
                .toList();
    }

    public List<String> filterChangedViewFiles(List<String> changedFiles) {
        return changedFiles.stream()
                .filter(f -> f.endsWith(".py"))
                .filter(f -> !f.contains("__pycache__") && !f.contains("/migrations/") && !f.contains("test"))
                .filter(f -> {
                    String fileName = f.contains("/") ? f.substring(f.lastIndexOf('/') + 1) : f;
                    return fileName.equals("views.py")
                            || fileName.endsWith("_views.py")
                            || fileName.equals("viewsets.py")
                            || fileName.endsWith("_viewsets.py")
                            || f.contains("/views/");
                })
                .toList();
    }

    // ======================== Python 코드 분석 ========================

    /**
     * Python View 소스에서 API 분석에 필요한 시그니처만 추출합니다.
     * - import 문 유지
     * - 클래스 선언 + 클래스 속성(queryset, serializer_class) 유지
     * - 데코레이터 유지
     * - def 시그니처 유지, 본문은 ... 으로 대체
     */
    String extractApiSignatures(String viewCode) {
        String[] lines = viewCode.split("\n");
        StringBuilder sb = new StringBuilder();

        boolean inFunctionBody = false;
        int bodyIndent = 0;
        boolean isDocstring = false;
        String docstringDelimiter = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String stripped = line.stripLeading();
            int indent = line.length() - stripped.length();

            // Docstring 처리 (함수 본문 내부의 docstring 건너뛰기)
            if (isDocstring) {
                if (stripped.contains(docstringDelimiter)) {
                    isDocstring = false;
                }
                continue;
            }

            // 함수 본문 내부 - 들여쓰기가 더 깊으면 skip
            if (inFunctionBody) {
                if (stripped.isEmpty()) continue; // 빈 줄은 skip
                if (indent > bodyIndent || (indent == bodyIndent && stripped.startsWith("#"))) {
                    continue; // 본문 내용 skip
                }
                // 본문 종료
                inFunctionBody = false;
            }

            // import 문 유지
            if (stripped.startsWith("import ") || stripped.startsWith("from ")) {
                sb.append(line).append("\n");
                continue;
            }

            // 데코레이터 유지
            if (stripped.startsWith("@")) {
                sb.append(line).append("\n");
                // 여러 줄 데코레이터 처리
                while (i + 1 < lines.length && !stripped.endsWith(")") && stripped.contains("(")
                        && !lines[i].stripLeading().contains(")")) {
                    i++;
                    sb.append(lines[i]).append("\n");
                    stripped = lines[i].stripLeading();
                }
                continue;
            }

            // 클래스 선언 유지
            if (stripped.startsWith("class ") && stripped.contains(":")) {
                sb.append("\n").append(line).append("\n");
                // 클래스 속성 추출 (queryset, serializer_class, permission_classes 등)
                int classIndent = indent;
                while (i + 1 < lines.length) {
                    String nextLine = lines[i + 1];
                    String nextStripped = nextLine.stripLeading();
                    int nextIndent = nextLine.length() - nextStripped.length();

                    if (nextStripped.isEmpty()) {
                        i++;
                        continue;
                    }
                    if (nextIndent <= classIndent) break; // 클래스 밖으로 나감

                    if (nextStripped.startsWith("queryset")
                            || nextStripped.startsWith("serializer_class")
                            || nextStripped.startsWith("permission_classes")
                            || nextStripped.startsWith("authentication_classes")
                            || nextStripped.startsWith("lookup_field")
                            || nextStripped.startsWith("pagination_class")) {
                        i++;
                        sb.append(nextLine).append("\n");
                    } else {
                        break; // 메서드 정의 시작
                    }
                }

                // ViewSet base class 감지 및 암시적 엔드포인트 주석 추가
                if (stripped.contains("ModelViewSet")) {
                    sb.append("    # ViewSet: list(GET), create(POST), retrieve(GET), update(PUT), partial_update(PATCH), destroy(DELETE)\n");
                } else if (stripped.contains("ReadOnlyModelViewSet")) {
                    sb.append("    # ViewSet: list(GET), retrieve(GET)\n");
                } else if (stripped.contains("ListCreateAPIView")) {
                    sb.append("    # Generic: list(GET), create(POST)\n");
                } else if (stripped.contains("RetrieveUpdateDestroyAPIView")) {
                    sb.append("    # Generic: retrieve(GET), update(PUT), partial_update(PATCH), destroy(DELETE)\n");
                } else if (stripped.contains("ListAPIView")) {
                    sb.append("    # Generic: list(GET)\n");
                } else if (stripped.contains("CreateAPIView")) {
                    sb.append("    # Generic: create(POST)\n");
                } else if (stripped.contains("RetrieveAPIView")) {
                    sb.append("    # Generic: retrieve(GET)\n");
                }
                continue;
            }

            // def 시그니처 유지, 본문은 ... 으로 대체
            if (stripped.startsWith("def ") || stripped.startsWith("async def ")) {
                sb.append(line).append("\n");

                // 여러 줄 def 처리
                String defLine = stripped;
                while (!defLine.contains(":") && i + 1 < lines.length) {
                    i++;
                    sb.append(lines[i]).append("\n");
                    defLine = lines[i].stripLeading();
                }

                bodyIndent = indent + 4; // Python 표준 들여쓰기
                // 탭 기반 들여쓰기 감지
                if (line.startsWith("\t")) {
                    bodyIndent = indent + 1;
                }

                // docstring 확인
                if (i + 1 < lines.length) {
                    String nextStripped = lines[i + 1].stripLeading();
                    if (nextStripped.startsWith("\"\"\"") || nextStripped.startsWith("'''")) {
                        docstringDelimiter = nextStripped.startsWith("\"\"\"") ? "\"\"\"" : "'''";
                        // 한 줄 docstring인지 확인
                        i++;
                        sb.append(lines[i]).append("\n");
                        if (!nextStripped.substring(3).contains(docstringDelimiter)) {
                            // 여러 줄 docstring
                            while (i + 1 < lines.length) {
                                i++;
                                sb.append(lines[i]).append("\n");
                                if (lines[i].stripLeading().contains(docstringDelimiter)) break;
                            }
                        }
                    }
                }

                sb.append(" ".repeat(Math.max(bodyIndent, 0))).append("...\n\n");
                inFunctionBody = true;
                continue;
            }

            // 빈 줄은 유지 (가독성)
            if (stripped.isEmpty()) {
                sb.append("\n");
            }
        }

        String result = sb.toString().trim();
        return result.isEmpty() ? viewCode : result;
    }

    /**
     * View 코드에서 특정 메서드/함수의 코드를 추출합니다.
     * methodName이 "클래스명.메서드명" 형식이면 클래스 컨텍스트도 함께 추출합니다.
     */
    String extractMethodCode(String viewCode, String methodName) {
        if (methodName == null || methodName.isBlank()) return viewCode;

        String[] lines = viewCode.split("\n");
        String className = null;
        String actualMethodName = methodName;

        // "ClassName.method_name" 형식 처리
        if (methodName.contains(".")) {
            String[] parts = methodName.split("\\.", 2);
            className = parts[0];
            actualMethodName = parts[1];
        }

        StringBuilder result = new StringBuilder();

        // 클래스 컨텍스트 추출
        if (className != null) {
            for (int i = 0; i < lines.length; i++) {
                String stripped = lines[i].stripLeading();
                if (stripped.startsWith("class " + className)) {
                    // 클래스 위 데코레이터 수집
                    int classStart = i;
                    while (classStart > 0 && lines[classStart - 1].stripLeading().startsWith("@")) {
                        classStart--;
                    }
                    for (int j = classStart; j <= i; j++) {
                        result.append(lines[j]).append("\n");
                    }

                    // 클래스 속성 수집
                    int classIndent = lines[i].length() - stripped.length();
                    for (int j = i + 1; j < lines.length; j++) {
                        String nextStripped = lines[j].stripLeading();
                        int nextIndent = lines[j].length() - nextStripped.length();
                        if (nextStripped.isEmpty()) continue;
                        if (nextIndent <= classIndent) break;
                        if (nextStripped.startsWith("queryset")
                                || nextStripped.startsWith("serializer_class")
                                || nextStripped.startsWith("permission_classes")
                                || nextStripped.startsWith("lookup_field")) {
                            result.append(lines[j]).append("\n");
                        } else if (nextStripped.startsWith("def ") || nextStripped.startsWith("@")) {
                            break;
                        }
                    }
                    result.append("\n");
                    break;
                }
            }
        }

        // 메서드/함수 추출
        int methodLineIdx = -1;
        for (int i = 0; i < lines.length; i++) {
            String stripped = lines[i].stripLeading();
            if ((stripped.startsWith("def " + actualMethodName + "(")
                    || stripped.startsWith("async def " + actualMethodName + "("))) {
                methodLineIdx = i;
                break;
            }
        }

        if (methodLineIdx == -1) return viewCode;

        // 데코레이터 수집 (위로)
        int startIdx = methodLineIdx;
        while (startIdx > 0) {
            String prevStripped = lines[startIdx - 1].stripLeading();
            if (prevStripped.startsWith("@") || prevStripped.startsWith("#")
                    || prevStripped.isEmpty()) {
                startIdx--;
            } else {
                break;
            }
        }
        // 빈 줄은 건너뛰기
        while (startIdx < methodLineIdx && lines[startIdx].stripLeading().isEmpty()) {
            startIdx++;
        }

        // 메서드 끝 찾기 (들여쓰기 기반)
        int defIndent = lines[methodLineIdx].length() - lines[methodLineIdx].stripLeading().length();
        int endIdx = methodLineIdx;

        // def 줄 이후 본문 찾기
        for (int i = methodLineIdx + 1; i < lines.length; i++) {
            String stripped = lines[i].stripLeading();
            int indent = lines[i].length() - stripped.length();

            if (stripped.isEmpty()) {
                endIdx = i;
                continue;
            }
            if (indent <= defIndent) {
                break; // 같은 또는 낮은 레벨 = 메서드 종료
            }
            endIdx = i;
        }

        for (int i = startIdx; i <= endIdx; i++) {
            result.append(lines[i]).append("\n");
        }

        return result.toString().trim().isEmpty() ? viewCode : result.toString().trim();
    }

    /**
     * View 코드에서 참조하는 Serializer/Model 소스를 수집합니다.
     */
    String collectReferencedSerializers(Long projectId, String viewCode, String methodCode,
                                        String viewPath, String branch, List<Long> dependencyProjectIds) {
        Set<String> classNames = new LinkedHashSet<>();
        StringBuilder output = new StringBuilder();
        Set<String> collected = new HashSet<>();
        Set<String> notFound = new HashSet<>();

        // Serializer 참조 감지
        Matcher m1 = Pattern.compile("serializer_class\\s*=\\s*([A-Z]\\w+)").matcher(viewCode);
        while (m1.find()) classNames.add(m1.group(1));

        Matcher m2 = Pattern.compile("([A-Z]\\w*Serializer)\\s*\\(").matcher(methodCode);
        while (m2.find()) classNames.add(m2.group(1));

        // Model 참조 감지
        Matcher m3 = Pattern.compile("queryset\\s*=\\s*([A-Z]\\w+)\\.objects").matcher(viewCode);
        while (m3.find()) classNames.add(m3.group(1));

        Matcher m4 = Pattern.compile("model\\s*=\\s*([A-Z]\\w+)").matcher(methodCode);
        while (m4.find()) classNames.add(m4.group(1));

        // 리턴 타입의 Serializer
        Matcher m5 = Pattern.compile("get_serializer_class.*?return\\s+([A-Z]\\w+)").matcher(viewCode);
        while (m5.find()) classNames.add(m5.group(1));

        // 빌트인 타입 제외
        classNames.removeIf(PYTHON_BUILTIN_TYPES::contains);

        if (classNames.isEmpty()) return "";

        // import 문에서 모듈 경로 추출
        Map<String, String> importMap = new HashMap<>();
        Matcher importMatcher = Pattern.compile("from\\s+([\\w.]+)\\s+import\\s+(.+)").matcher(viewCode);
        while (importMatcher.find()) {
            String module = importMatcher.group(1);
            String imports = importMatcher.group(2);
            for (String imported : imports.split(",")) {
                String name = imported.trim().split("\\s+as\\s+")[0].trim();
                if (classNames.contains(name)) {
                    importMap.put(name, module);
                }
            }
        }

        // 뷰 파일의 앱 디렉토리 경로
        String appDir = viewPath.contains("/") ? viewPath.substring(0, viewPath.lastIndexOf('/')) : "";

        List<Long> searchProjectIds = new ArrayList<>();
        searchProjectIds.add(projectId);
        if (dependencyProjectIds != null) searchProjectIds.addAll(dependencyProjectIds);

        for (String className : classNames) {
            if (collected.contains(className)) continue;

            String source = null;
            String module = importMap.get(className);

            if (module != null) {
                source = resolveModuleSource(searchProjectIds, module, className, appDir, branch);
            }

            // 폴백: 파일명으로 검색
            if (source == null) {
                source = findClassByFileName(searchProjectIds, className, branch);
            }

            if (source != null) {
                // 특정 클래스만 추출
                String classSource = extractPythonClass(source, className);
                output.append("--- ").append(className).append(" ---\n");
                output.append(classSource).append("\n\n");
                collected.add(className);

                // 중첩 Serializer 수집
                collectNestedSerializers(searchProjectIds, classSource, branch, output, collected, notFound, appDir);
            } else {
                notFound.add(className);
            }
        }

        if (!notFound.isEmpty()) {
            output.append("\n=== 주의: 아래 Serializer/Model 클래스의 소스를 찾지 못했습니다 ===\n");
            output.append("LLM이 코드 컨텍스트에서 필드를 유추하여 작성하세요.\n");
            for (String name : notFound) {
                output.append("  - ").append(name).append("\n");
            }
        }

        return output.toString();
    }

    /**
     * request.data, request.query_params 등에서 사용되는 키를 추출합니다.
     */
    String extractDictKeyHints(String methodCode) {
        if (methodCode == null) return "";

        Map<String, List<String>> keysBySource = new LinkedHashMap<>();

        // request.data.get('key') 또는 request.data['key']
        extractKeys(methodCode, "request\\.data\\.get\\(['\"]([^'\"]+)['\"]", "request.data", keysBySource);
        extractKeys(methodCode, "request\\.data\\[['\"]([^'\"]+)['\"]\\]", "request.data", keysBySource);

        // request.query_params
        extractKeys(methodCode, "request\\.query_params\\.get\\(['\"]([^'\"]+)['\"]", "request.query_params", keysBySource);
        extractKeys(methodCode, "request\\.query_params\\[['\"]([^'\"]+)['\"]\\]", "request.query_params", keysBySource);

        // request.GET / request.POST
        extractKeys(methodCode, "request\\.GET\\.get\\(['\"]([^'\"]+)['\"]", "request.GET", keysBySource);
        extractKeys(methodCode, "request\\.POST\\.get\\(['\"]([^'\"]+)['\"]", "request.POST", keysBySource);

        // kwargs
        extractKeys(methodCode, "kwargs\\.get\\(['\"]([^'\"]+)['\"]", "URL kwargs", keysBySource);
        extractKeys(methodCode, "kwargs\\[['\"]([^'\"]+)['\"]\\]", "URL kwargs", keysBySource);
        extractKeys(methodCode, "self\\.kwargs\\[['\"]([^'\"]+)['\"]\\]", "URL kwargs", keysBySource);
        extractKeys(methodCode, "self\\.kwargs\\.get\\(['\"]([^'\"]+)['\"]", "URL kwargs", keysBySource);

        if (keysBySource.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("request 파라미터의 실제 사용 key 목록입니다. Request Parameter 작성 시 이 필드들을 포함하세요:\n");
        for (Map.Entry<String, List<String>> entry : keysBySource.entrySet()) {
            sb.append("소스: ").append(entry.getKey()).append("\n");
            for (String key : entry.getValue()) {
                sb.append("  - ").append(key).append("\n");
            }
        }
        return sb.toString();
    }

    // ======================== 헬퍼 메서드 ========================

    private void extractKeys(String code, String pattern, String source, Map<String, List<String>> result) {
        Matcher matcher = Pattern.compile(pattern).matcher(code);
        while (matcher.find()) {
            result.computeIfAbsent(source, k -> new ArrayList<>());
            String key = matcher.group(1);
            if (!result.get(source).contains(key)) {
                result.get(source).add(key);
            }
        }
    }

    private String collectUrlsContext(Long projectId, String viewPath, String branch) {
        StringBuilder urlsContent = new StringBuilder();
        try {
            List<String> urlFiles = findUrlFiles(projectId, branch);
            String appDir = viewPath.contains("/") ? viewPath.substring(0, viewPath.lastIndexOf('/')) : "";

            for (String urlFile : urlFiles) {
                // 같은 앱 디렉토리 또는 부모 디렉토리의 urls.py 우선
                if (urlFile.startsWith(appDir) || appDir.startsWith(urlFile.substring(0, Math.max(0, urlFile.lastIndexOf('/'))))) {
                    String content = gitLabSourceService.getFileContent(projectId, urlFile, branch);
                    urlsContent.append("--- ").append(urlFile).append(" ---\n");
                    urlsContent.append(content).append("\n\n");
                }
            }
        } catch (Exception e) {
            log.debug("urls.py 수집 실패: {}", e.getMessage());
        }
        return urlsContent.toString();
    }

    private String resolveModuleSource(List<Long> projectIds, String module, String className,
                                        String appDir, String branch) {
        // 상대 import 처리 (. 으로 시작)
        String filePath;
        if (module.startsWith(".")) {
            String relativePart = module.substring(1); // .serializers → serializers
            if (relativePart.isEmpty()) {
                // from . import Something → 같은 디렉토리의 __init__.py 또는 파일
                filePath = appDir + "/" + className.toLowerCase() + ".py";
            } else {
                filePath = appDir + "/" + relativePart.replace(".", "/") + ".py";
            }
        } else {
            // 절대 import
            filePath = module.replace(".", "/") + ".py";
        }

        for (Long pid : projectIds) {
            try {
                String defaultBranch = gitLabSourceService.getDefaultBranch(pid);
                return gitLabSourceService.getFileContent(pid, filePath, defaultBranch);
            } catch (Exception ignored) {}
        }

        // 경로로 못 찾으면 파일명으로 검색
        String fileName = filePath.contains("/") ? filePath.substring(filePath.lastIndexOf('/') + 1) : filePath;
        for (Long pid : projectIds) {
            try {
                String defaultBranch = gitLabSourceService.getDefaultBranch(pid);
                List<String> found = gitLabSourceService.findFiles(pid, defaultBranch, fileName);
                for (String f : found) {
                    if (f.endsWith(fileName)) {
                        return gitLabSourceService.getFileContent(pid, f, defaultBranch);
                    }
                }
            } catch (Exception ignored) {}
        }

        return null;
    }

    private String findClassByFileName(List<Long> projectIds, String className, String branch) {
        // Serializer 클래스 → serializers.py에서 검색
        String searchFile = className.endsWith("Serializer") ? "serializers.py" : "models.py";

        for (Long pid : projectIds) {
            try {
                String defaultBranch = gitLabSourceService.getDefaultBranch(pid);
                List<String> found = gitLabSourceService.findFiles(pid, defaultBranch, searchFile);
                for (String f : found) {
                    String content = gitLabSourceService.getFileContent(pid, f, defaultBranch);
                    if (content.contains("class " + className)) {
                        return content;
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * Python 소스 파일에서 특정 클래스 블록만 추출합니다.
     */
    private String extractPythonClass(String source, String className) {
        String[] lines = source.split("\n");
        StringBuilder result = new StringBuilder();
        boolean inClass = false;
        int classIndent = 0;

        for (int i = 0; i < lines.length; i++) {
            String stripped = lines[i].stripLeading();
            int indent = lines[i].length() - stripped.length();

            if (!inClass) {
                // 클래스 선언 찾기
                if (stripped.startsWith("class " + className + "(") || stripped.startsWith("class " + className + ":")) {
                    // 위의 데코레이터 포함
                    int start = i;
                    while (start > 0 && lines[start - 1].stripLeading().startsWith("@")) {
                        start--;
                    }
                    for (int j = start; j <= i; j++) {
                        result.append(lines[j]).append("\n");
                    }
                    inClass = true;
                    classIndent = indent;
                }
            } else {
                if (stripped.isEmpty()) {
                    result.append("\n");
                    continue;
                }
                if (indent <= classIndent && !stripped.startsWith("#")) {
                    break; // 클래스 밖
                }
                result.append(lines[i]).append("\n");
            }
        }

        String extracted = result.toString().trim();
        return extracted.isEmpty() ? source : extracted;
    }

    private void collectNestedSerializers(List<Long> projectIds, String classSource, String branch,
                                           StringBuilder output, Set<String> collected, Set<String> notFound,
                                           String appDir) {
        // 필드에서 참조하는 Serializer 감지
        Matcher m = Pattern.compile("([A-Z]\\w*Serializer)\\s*\\(").matcher(classSource);
        while (m.find()) {
            String nested = m.group(1);
            if (collected.contains(nested) || notFound.contains(nested) || PYTHON_BUILTIN_TYPES.contains(nested)) continue;

            String source = findClassByFileName(projectIds, nested, branch);
            if (source != null) {
                String extracted = extractPythonClass(source, nested);
                output.append("--- ").append(nested).append(" ---\n");
                output.append(extracted).append("\n\n");
                collected.add(nested);
                // 재귀 (1단계만)
            } else {
                notFound.add(nested);
            }
        }

        // Model 참조 감지 (Meta.model = XxxModel)
        Matcher mModel = Pattern.compile("model\\s*=\\s*([A-Z]\\w+)").matcher(classSource);
        while (mModel.find()) {
            String modelName = mModel.group(1);
            if (collected.contains(modelName) || notFound.contains(modelName) || PYTHON_BUILTIN_TYPES.contains(modelName)) continue;

            String source = findClassByFileName(List.copyOf(projectIds), modelName, branch);
            if (source != null) {
                String extracted = extractPythonClass(source, modelName);
                output.append("--- ").append(modelName).append(" (Model) ---\n");
                output.append(extracted).append("\n\n");
                collected.add(modelName);
            } else {
                notFound.add(modelName);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findPageByViewPath(Long parentId, String viewPath) {
        try {
            Map<String, Object> response = confluenceTargetService.getChildPagesFull(parentId);
            List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");
            if (results == null) return null;

            String searchTag = VIEW_PATH_PREFIX + viewPath + VIEW_PATH_SUFFIX;
            for (Map<String, Object> page : results) {
                Map<String, Object> body = (Map<String, Object>) page.get("body");
                if (body == null) continue;
                Map<String, Object> storage = (Map<String, Object>) body.get("storage");
                if (storage == null) continue;
                String content = (String) storage.get("value");
                if (content != null && content.contains(searchTag)) return page;
            }
        } catch (Exception e) {
            log.warn("View 페이지 검색 실패: {}", e.getMessage());
        }
        return null;
    }

    private String buildViewPageBody(String viewKoreanName, String viewPath, JsonNode apis, String prefix) {
        StringBuilder sb = new StringBuilder();

        sb.append(VIEW_PATH_PREFIX).append(viewPath).append(VIEW_PATH_SUFFIX).append("\n");
        sb.append("<h2>View 정보</h2>");
        sb.append("<table><tbody>");
        sb.append("<tr><th>항목</th><th>내용</th></tr>");
        sb.append("<tr><td>View 이름</td><td>").append(viewKoreanName).append("</td></tr>");
        sb.append("<tr><td>파일 경로</td><td>").append(viewPath).append("</td></tr>");
        sb.append("</tbody></table>");

        sb.append("<h2>API 목록</h2>");
        if (apis != null && apis.isArray() && apis.size() > 0) {
            sb.append("<table><tbody>");
            sb.append("<tr><th>메서드</th><th>경로</th><th>API명</th></tr>");
            int count = 0;
            for (JsonNode api : apis) {
                if (count >= MAX_APIS_PER_VIEW) break;
                String method = api.has("method") ? api.get("method").asText() : "";
                String path = api.has("path") ? api.get("path").asText() : "";
                String koreanName = api.has("koreanName") ? api.get("koreanName").asText() : "";
                sb.append("<tr><td>").append(method).append("</td>");
                sb.append("<td>").append(path).append("</td>");
                sb.append("<td>").append(koreanName).append("</td></tr>");
                count++;
            }
            sb.append("</tbody></table>");
        } else {
            sb.append("<p>API 엔드포인트가 없습니다.</p>");
        }

        sb.append("<h2>API 상세 명세</h2>");
        sb.append("<ac:structured-macro ac:name=\"children\">");
        sb.append("<ac:parameter ac:name=\"sort\">title</ac:parameter>");
        sb.append("<ac:parameter ac:name=\"all\">true</ac:parameter>");
        sb.append("</ac:structured-macro>");

        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private void loadTemplateFormat(String spaceKey) {
        if (templatePageTitle == null || templatePageTitle.isBlank()) {
            cachedTemplateXml = null;
            return;
        }
        try {
            Map<String, Object> page = confluenceTargetService.getPage(spaceKey, templatePageTitle);
            if (page == null) {
                cachedTemplateXml = null;
                return;
            }
            Map<String, Object> body = (Map<String, Object>) page.get("body");
            Map<String, Object> storage = (Map<String, Object>) body.get("storage");
            cachedTemplateXml = (String) storage.get("value");
        } catch (Exception e) {
            cachedTemplateXml = null;
        }
    }

    private String extractJson(String text) {
        if (text == null) return "";
        text = text.trim();
        if (text.startsWith("```json")) text = text.substring(7);
        else if (text.startsWith("```")) text = text.substring(3);
        if (text.endsWith("```")) text = text.substring(0, text.length() - 3);
        return text.trim();
    }

    private String cleanLlmResponse(String response) {
        if (response == null) return "";
        response = response.trim();
        if (response.startsWith("```xml")) response = response.substring(6);
        else if (response.startsWith("```html")) response = response.substring(7);
        else if (response.startsWith("```")) response = response.substring(3);
        if (response.endsWith("```")) response = response.substring(0, response.length() - 3);
        response = response.replaceAll("<\\?xml[^?]*\\?>", "").trim();
        return response;
    }

    private Long toLong(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        return Long.parseLong(value.toString());
    }
}
