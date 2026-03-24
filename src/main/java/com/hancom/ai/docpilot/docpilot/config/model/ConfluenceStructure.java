package com.hancom.ai.docpilot.docpilot.config.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class ConfluenceStructure {

    @JsonProperty("space_key")
    private String spaceKey;

    private List<ProjectMapping> projects;

    private Branches branches;

    private Options options;

    @Data
    public static class ProjectMapping {
        @JsonProperty("gitlab_project_id")
        private Long gitlabProjectId;

        @JsonProperty("gitlab_path")
        private String gitlabPath;

        @JsonProperty("confluence_parent_pages")
        private List<String> confluenceParentPages;

        @JsonProperty("page_title_prefix")
        private String pageTitlePrefix;

        private List<Page> pages;
    }

    @Data
    public static class Page {
        private String id;
        private String title;
        private String description;

        @JsonProperty("auto_generate")
        private boolean autoGenerate;

        @JsonProperty("prompt_template")
        private String promptTemplate;

        @JsonProperty("source_file")
        private String sourceFile;

        private List<String> trigger;

        @JsonProperty("target_files")
        private List<String> targetFiles;

        @JsonProperty("append_mode")
        private boolean appendMode;
    }

    @Data
    public static class Branches {
        @JsonProperty("trigger_branches")
        private List<String> triggerBranches;

        @JsonProperty("ignore_branches")
        private List<String> ignoreBranches;
    }

    @Data
    public static class Options {
        @JsonProperty("skip_keyword")
        private String skipKeyword;

        @JsonProperty("overwrite_manual_edits")
        private boolean overwriteManualEdits;
    }
}
