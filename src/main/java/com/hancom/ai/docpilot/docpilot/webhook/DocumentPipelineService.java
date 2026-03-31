package com.hancom.ai.docpilot.docpilot.webhook;

import com.hancom.ai.docpilot.docpilot.config.ConfigLoaderService;
import com.hancom.ai.docpilot.docpilot.config.model.ConfluenceStructure;
import com.hancom.ai.docpilot.docpilot.llm.LLMRouter;
import com.hancom.ai.docpilot.docpilot.llm.PromptTemplateService;
import com.hancom.ai.docpilot.docpilot.source.gitlab.GitLabSourceService;
import com.hancom.ai.docpilot.docpilot.target.confluence.ConfluenceTargetService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DocumentPipelineService {

    private final ConfigLoaderService configLoaderService;
    private final GitLabSourceService gitLabSourceService;
    private final LLMRouter llmRouter;
    private final PromptTemplateService promptTemplateService;
    private final ConfluenceTargetService confluenceTargetService;
    private final ApiSpecInitializationService apiSpecInitializationService;
    private final ExpressApiSpecService expressApiSpecService;
    private final DjangoApiSpecService djangoApiSpecService;

    public DocumentPipelineService(ConfigLoaderService configLoaderService,
                                   GitLabSourceService gitLabSourceService,
                                   LLMRouter llmRouter,
                                   PromptTemplateService promptTemplateService,
                                   ConfluenceTargetService confluenceTargetService,
                                   ApiSpecInitializationService apiSpecInitializationService,
                                   ExpressApiSpecService expressApiSpecService,
                                   DjangoApiSpecService djangoApiSpecService) {
        this.configLoaderService = configLoaderService;
        this.gitLabSourceService = gitLabSourceService;
        this.llmRouter = llmRouter;
        this.promptTemplateService = promptTemplateService;
        this.confluenceTargetService = confluenceTargetService;
        this.apiSpecInitializationService = apiSpecInitializationService;
        this.expressApiSpecService = expressApiSpecService;
        this.djangoApiSpecService = djangoApiSpecService;
    }

    /**
     * GitLab 이벤트를 비동기로 처리합니다.
     * Source(GitLab) → LLM → Target(Confluence) 파이프라인을 실행합니다.
     */
    @Async
    public void processEvent(String eventType, Long projectId, String projectName,
                             String branch, List<String> changedFiles, String commitSha) {
        log.info("파이프라인 시작: event={}, project={}(id={}), branch={}, files={}",
                eventType, projectName, projectId, branch, changedFiles.size());

        try {
            // 프로젝트 매핑 조회
            ConfluenceStructure.ProjectMapping projectMapping =
                    configLoaderService.getProjectMapping(projectId);

            if (projectMapping == null) {
                log.warn("등록되지 않은 프로젝트: id={}, name={}. 처리 건너뜀", projectId, projectName);
                return;
            }

            // 대상 페이지 결정
            List<ConfluenceStructure.Page> targetPages =
                    configLoaderService.getPagesForEvent(projectMapping, eventType, changedFiles);

            if (targetPages.isEmpty()) {
                log.info("업데이트 대상 페이지 없음, 처리 종료");
                return;
            }

            // Confluence 계층 경로
            String spaceKey = configLoaderService.getConfluenceStructure().getSpaceKey();
            List<String> parentPages = projectMapping.getConfluenceParentPages();

            String titlePrefix = projectMapping.getPageTitlePrefix();

            for (ConfluenceStructure.Page page : targetPages) {
                try {
                    processPage(page, projectId, projectName, branch, changedFiles,
                            spaceKey, parentPages, titlePrefix);
                } catch (Exception e) {
                    log.error("페이지 처리 실패: pageId={}, title={}", page.getId(), page.getTitle(), e);
                }
            }

            log.info("파이프라인 완료: project={}, 처리 페이지={}개", projectName, targetPages.size());

            // Controller 변경 감지 → API 명세서 업데이트
            processControllerChanges(changedFiles, spaceKey, projectMapping, branch);

        } catch (Exception e) {
            log.error("파이프라인 처리 중 오류: project={}", projectName, e);
        }
    }

    /**
     * 변경된 파일 중 Controller 파일이 있으면 해당 API 명세서 페이지를 업데이트합니다.
     */
    private void processControllerChanges(List<String> changedFiles, String spaceKey,
                                           ConfluenceStructure.ProjectMapping projectMapping, String branch) {
        String platform = projectMapping.getPlatform() != null ? projectMapping.getPlatform() : "springboot";

        List<String> changedControllers;
        switch (platform) {
            case "express" -> {
                changedControllers = expressApiSpecService.filterChangedRouteFiles(changedFiles);
                if (changedControllers.isEmpty()) return;
                log.info("변경된 Express 라우트 {}개 감지, API 명세서 업데이트 시작: {}", changedControllers.size(), changedControllers);
                try {
                    expressApiSpecService.processControllerUpdate(spaceKey, projectMapping, changedControllers, branch);
                } catch (Exception e) {
                    log.error("Express API 명세서 업데이트 실패", e);
                }
            }
            case "django" -> {
                changedControllers = djangoApiSpecService.filterChangedViewFiles(changedFiles);
                if (changedControllers.isEmpty()) return;
                log.info("변경된 Django 뷰 {}개 감지, API 명세서 업데이트 시작: {}", changedControllers.size(), changedControllers);
                try {
                    djangoApiSpecService.processControllerUpdate(spaceKey, projectMapping, changedControllers, branch);
                } catch (Exception e) {
                    log.error("Django API 명세서 업데이트 실패", e);
                }
            }
            default -> {
                changedControllers = changedFiles.stream()
                        .filter(f -> f.contains("/controller/") && f.endsWith("Controller.java"))
                        .filter(f -> !f.contains("ErrorHandler") && !f.contains("CustomError"))
                        .toList();
                if (changedControllers.isEmpty()) return;
                log.info("변경된 Controller {}개 감지, API 명세서 업데이트 시작: {}", changedControllers.size(), changedControllers);
                try {
                    apiSpecInitializationService.processControllerUpdate(spaceKey, projectMapping, changedControllers, branch);
                } catch (Exception e) {
                    log.error("Controller API 명세서 업데이트 실패", e);
                }
            }
        }
    }

    private void processPage(ConfluenceStructure.Page page, Long projectId, String projectName,
                             String branch, List<String> changedFiles,
                             String spaceKey, List<String> parentPages, String titlePrefix) throws Exception {
        String baseTitle = configLoaderService.renderTitle(page, projectName);
        String pageTitle = (titlePrefix != null && !titlePrefix.isBlank())
                ? titlePrefix + " " + baseTitle
                : baseTitle;
        String templateName = page.getPromptTemplate();

        log.info("페이지 처리 시작: title={}, template={}", pageTitle, templateName);

        String generatedDoc;

        if (page.getSourceFile() != null && !page.getSourceFile().isBlank()) {
            // source_file이 지정된 경우: 해당 파일을 GitLab에서 직접 읽어서 LLM으로 변환
            generatedDoc = processSourceFile(page, projectId, projectName, branch);
        } else {
            // 기존 방식: 변경 파일 수집 → LLM 분석
            String codeContent = collectFileContents(projectId, branch, changedFiles, page.getTargetFiles());
            String prompt = promptTemplateService.getPrompt(templateName, codeContent, projectName);
            generatedDoc = cleanLlmResponse(llmRouter.generate(prompt));
        }

        // Target: Confluence 계층 경로 아래에 문서 Upsert
        confluenceTargetService.upsertPageUnderParents(
                spaceKey, parentPages, pageTitle, generatedDoc, page.isAppendMode());

        log.info("페이지 처리 완료: title={}", pageTitle);
    }

    /**
     * source_file로 지정된 파일을 GitLab에서 읽어 LLM으로 Confluence 형식으로 변환합니다.
     * 파일이 없으면 안내 메시지를 반환합니다.
     */
    private String processSourceFile(ConfluenceStructure.Page page, Long projectId,
                                     String projectName, String branch) {
        String sourceFile = page.getSourceFile();
        log.info("소스 파일 조회: project={}, file={}, branch={}", projectName, sourceFile, branch);

        try {
            String content = gitLabSourceService.getFileContent(projectId, sourceFile, branch);

            if (content == null || content.isBlank()) {
                log.warn("소스 파일이 비어있음: {}", sourceFile);
                return "<p>해당 프로젝트의 " + sourceFile + " 파일이 비어있습니다.</p>";
            }

            // LLM으로 마크다운 → Confluence 형식 변환
            String prompt = promptTemplateService.getPrompt(page.getPromptTemplate(), content, projectName);
            return cleanLlmResponse(llmRouter.generate(prompt));

        } catch (Exception e) {
            log.warn("소스 파일 조회 실패: {} ({})", sourceFile, e.getMessage());
            return "<p>해당 프로젝트에는 " + sourceFile + " 파일이 없습니다.</p>";
        }
    }

    /**
     * 변경된 파일 중 targetFiles 패턴에 매칭되는 파일들의 내용을 수집합니다.
     */
    private String collectFileContents(Long projectId, String branch,
                                       List<String> changedFiles, List<String> targetFilePatterns) throws Exception {
        org.springframework.util.AntPathMatcher pathMatcher = new org.springframework.util.AntPathMatcher();

        List<String> matchedFiles = changedFiles.stream()
                .filter(file -> targetFilePatterns == null || targetFilePatterns.stream()
                        .anyMatch(pattern -> pathMatcher.match(pattern, file)))
                .collect(Collectors.toList());

        StringBuilder sb = new StringBuilder();
        for (String filePath : matchedFiles) {
            try {
                String content = gitLabSourceService.getFileContent(projectId, filePath, branch);
                sb.append("=== ").append(filePath).append(" ===\n");
                sb.append(content).append("\n\n");
            } catch (Exception e) {
                log.warn("파일 내용 수집 실패: {}, 건너뜀", filePath, e);
            }
        }

        return sb.toString();
    }

    /**
     * LLM 응답에서 코드블록 마커를 제거하고, Confluence Storage Format으로 정리합니다.
     */
    private String cleanLlmResponse(String response) {
        if (response == null) return "<p></p>";
        String cleaned = response.strip();

        // ```xml 또는 ``` 으로 시작하는 코드블록 마커 제거
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            if (firstNewline != -1) {
                cleaned = cleaned.substring(firstNewline + 1);
            }
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3).strip();
        }

        // <?xml ... ?> 선언 제거 (Confluence Storage Format에서는 불필요)
        cleaned = cleaned.replaceAll("<\\?xml[^?]*\\?>", "").strip();

        // 빈 응답 방어
        if (cleaned.isEmpty()) {
            return "<p>문서 생성에 실패했습니다. 다시 시도해주세요.</p>";
        }

        return cleaned;
    }
}
