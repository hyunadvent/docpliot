package com.hancom.ai.docpilot.docpilot.web;

import com.hancom.ai.docpilot.docpilot.config.ConfigLoaderService;
import com.hancom.ai.docpilot.docpilot.config.model.ConfluenceStructure;
import com.hancom.ai.docpilot.docpilot.config.model.LLMConfig;
import com.hancom.ai.docpilot.docpilot.web.dto.ProjectSummary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Controller
public class DashboardController {

    private final ConfigLoaderService configLoaderService;
    private final DashboardService dashboardService;

    public DashboardController(ConfigLoaderService configLoaderService,
                               DashboardService dashboardService) {
        this.configLoaderService = configLoaderService;
        this.dashboardService = dashboardService;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        ConfluenceStructure structure = configLoaderService.getConfluenceStructure();
        List<ConfluenceStructure.ProjectMapping> projects = structure.getProjects();

        // 프로젝트별 요약 정보
        List<ProjectSummary> summaries = dashboardService.getProjectSummaries();

        int controllerTotal = summaries.stream().mapToInt(ProjectSummary::getControllerTotal).sum();
        int controllerDone = summaries.stream().mapToInt(ProjectSummary::getControllerDone).sum();

        model.addAttribute("menu", "dashboard");
        model.addAttribute("projectCount", projects.size());
        model.addAttribute("controllerTotal", controllerTotal);
        model.addAttribute("controllerDone", controllerDone);
        model.addAttribute("controllerPending", controllerTotal - controllerDone);
        model.addAttribute("projects", summaries);

        // 연결 상태
        model.addAttribute("gitlabConnected", dashboardService.isGitlabConnected());
        model.addAttribute("confluenceConnected", dashboardService.isConfluenceConnected());

        try {
            LLMConfig.LLM activeLlm = configLoaderService.getActiveLLM();
            model.addAttribute("llmConfigured", true);
            model.addAttribute("activeLlmName", activeLlm.getName() + " / " + activeLlm.getModel());
        } catch (Exception e) {
            model.addAttribute("llmConfigured", false);
            model.addAttribute("activeLlmName", "미설정");
        }

        return "dashboard";
    }
}
