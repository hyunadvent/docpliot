package com.hancom.ai.docpilot.docpilot.webhook;

import com.hancom.ai.docpilot.docpilot.config.ConfigLoaderService;
import com.hancom.ai.docpilot.docpilot.config.model.ConfluenceStructure;
import com.hancom.ai.docpilot.docpilot.llm.LLMRouter;
import com.hancom.ai.docpilot.docpilot.llm.PromptTemplateService;
import com.hancom.ai.docpilot.docpilot.source.gitlab.GitLabSourceService;
import com.hancom.ai.docpilot.docpilot.target.confluence.ConfluenceTargetService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ProjectInitializationService {

    private final ConfigLoaderService configLoaderService;
    private final GitLabSourceService gitLabSourceService;
    private final LLMRouter llmRouter;
    private final PromptTemplateService promptTemplateService;
    private final ConfluenceTargetService confluenceTargetService;

    public ProjectInitializationService(ConfigLoaderService configLoaderService,
                                        GitLabSourceService gitLabSourceService,
                                        LLMRouter llmRouter,
                                        PromptTemplateService promptTemplateService,
                                        ConfluenceTargetService confluenceTargetService) {
        this.configLoaderService = configLoaderService;
        this.gitLabSourceService = gitLabSourceService;
        this.llmRouter = llmRouter;
        this.promptTemplateService = promptTemplateService;
        this.confluenceTargetService = confluenceTargetService;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(1)
    public void initialize() {
        log.info("=== 프로젝트 초기화 시작 ===");

        ConfluenceStructure structure = configLoaderService.getConfluenceStructure();
        String spaceKey = structure.getSpaceKey();

        for (ConfluenceStructure.ProjectMapping project : structure.getProjects()) {
            try {
                initializeProject(spaceKey, project);
            } catch (Exception e) {
                log.error("프로젝트 초기화 실패: gitlab_path={}", project.getGitlabPath(), e);
            }
        }

        log.info("=== 프로젝트 초기화 완료 ===");
    }

    /**
     * 프로젝트의 기본 페이지(서비스 개요 등)를 Confluence에 생성합니다.
     * 서버 시작 시 자동 호출되며, UI에서 프로젝트 추가 시에도 호출됩니다.
     */
    public void initializeProject(String spaceKey, ConfluenceStructure.ProjectMapping project) {
        log.info("프로젝트 확인: {} (id: {})", project.getGitlabPath(), project.getGitlabProjectId());

        List<String> parentPages = project.getConfluenceParentPages();
        String titlePrefix = project.getPageTitlePrefix();

        // 부모 폴더/페이지 경로 보장
        Long parentId = confluenceTargetService.ensureParentPages(spaceKey, parentPages);

        for (ConfluenceStructure.Page page : project.getPages()) {
            if (!page.isAutoGenerate()) continue;

            // source_file이 있는 페이지만 초기화 대상 (README 기반 페이지)
            if (page.getSourceFile() == null || page.getSourceFile().isBlank()) continue;

            String baseTitle = configLoaderService.renderTitle(page, project.getGitlabPath());
            String pageTitle = (titlePrefix != null && !titlePrefix.isBlank())
                    ? titlePrefix + " " + baseTitle
                    : baseTitle;

            // 이미 존재하는지 확인
            Map<String, Object> existing = confluenceTargetService.getChildPage(parentId, pageTitle);

            if (existing != null) {
                log.info("페이지 이미 존재: {}", pageTitle);
                continue;
            }

            // 페이지가 없으면 GitLab에서 source_file을 읽어 생성
            log.info("페이지 없음, 생성 시작: {}", pageTitle);
            String content = fetchSourceFileContent(project, page);

            confluenceTargetService.createPage(spaceKey, pageTitle, content, parentId);
            log.info("초기 페이지 생성 완료: {}", pageTitle);
        }
    }

    /**
     * GitLab에서 source_file을 읽고 LLM으로 Confluence 형식으로 변환합니다.
     */
    private String fetchSourceFileContent(ConfluenceStructure.ProjectMapping project,
                                          ConfluenceStructure.Page page) {
        String sourceFile = page.getSourceFile();
        Long projectId = project.getGitlabProjectId();

        try {
            String defaultBranch = gitLabSourceService.getDefaultBranch(projectId);
            log.info("GitLab default branch: {} (project: {})", defaultBranch, project.getGitlabPath());
            String content = gitLabSourceService.getFileContent(projectId, sourceFile, defaultBranch);

            if (content == null || content.isBlank()) {
                return "<p>해당 프로젝트의 " + sourceFile + " 파일이 비어있습니다.</p>";
            }

            // LLM으로 마크다운 → Confluence 형식 변환
            String prompt = promptTemplateService.getPrompt(page.getPromptTemplate(), content, project.getGitlabPath());
            String response = llmRouter.generate(prompt);
            return cleanLlmResponse(response);

        } catch (Exception e) {
            log.warn("소스 파일 조회 실패: {} ({})", sourceFile, e.getMessage());
            return "<p>해당 프로젝트에는 " + sourceFile + " 파일이 없습니다.</p>";
        }
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
            return "<p>문서 생성에 실패했습니다. 다시 시도해주세요.</p>";
        }

        return cleaned;
    }
}
