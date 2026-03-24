package com.hancom.ai.docpilot.docpilot.web.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ProjectSummary {
    private Long gitlabProjectId;
    private String gitlabPath;
    private String pageTitlePrefix;
    private List<String> confluenceParentPages;
    private int controllerTotal;
    private int controllerDone;
}
