package com.hancom.ai.docpilot.docpilot.web.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ControllerSpecSummary {
    private String controllerPath;
    private String controllerName;
    private String pageTitle;
    private Long pageId;
    private boolean processed;
    private int apiCount;
    private List<ApiPageInfo> apiPages;

    @Data
    @Builder
    public static class ApiPageInfo {
        private String title;
        private Long pageId;
    }
}
