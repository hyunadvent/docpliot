package com.hancom.ai.docpilot.docpilot.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hancom.ai.docpilot.docpilot.config.model.ConfluenceStructure;
import com.hancom.ai.docpilot.docpilot.config.model.LLMConfig;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.AntPathMatcher;

import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class ConfigLoaderService {

    private final ObjectMapper objectMapper;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Value("${config.llm-config-path}")
    private String llmConfigPath;

    @Value("${config.structure-config-path}")
    private String structureConfigPath;

    @Getter
    private LLMConfig llmConfig;

    @Getter
    private ConfluenceStructure confluenceStructure;

    public ConfigLoaderService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        reload();
    }

    /**
     * 설정 파일을 (재)로드합니다. 런타임 중 설정 변경 반영 시 호출합니다.
     */
    public void reload() {
        try {
            llmConfig = objectMapper.readValue(new File(llmConfigPath), LLMConfig.class);
            log.info("LLM 설정 로드 완료: {}개 항목", llmConfig.getLlms().size());
        } catch (IOException e) {
            throw new IllegalStateException("llm_config.json 로드 실패: " + llmConfigPath, e);
        }

        try {
            confluenceStructure = objectMapper.readValue(new File(structureConfigPath), ConfluenceStructure.class);
            log.info("Confluence 구조 설정 로드 완료: space={}, 프로젝트 {}개",
                    confluenceStructure.getSpaceKey(), confluenceStructure.getProjects().size());
        } catch (IOException e) {
            throw new IllegalStateException("page_structure_config.json 로드 실패: " + structureConfigPath, e);
        }

        // active LLM 검증
        getActiveLLM();
    }

    /**
     * active: true인 LLM을 반환합니다. 0개 또는 2개 이상이면 예외를 발생시킵니다.
     */
    public LLMConfig.LLM getActiveLLM() {
        List<LLMConfig.LLM> activeLlms = llmConfig.getLlms().stream()
                .filter(LLMConfig.LLM::isActive)
                .toList();

        if (activeLlms.size() != 1) {
            throw new IllegalStateException(
                    "active LLM이 정확히 1개여야 합니다. 현재: " + activeLlms.size() + "개");
        }

        return activeLlms.get(0);
    }

    /**
     * GitLab 프로젝트 ID로 매핑된 프로젝트 설정을 찾습니다. 없으면 null.
     */
    public ConfluenceStructure.ProjectMapping getProjectMapping(Long gitlabProjectId) {
        return confluenceStructure.getProjects().stream()
                .filter(p -> p.getGitlabProjectId().equals(gitlabProjectId))
                .findFirst()
                .orElse(null);
    }

    /**
     * 해당 브랜치가 트리거 대상인지 확인합니다.
     * ignore 패턴에 먼저 매칭되면 false, 이후 trigger 패턴에 매칭되면 true.
     */
    public boolean isTriggerBranch(String branch) {
        ConfluenceStructure.Branches branches = confluenceStructure.getBranches();

        // ignore 패턴 우선 확인
        if (branches.getIgnoreBranches() != null) {
            for (String pattern : branches.getIgnoreBranches()) {
                if (pathMatcher.match(pattern, branch)) {
                    log.debug("브랜치 '{}' → ignore 패턴 '{}' 매칭, 무시", branch, pattern);
                    return false;
                }
            }
        }

        // trigger 패턴 확인
        if (branches.getTriggerBranches() != null) {
            for (String pattern : branches.getTriggerBranches()) {
                if (pathMatcher.match(pattern, branch)) {
                    log.debug("브랜치 '{}' → trigger 패턴 '{}' 매칭", branch, pattern);
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * 프로젝트 매핑 내에서 이벤트 유형과 변경 파일 목록을 기준으로 업데이트가 필요한 페이지 목록을 반환합니다.
     */
    public List<ConfluenceStructure.Page> getPagesForEvent(ConfluenceStructure.ProjectMapping project,
                                                           String eventType, List<String> changedFiles) {
        List<ConfluenceStructure.Page> result = new ArrayList<>();
        if (project.getPages() == null) return result;

        for (ConfluenceStructure.Page page : project.getPages()) {
            if (page.isAutoGenerate() && isTriggeredBy(page, eventType, changedFiles)) {
                result.add(page);
            }
        }
        return result;
    }

    /**
     * 하위 호환: 전체 프로젝트에서 페이지 검색 (프로젝트 ID 없이 호출 시)
     */
    public List<ConfluenceStructure.Page> getPagesForEvent(String eventType, List<String> changedFiles) {
        List<ConfluenceStructure.Page> result = new ArrayList<>();
        for (ConfluenceStructure.ProjectMapping project : confluenceStructure.getProjects()) {
            result.addAll(getPagesForEvent(project, eventType, changedFiles));
        }
        return result;
    }

    private boolean isTriggeredBy(ConfluenceStructure.Page page, String eventType, List<String> changedFiles) {
        // trigger 이벤트 유형 확인
        if (page.getTrigger() == null || !page.getTrigger().contains(eventType)) {
            return false;
        }

        // target_files 패턴과 변경 파일 매칭
        if (page.getTargetFiles() == null || page.getTargetFiles().isEmpty()) {
            return true;
        }

        for (String changedFile : changedFiles) {
            for (String pattern : page.getTargetFiles()) {
                if (pathMatcher.match(pattern, changedFile)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * LLM 설정을 JSON 파일에 저장하고 리로드합니다.
     */
    public void saveLlmConfig(LLMConfig config) {
        try {
            objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
            objectMapper.writeValue(new File(llmConfigPath), config);
            this.llmConfig = config;
            log.info("LLM 설정 저장 완료: {}개 항목", config.getLlms().size());
        } catch (IOException e) {
            throw new IllegalStateException("llm_config.json 저장 실패: " + llmConfigPath, e);
        }
    }

    /**
     * Confluence 구조 설정을 JSON 파일에 저장하고 리로드합니다.
     */
    public void saveConfluenceStructure(ConfluenceStructure structure) {
        try {
            objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
            objectMapper.writeValue(new File(structureConfigPath), structure);
            this.confluenceStructure = structure;
            log.info("Confluence 구조 설정 저장 완료: 프로젝트 {}개", structure.getProjects().size());
        } catch (IOException e) {
            throw new IllegalStateException("page_structure_config.json 저장 실패: " + structureConfigPath, e);
        }
    }

    /**
     * 페이지 제목의 {project_name} 플레이스홀더를 실제 프로젝트명으로 치환합니다.
     */
    public String renderTitle(ConfluenceStructure.Page page, String projectName) {
        if (page.getTitle() == null) return projectName;
        return page.getTitle().replace("{project_name}", projectName);
    }
}
