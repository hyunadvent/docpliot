package com.hancom.ai.docpilot.docpilot.web;

import com.hancom.ai.docpilot.docpilot.config.ConfigLoaderService;
import com.hancom.ai.docpilot.docpilot.config.model.ConfluenceStructure;
import com.hancom.ai.docpilot.docpilot.repository.ControllerPageMappingRepository;
import com.hancom.ai.docpilot.docpilot.source.gitlab.GitLabSourceService;
import com.hancom.ai.docpilot.docpilot.target.confluence.ConfluenceTargetService;
import com.hancom.ai.docpilot.docpilot.web.dto.ProjectSummary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class DashboardService {

    private final ConfigLoaderService configLoaderService;
    private final GitLabSourceService gitLabSourceService;
    private final ConfluenceTargetService confluenceTargetService;
    private final ControllerPageMappingRepository controllerPageMappingRepository;

    public DashboardService(ConfigLoaderService configLoaderService,
                            GitLabSourceService gitLabSourceService,
                            ConfluenceTargetService confluenceTargetService,
                            ControllerPageMappingRepository controllerPageMappingRepository) {
        this.configLoaderService = configLoaderService;
        this.gitLabSourceService = gitLabSourceService;
        this.confluenceTargetService = confluenceTargetService;
        this.controllerPageMappingRepository = controllerPageMappingRepository;
    }

    public List<ProjectSummary> getProjectSummaries() {
        ConfluenceStructure structure = configLoaderService.getConfluenceStructure();
        List<ProjectSummary> summaries = new ArrayList<>();

        for (ConfluenceStructure.ProjectMapping project : structure.getProjects()) {
            try {
                summaries.add(buildProjectSummary(project));
            } catch (Exception e) {
                log.warn("프로젝트 요약 조회 실패: {}", project.getGitlabPath(), e);
                summaries.add(ProjectSummary.builder()
                        .gitlabProjectId(project.getGitlabProjectId())
                        .gitlabPath(project.getGitlabPath())
                        .pageTitlePrefix(project.getPageTitlePrefix())
                        .confluenceParentPages(project.getConfluenceParentPages())
                        .controllerTotal(0)
                        .controllerDone(0)
                        .build());
            }
        }
        return summaries;
    }

    private ProjectSummary buildProjectSummary(ConfluenceStructure.ProjectMapping project) throws Exception {
        Long projectId = project.getGitlabProjectId();

        // GitLab에서 전체 Controller 수
        String defaultBranch = gitLabSourceService.getDefaultBranch(projectId);
        List<String> controllerFiles = gitLabSourceService.findFiles(projectId, defaultBranch, "Controller.java");
        int total = (int) controllerFiles.stream()
                .filter(f -> !f.contains("ErrorHandler") && !f.contains("CustomError"))
                .filter(f -> f.contains("/controller/"))
                .count();

        // DB에서 처리 완료 수
        int done = controllerPageMappingRepository.countByGitlabProjectIdAndProcessedTrue(projectId);

        return ProjectSummary.builder()
                .gitlabProjectId(projectId)
                .gitlabPath(project.getGitlabPath())
                .pageTitlePrefix(project.getPageTitlePrefix())
                .confluenceParentPages(project.getConfluenceParentPages())
                .controllerTotal(total)
                .controllerDone(done)
                .build();
    }

    public boolean isGitlabConnected() {
        try {
            ConfluenceStructure structure = configLoaderService.getConfluenceStructure();
            if (structure.getProjects().isEmpty()) return false;
            Long projectId = structure.getProjects().get(0).getGitlabProjectId();
            gitLabSourceService.getDefaultBranch(projectId);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isConfluenceConnected() {
        try {
            String spaceKey = configLoaderService.getConfluenceStructure().getSpaceKey();
            confluenceTargetService.getPage(spaceKey, "__connection_test_nonexistent__");
            return true;
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("404")) return true;
            return false;
        }
    }
}
