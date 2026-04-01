package com.hancom.ai.docpilot.docpilot.web;

import com.hancom.ai.docpilot.docpilot.config.ConfigLoaderService;
import com.hancom.ai.docpilot.docpilot.config.SystemSettingService;
import com.hancom.ai.docpilot.docpilot.config.model.ConfluenceStructure;
import com.hancom.ai.docpilot.docpilot.web.dto.ControllerSpecSummary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Collections;
import java.util.List;

@Controller
public class PageController {

    private final ConfigLoaderService configLoaderService;
    private final SystemSettingService settingService;
    private final ApiSpecService apiSpecService;
    private final WebhookEventStore webhookEventStore;
    private final ProjectApiController projectApiController;

    @Value("${server.port:8080}")
    private int serverPort;

    public PageController(ConfigLoaderService configLoaderService,
                          SystemSettingService settingService,
                          ApiSpecService apiSpecService,
                          WebhookEventStore webhookEventStore,
                          ProjectApiController projectApiController) {
        this.configLoaderService = configLoaderService;
        this.settingService = settingService;
        this.apiSpecService = apiSpecService;
        this.webhookEventStore = webhookEventStore;
        this.projectApiController = projectApiController;
    }

    @GetMapping("/projects")
    public String projects(Model model, Authentication auth) {
        ConfluenceStructure structure = configLoaderService.getConfluenceStructure();
        List<ConfluenceStructure.ProjectMapping> projects = projectApiController.getAccessibleProjects(auth);

        model.addAttribute("menu", "projects");
        model.addAttribute("projects", projects);
        model.addAttribute("commonLibraries", structure.getCommonLibraries() != null ? structure.getCommonLibraries() : java.util.Collections.emptyList());
        model.addAttribute("branches", structure.getBranches());
        model.addAttribute("options", structure.getOptions());
        return "projects";
    }

    @GetMapping("/api-specs")
    public String apiSpecs(Model model, Authentication auth,
                           @RequestParam(required = false) Long projectId) {
        List<ConfluenceStructure.ProjectMapping> projects = projectApiController.getAccessibleProjects(auth);

        model.addAttribute("menu", "api-specs");
        model.addAttribute("projects", projects);
        model.addAttribute("confluenceUrl", settingService.get(SystemSettingService.CONFLUENCE_URL));

        if (projectId != null) {
            model.addAttribute("selectedProjectId", projectId);
            populateControllerSpecs(model, projectId);
        } else if (!projects.isEmpty()) {
            Long firstId = projects.get(0).getGitlabProjectId();
            model.addAttribute("selectedProjectId", firstId);
            populateControllerSpecs(model, firstId);
        } else {
            model.addAttribute("controllerSpecs", Collections.emptyList());
            model.addAttribute("processedCount", 0);
            model.addAttribute("totalCount", 0);
        }

        return "api-specs";
    }

    private void populateControllerSpecs(Model model, Long projectId) {
        List<ControllerSpecSummary> specs = apiSpecService.getControllerSpecs(projectId);
        model.addAttribute("controllerSpecs", specs);
        long processed = specs.stream().filter(ControllerSpecSummary::isProcessed).count();
        model.addAttribute("processedCount", processed);
        model.addAttribute("totalCount", specs.size());
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
    public String settings(Model model) {
        model.addAttribute("menu", "settings");
        model.addAttribute("webhookUrl", "http://localhost:" + serverPort + "/webhook/gitlab");
        return "settings";
    }
}
