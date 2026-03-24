package com.hancom.ai.docpilot.docpilot.llm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PromptTemplateServiceTest {

    private PromptTemplateService promptTemplateService;

    @BeforeEach
    void setUp() {
        promptTemplateService = new PromptTemplateService();
    }

    @Test
    void service_overview_프롬프트_생성() {
        String prompt = promptTemplateService.getPrompt("service_overview", "public class Main {}", "MyProject");
        assertTrue(prompt.contains("MyProject"));
        assertTrue(prompt.contains("public class Main {}"));
        assertTrue(prompt.contains("Confluence Storage Format"));
    }

    @Test
    void api_spec_프롬프트_생성() {
        String prompt = promptTemplateService.getPrompt("api_spec", "@GetMapping(\"/users\")", "MyProject");
        assertTrue(prompt.contains("REST API"));
        assertTrue(prompt.contains("@GetMapping(\"/users\")"));
    }

    @Test
    void db_schema_프롬프트_생성() {
        String prompt = promptTemplateService.getPrompt("db_schema", "CREATE TABLE users", "MyProject");
        assertTrue(prompt.contains("DB 스키마"));
        assertTrue(prompt.contains("CREATE TABLE users"));
    }

    @Test
    void changelog_프롬프트_생성() {
        String prompt = promptTemplateService.getPrompt("changelog", "fix: 로그인 버그 수정", "MyProject");
        assertTrue(prompt.contains("변경 이력"));
        assertTrue(prompt.contains("fix: 로그인 버그 수정"));
    }

    @Test
    void weekly_report_프롬프트_생성() {
        String prompt = promptTemplateService.getPrompt("weekly_report", "ISSUE-123: 완료", "MyProject");
        assertTrue(prompt.contains("주간업무"));
        assertTrue(prompt.contains("ISSUE-123: 완료"));
    }

    @Test
    void 지원하지_않는_템플릿이면_예외() {
        assertThrows(IllegalArgumentException.class,
                () -> promptTemplateService.getPrompt("invalid_template", "code", "MyProject"));
    }

    @Test
    void 코드가_16000자_초과시_잘라냄() {
        String longCode = "x".repeat(20000);
        String prompt = promptTemplateService.getPrompt("service_overview", longCode, "MyProject");
        assertTrue(prompt.contains("...(이하 생략)"));
        // 잘린 코드는 16000자 + 접미사
        assertFalse(prompt.contains("x".repeat(20000)));
    }

    @Test
    void 코드가_8000자_이하면_그대로() {
        String shortCode = "x".repeat(100);
        String prompt = promptTemplateService.getPrompt("service_overview", shortCode, "MyProject");
        assertFalse(prompt.contains("...(이하 생략)"));
        assertTrue(prompt.contains(shortCode));
    }

    @Test
    void 코드가_null이면_빈문자열_처리() {
        String prompt = promptTemplateService.getPrompt("service_overview", null, "MyProject");
        assertNotNull(prompt);
        assertTrue(prompt.contains("MyProject"));
    }

    @Test
    void 모든_프롬프트에_Confluence_Storage_Format_명시() {
        String[] templates = {"service_overview", "api_spec", "db_schema", "changelog", "weekly_report"};
        for (String template : templates) {
            String prompt = promptTemplateService.getPrompt(template, "code", "Test");
            assertTrue(prompt.contains("Confluence Storage Format"),
                    template + " 템플릿에 Confluence Storage Format 명시 누락");
        }
    }

    @Test
    void LLM_분석_프롬프트에_한국어_작성_지시_포함() {
        // service_overview는 README 변환용이므로 한국어 지시 불필요
        String[] templates = {"api_spec", "db_schema", "changelog", "weekly_report"};
        for (String template : templates) {
            String prompt = promptTemplateService.getPrompt(template, "code", "Test");
            assertTrue(prompt.contains("한국어"),
                    template + " 템플릿에 한국어 작성 지시 누락");
        }
    }

    @Test
    void service_overview는_README_변환_프롬프트() {
        String prompt = promptTemplateService.getPrompt("service_overview", "# Hello", "Test");
        assertTrue(prompt.contains("README.md"));
        assertTrue(prompt.contains("변환"));
        assertTrue(prompt.contains("# Hello"));
    }
}
