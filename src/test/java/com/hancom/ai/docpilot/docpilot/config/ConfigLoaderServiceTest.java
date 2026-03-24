package com.hancom.ai.docpilot.docpilot.config;

import com.hancom.ai.docpilot.docpilot.config.model.ConfluenceStructure;
import com.hancom.ai.docpilot.docpilot.config.model.LLMConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties = {
        "config.llm-config-path=src/test/resources/config/test_llm_config.json",
        "config.structure-config-path=src/test/resources/config/test_page_structure_config.json"
})
class ConfigLoaderServiceTest {

    @Autowired
    private ConfigLoaderService configLoaderService;

    // === LLM Config 테스트 ===

    @Test
    void LLM_설정이_정상_로드된다() {
        LLMConfig config = configLoaderService.getLlmConfig();
        assertNotNull(config);
        assertEquals(2, config.getLlms().size());
    }

    @Test
    void active_LLM이_정확히_1개_반환된다() {
        LLMConfig.LLM active = configLoaderService.getActiveLLM();
        assertEquals("claude", active.getName());
        assertTrue(active.isActive());
        assertEquals("sk-ant-test-key", active.getApiKey());
    }

    // === Confluence Structure 테스트 ===

    @Test
    void Confluence_구조_설정이_정상_로드된다() {
        ConfluenceStructure structure = configLoaderService.getConfluenceStructure();
        assertNotNull(structure);
        assertEquals("DEV", structure.getSpaceKey());
    }

    @Test
    void 프로젝트_매핑이_정상_로드된다() {
        ConfluenceStructure.ProjectMapping mapping = configLoaderService.getProjectMapping(1L);
        assertNotNull(mapping);
        assertEquals("test-group/test-project", mapping.getGitlabPath());
        assertEquals(List.of("프로젝트", "테스트 프로젝트"), mapping.getConfluenceParentPages());
        assertEquals(3, mapping.getPages().size());
    }

    @Test
    void 등록되지_않은_프로젝트는_null() {
        assertNull(configLoaderService.getProjectMapping(9999L));
    }

    @Test
    void options_설정이_정상_로드된다() {
        ConfluenceStructure.Options options = configLoaderService.getConfluenceStructure().getOptions();
        assertEquals("[skip-doc]", options.getSkipKeyword());
        assertFalse(options.isOverwriteManualEdits());
    }

    // === 브랜치 필터 테스트 ===

    @Test
    void trigger_브랜치_매칭() {
        assertTrue(configLoaderService.isTriggerBranch("main"));
        assertTrue(configLoaderService.isTriggerBranch("develop"));
    }

    @Test
    void ignore_브랜치는_제외된다() {
        assertFalse(configLoaderService.isTriggerBranch("feature/login"));
        assertFalse(configLoaderService.isTriggerBranch("hotfix/bug-123"));
        assertFalse(configLoaderService.isTriggerBranch("release/1.0"));
    }

    @Test
    void 미등록_브랜치는_제외된다() {
        assertFalse(configLoaderService.isTriggerBranch("test-branch"));
        assertFalse(configLoaderService.isTriggerBranch("experiment"));
    }

    // === 페이지 매칭 테스트 ===

    @Test
    void push_이벤트에_README_변경시_service_overview_매칭() {
        List<ConfluenceStructure.Page> pages = configLoaderService.getPagesForEvent(
                "push", List.of("README.md"));
        assertTrue(pages.stream().anyMatch(p -> "service_overview".equals(p.getId())));
    }

    @Test
    void push_이벤트에_controller_변경시_api_spec_매칭() {
        List<ConfluenceStructure.Page> pages = configLoaderService.getPagesForEvent(
                "push", List.of("src/controllers/UserController.java"));
        assertTrue(pages.stream().anyMatch(p -> "api_spec".equals(p.getId())));
    }

    @Test
    void push_이벤트에_sql_변경시_db_schema_매칭() {
        List<ConfluenceStructure.Page> pages = configLoaderService.getPagesForEvent(
                "push", List.of("init.sql"));
        assertTrue(pages.stream().anyMatch(p -> "db_schema".equals(p.getId())));
    }

    @Test
    void merge_request_이벤트에_db_schema는_매칭되지_않는다() {
        List<ConfluenceStructure.Page> pages = configLoaderService.getPagesForEvent(
                "merge_request", List.of("init.sql"));
        assertTrue(pages.stream().noneMatch(p -> "db_schema".equals(p.getId())));
    }

    @Test
    void 매칭되는_파일이_없으면_빈_목록() {
        List<ConfluenceStructure.Page> pages = configLoaderService.getPagesForEvent(
                "push", List.of("some/random/file.txt"));
        assertTrue(pages.isEmpty());
    }

    // === 프로젝트별 페이지 매칭 테스트 ===

    @Test
    void 프로젝트_매핑_기반_페이지_매칭() {
        ConfluenceStructure.ProjectMapping mapping = configLoaderService.getProjectMapping(1L);
        List<ConfluenceStructure.Page> pages = configLoaderService.getPagesForEvent(
                mapping, "push", List.of("README.md"));
        assertTrue(pages.stream().anyMatch(p -> "service_overview".equals(p.getId())));
    }

    // === renderTitle 테스트 ===

    @Test
    void renderTitle_플레이스홀더_치환() {
        ConfluenceStructure.Page page = new ConfluenceStructure.Page();
        page.setTitle("{project_name} 서비스");
        String rendered = configLoaderService.renderTitle(page, "MyProject");
        assertEquals("MyProject 서비스", rendered);
    }

    @Test
    void renderTitle_플레이스홀더_없으면_그대로() {
        ConfluenceStructure.ProjectMapping mapping = configLoaderService.getProjectMapping(1L);
        ConfluenceStructure.Page page = mapping.getPages().get(0);
        String rendered = configLoaderService.renderTitle(page, "MyProject");
        assertEquals("서비스 개요 및 구성도", rendered);
    }
}
