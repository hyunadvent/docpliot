package com.hancom.ai.docpilot.docpilot.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "config.llm-config-path=src/test/resources/config/test_llm_config.json",
        "config.structure-config-path=src/test/resources/config/test_page_structure_config.json",
        "gitlab.webhook-secret=test-secret",
        "gitlab.url=https://fake-gitlab.com",
        "gitlab.private-token=fake-token"
})
class GitLabWebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DocumentPipelineService documentPipelineService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // === 토큰 검증 테스트 ===

    @Test
    void 토큰_없으면_401() throws Exception {
        mockMvc.perform(post("/webhook/gitlab")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Gitlab-Event", "Push Hook")
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 토큰_불일치시_401() throws Exception {
        mockMvc.perform(post("/webhook/gitlab")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Gitlab-Token", "wrong-secret")
                        .header("X-Gitlab-Event", "Push Hook")
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // === 이벤트 유형 테스트 ===

    @Test
    void 지원하지_않는_이벤트는_200_반환_후_무시() throws Exception {
        mockMvc.perform(post("/webhook/gitlab")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Gitlab-Token", "test-secret")
                        .header("X-Gitlab-Event", "Tag Push Hook")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("지원하지 않는 이벤트 유형, 무시"));

        verify(documentPipelineService, never()).processEvent(any(), any(), any(), any(), any(), any());
    }

    // === Push 이벤트 테스트 ===

    @Test
    void Push_이벤트_정상_처리() throws Exception {
        Map<String, Object> payload = Map.of(
                "ref", "refs/heads/main",
                "project_id", 1,
                "project", Map.of("name", "my-project"),
                "after", "abc123",
                "commits", List.of(Map.of(
                        "id", "abc123",
                        "message", "feat: 새 기능 추가",
                        "added", List.of("README.md"),
                        "modified", List.of("src/controllers/App.java"),
                        "removed", List.of()
                ))
        );

        mockMvc.perform(post("/webhook/gitlab")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Gitlab-Token", "test-secret")
                        .header("X-Gitlab-Event", "Push Hook")
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("처리 시작"));

        verify(documentPipelineService).processEvent(
                eq("push"), eq(1L), eq("my-project"), eq("main"),
                argThat(files -> files.contains("README.md") && files.contains("src/controllers/App.java")),
                eq("abc123"));
    }

    // === 브랜치 필터 테스트 ===

    @Test
    void 트리거_대상이_아닌_브랜치는_무시() throws Exception {
        Map<String, Object> payload = Map.of(
                "ref", "refs/heads/feature/login",
                "project_id", 1,
                "project", Map.of("name", "my-project"),
                "after", "abc123",
                "commits", List.of(Map.of(
                        "id", "abc123",
                        "message", "feat: 로그인",
                        "added", List.of("Login.java"),
                        "modified", List.of(),
                        "removed", List.of()
                ))
        );

        mockMvc.perform(post("/webhook/gitlab")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Gitlab-Token", "test-secret")
                        .header("X-Gitlab-Event", "Push Hook")
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("트리거 대상 브랜치가 아님"));

        verify(documentPipelineService, never()).processEvent(any(), any(), any(), any(), any(), any());
    }

    // === Skip keyword 테스트 ===

    @Test
    void skip_keyword_포함시_처리_건너뜀() throws Exception {
        Map<String, Object> payload = Map.of(
                "ref", "refs/heads/main",
                "project_id", 1,
                "project", Map.of("name", "my-project"),
                "after", "abc123",
                "commits", List.of(Map.of(
                        "id", "abc123",
                        "message", "docs: 수정 [skip-doc]",
                        "added", List.of(),
                        "modified", List.of("README.md"),
                        "removed", List.of()
                ))
        );

        mockMvc.perform(post("/webhook/gitlab")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Gitlab-Token", "test-secret")
                        .header("X-Gitlab-Event", "Push Hook")
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Skip keyword 감지, 처리 건너뜀"));

        verify(documentPipelineService, never()).processEvent(any(), any(), any(), any(), any(), any());
    }

    // === Merge Request 이벤트 테스트 ===

    @Test
    void MR_이벤트_정상_처리() throws Exception {
        Map<String, Object> payload = Map.of(
                "project", Map.of("id", 2, "name", "mr-project"),
                "object_attributes", Map.of(
                        "target_branch", "main",
                        "title", "MR: API 추가",
                        "last_commit", Map.of(
                                "id", "def456",
                                "message", "feat: API 추가"
                        )
                )
        );

        mockMvc.perform(post("/webhook/gitlab")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Gitlab-Token", "test-secret")
                        .header("X-Gitlab-Event", "Merge Request Hook")
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("처리 시작"));

        verify(documentPipelineService).processEvent(
                eq("merge_request"), eq(2L), eq("mr-project"), eq("main"),
                any(), eq("def456"));
    }

    // === MR skip keyword 테스트 ===

    @Test
    void MR_title에_skip_keyword_포함시_건너뜀() throws Exception {
        Map<String, Object> payload = Map.of(
                "project", Map.of("id", 2, "name", "mr-project"),
                "object_attributes", Map.of(
                        "target_branch", "main",
                        "title", "[skip-doc] 문서 수정 제외",
                        "last_commit", Map.of(
                                "id", "def456",
                                "message", "fix: 수정"
                        )
                )
        );

        mockMvc.perform(post("/webhook/gitlab")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Gitlab-Token", "test-secret")
                        .header("X-Gitlab-Event", "Merge Request Hook")
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Skip keyword 감지, 처리 건너뜀"));

        verify(documentPipelineService, never()).processEvent(any(), any(), any(), any(), any(), any());
    }
}
