package com.hancom.ai.docpilot.docpilot.web;

import com.hancom.ai.docpilot.docpilot.config.ConfigLoaderService;
import com.hancom.ai.docpilot.docpilot.config.model.ConfluenceStructure;
import com.hancom.ai.docpilot.docpilot.entity.ApiPageMappingEntity;
import com.hancom.ai.docpilot.docpilot.entity.ControllerPageMappingEntity;
import com.hancom.ai.docpilot.docpilot.repository.ApiPageMappingRepository;
import com.hancom.ai.docpilot.docpilot.repository.ControllerPageMappingRepository;
import com.hancom.ai.docpilot.docpilot.source.gitlab.GitLabSourceService;
import com.hancom.ai.docpilot.docpilot.web.dto.ControllerSpecSummary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ApiSpecService {

    private final ConfigLoaderService configLoaderService;
    private final GitLabSourceService gitLabSourceService;
    private final ControllerPageMappingRepository controllerPageMappingRepository;
    private final ApiPageMappingRepository apiPageMappingRepository;

    public ApiSpecService(ConfigLoaderService configLoaderService,
                          GitLabSourceService gitLabSourceService,
                          ControllerPageMappingRepository controllerPageMappingRepository,
                          ApiPageMappingRepository apiPageMappingRepository) {
        this.configLoaderService = configLoaderService;
        this.gitLabSourceService = gitLabSourceService;
        this.controllerPageMappingRepository = controllerPageMappingRepository;
        this.apiPageMappingRepository = apiPageMappingRepository;
    }

    public List<ControllerSpecSummary> getControllerSpecs(Long gitlabProjectId) {
        ConfluenceStructure.ProjectMapping project = configLoaderService.getProjectMapping(gitlabProjectId);
        if (project == null) return Collections.emptyList();

        try {
            // GitLab에서 Controller 파일 목록 (source of truth)
            String defaultBranch = gitLabSourceService.getDefaultBranch(gitlabProjectId);
            List<String> controllerFiles = gitLabSourceService.findFiles(gitlabProjectId, defaultBranch, "Controller.java");
            controllerFiles = controllerFiles.stream()
                    .filter(f -> !f.contains("ErrorHandler") && !f.contains("CustomError"))
                    .filter(f -> f.contains("/controller/"))
                    .toList();

            // DB에서 매핑 정보 조회
            Map<String, ControllerPageMappingEntity> pathToMapping = controllerPageMappingRepository
                    .findByGitlabProjectId(gitlabProjectId).stream()
                    .collect(Collectors.toMap(ControllerPageMappingEntity::getControllerPath, m -> m, (a, b) -> a));

            List<ControllerSpecSummary> summaries = new ArrayList<>();
            for (String controllerPath : controllerFiles) {
                ControllerPageMappingEntity mapping = pathToMapping.get(controllerPath);

                if (mapping != null && mapping.isProcessed()) {
                    // DB에서 API 페이지 목록 조회
                    List<ApiPageMappingEntity> apiMappings = apiPageMappingRepository
                            .findByControllerMappingId(mapping.getId());

                    List<ControllerSpecSummary.ApiPageInfo> apiPages = apiMappings.stream()
                            .map(a -> ControllerSpecSummary.ApiPageInfo.builder()
                                    .title(a.getConfluencePageTitle())
                                    .pageId(a.getConfluencePageId())
                                    .build())
                            .toList();

                    summaries.add(ControllerSpecSummary.builder()
                            .controllerPath(controllerPath)
                            .controllerName(extractSimpleName(controllerPath))
                            .pageTitle(mapping.getConfluencePageTitle())
                            .pageId(mapping.getConfluencePageId())
                            .processed(true)
                            .apiCount(apiPages.size())
                            .apiPages(apiPages)
                            .build());
                } else {
                    summaries.add(ControllerSpecSummary.builder()
                            .controllerPath(controllerPath)
                            .controllerName(extractSimpleName(controllerPath))
                            .processed(false)
                            .apiCount(0)
                            .apiPages(Collections.emptyList())
                            .build());
                }
            }

            return summaries;

        } catch (Exception e) {
            log.error("API 명세서 현황 조회 실패: projectId={}", gitlabProjectId, e);
            return Collections.emptyList();
        }
    }

    private String extractSimpleName(String path) {
        int lastSlash = path.lastIndexOf('/');
        String fileName = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
        return fileName.replace(".java", "");
    }
}
