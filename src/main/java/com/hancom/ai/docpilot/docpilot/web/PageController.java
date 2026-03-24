package com.hancom.ai.docpilot.docpilot.web;

import com.hancom.ai.docpilot.docpilot.config.ConfigLoaderService;
import com.hancom.ai.docpilot.docpilot.config.model.ConfluenceStructure;
import com.hancom.ai.docpilot.docpilot.web.dto.ControllerSpecSummary;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Collections;
import java.util.List;

@Controller
public class PageController {

    private final ConfigLoaderService configLoaderService;
    private final ApiSpecService apiSpecService;
    private final WebhookEventStore webhookEventStore;

    public PageController(ConfigLoaderService configLoaderService,
                          ApiSpecService apiSpecService,
                          WebhookEventStore webhookEventStore) {
        this.configLoaderService = configLoaderService;
        this.apiSpecService = apiSpecService;
        this.webhookEventStore = webhookEventStore;
    }

    @GetMapping("/projects")
    public String projects(Model model) {
        ConfluenceStructure structure = configLoaderService.getConfluenceStructure();
        model.addAttribute("menu", "projects");
        model.addAttribute("projects", structure.getProjects());
        model.addAttribute("spaceKey", structure.getSpaceKey());
        model.addAttribute("branches", structure.getBranches());
        model.addAttribute("options", structure.getOptions());
        return "projects";
    }

    @GetMapping("/api-specs")
    public String apiSpecs(Model model,
                           @RequestParam(required = false) Long projectId) {
        ConfluenceStructure structure = configLoaderService.getConfluenceStructure();
        List<ConfluenceStructure.ProjectMapping> projects = structure.getProjects();

        model.addAttribute("menu", "api-specs");
        model.addAttribute("projects", projects);
        model.addAttribute("confluenceUrl", getConfluenceBaseUrl());

        // 프로젝트 선택 시 Controller 현황 조회
        if (projectId != null) {
            model.addAttribute("selectedProjectId", projectId);
            List<ControllerSpecSummary> specs = apiSpecService.getControllerSpecs(projectId);
            model.addAttribute("controllerSpecs", specs);
            long processed = specs.stream().filter(ControllerSpecSummary::isProcessed).count();
            model.addAttribute("processedCount", processed);
            model.addAttribute("totalCount", specs.size());
        } else if (!projects.isEmpty()) {
            // 기본: 첫 번째 프로젝트 선택
            Long firstId = projects.get(0).getGitlabProjectId();
            model.addAttribute("selectedProjectId", firstId);
            List<ControllerSpecSummary> specs = apiSpecService.getControllerSpecs(firstId);
            model.addAttribute("controllerSpecs", specs);
            long processed = specs.stream().filter(ControllerSpecSummary::isProcessed).count();
            model.addAttribute("processedCount", processed);
            model.addAttribute("totalCount", specs.size());
        } else {
            model.addAttribute("controllerSpecs", Collections.emptyList());
            model.addAttribute("processedCount", 0);
            model.addAttribute("totalCount", 0);
        }

        return "api-specs";
    }

    private String getConfluenceBaseUrl() {
        // application.yml의 confluence.url에서 /wiki 이전 부분 추출
        try {
            return configLoaderService.getConfluenceStructure().getSpaceKey();
        } catch (Exception e) {
            return "";
        }
    }

    @GetMapping("/llm")
    public String llm(Model model) {
        model.addAttribute("menu", "llm");
        model.addAttribute("llms", configLoaderService.getLlmConfig().getLlms());
        return "llm";
    }

    @GetMapping("/webhook-logs")
    public String webhookLogs(Model model) {
        model.addAttribute("menu", "webhook-logs");
        model.addAttribute("events", webhookEventStore.getAll());
        return "webhook-logs";
    }

    @GetMapping("/settings")
    public String settings(Model model,
                           @org.springframework.beans.factory.annotation.Value("${gitlab.url}") String gitlabUrl,
                           @org.springframework.beans.factory.annotation.Value("${confluence.url}") String confluenceUrl,
                           @org.springframework.beans.factory.annotation.Value("${api-spec.template-page-title:}") String templatePageTitle,
                           @org.springframework.beans.factory.annotation.Value("${api-spec.max-controllers:0}") int maxControllers,
                           @org.springframework.beans.factory.annotation.Value("${server.port:8080}") int serverPort) {
        model.addAttribute("menu", "settings");
        model.addAttribute("gitlabUrl", gitlabUrl);
        model.addAttribute("confluenceUrl", confluenceUrl);
        model.addAttribute("templatePageTitle", templatePageTitle);
        model.addAttribute("maxControllers", maxControllers);
        model.addAttribute("webhookUrl", "http://localhost:" + serverPort + "/webhook/gitlab");
        model.addAttribute("spaceKey", configLoaderService.getConfluenceStructure().getSpaceKey());
        return "settings";
    }
}
