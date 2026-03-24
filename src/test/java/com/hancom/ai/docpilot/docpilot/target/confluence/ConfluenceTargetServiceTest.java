package com.hancom.ai.docpilot.docpilot.target.confluence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfluenceTargetServiceTest {

    private MockWebServer mockWebServer;
    private ConfluenceTargetService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        String baseUrl = mockWebServer.url("").toString();
        // baseUrl 끝의 /rest/api/content 제거 (생성자에서 붙이므로)
        String confluenceUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;

        service = new ConfluenceTargetService(
                WebClient.builder(),
                confluenceUrl.replace("/rest/api/content", ""),
                "testuser",
                "testpass",
                "test-pat-token"
        );
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    // === getPage 테스트 ===

    @Test
    void getPage_페이지_존재시_반환() throws Exception {
        Map<String, Object> pageData = Map.of(
                "id", "12345",
                "title", "테스트 페이지",
                "version", Map.of("number", 3),
                "body", Map.of("storage", Map.of("value", "<p>내용</p>"))
        );
        Map<String, Object> response = Map.of("results", List.of(pageData));
        enqueueJson(response);

        Map<String, Object> result = service.getPage("DEV", "테스트 페이지");

        assertNotNull(result);
        assertEquals("12345", result.get("id"));
        assertEquals("테스트 페이지", result.get("title"));

        RecordedRequest request = mockWebServer.takeRequest();
        assertTrue(request.getPath().contains("spaceKey=DEV"));
        assertTrue(request.getHeader("Authorization").startsWith("Bearer "));
    }

    @Test
    void getPage_페이지_없으면_null() throws Exception {
        enqueueJson(Map.of("results", List.of()));

        Map<String, Object> result = service.getPage("DEV", "없는 페이지");

        assertNull(result);
    }

    // === createPage 테스트 ===

    @Test
    void createPage_정상_생성() throws Exception {
        enqueueJson(Map.of("id", "99999", "title", "새 페이지"));

        service.createPage("DEV", "새 페이지", "<p>내용</p>", 100L);

        RecordedRequest request = mockWebServer.takeRequest();
        assertEquals("POST", request.getMethod());

        String body = request.getBody().readUtf8();
        assertTrue(body.contains("\"title\":\"새 페이지\""));
        assertTrue(body.contains("\"key\":\"DEV\""));
        assertTrue(body.contains("\"value\":\"<p>내용</p>\""));
        assertTrue(body.contains("\"ancestors\""));
    }

    @Test
    void createPage_부모없이_생성() throws Exception {
        enqueueJson(Map.of("id", "99999", "title", "루트 페이지"));

        service.createPage("DEV", "루트 페이지", "<p>내용</p>", null);

        RecordedRequest request = mockWebServer.takeRequest();
        String body = request.getBody().readUtf8();
        assertFalse(body.contains("\"ancestors\""));
    }

    // === updatePage 테스트 ===

    @Test
    void updatePage_정상_업데이트() throws Exception {
        enqueueJson(Map.of("id", "12345", "title", "업데이트 페이지"));

        service.updatePage(12345L, "업데이트 페이지", "<p>새 내용</p>", 4);

        RecordedRequest request = mockWebServer.takeRequest();
        assertEquals("PUT", request.getMethod());
        assertTrue(request.getPath().contains("/12345"));

        String body = request.getBody().readUtf8();
        assertTrue(body.contains("\"number\":4"));
        assertTrue(body.contains("\"value\":\"<p>새 내용</p>\""));
    }

    // === appendToPage 테스트 ===

    @Test
    void appendToPage_기존내용에_추가() throws Exception {
        // 1) 기존 페이지 조회 응답
        Map<String, Object> existingPage = Map.of(
                "id", "12345",
                "body", Map.of("storage", Map.of("value", "<p>기존 내용</p>"))
        );
        enqueueJson(existingPage);

        // 2) updatePage 응답
        enqueueJson(Map.of("id", "12345"));

        service.appendToPage(12345L, "추가 페이지", "<p>추가 내용</p>", 5);

        // 첫 번째 요청: GET (기존 내용 조회)
        RecordedRequest getRequest = mockWebServer.takeRequest();
        assertEquals("GET", getRequest.getMethod());

        // 두 번째 요청: PUT (병합 내용으로 업데이트)
        RecordedRequest putRequest = mockWebServer.takeRequest();
        assertEquals("PUT", putRequest.getMethod());
        String body = putRequest.getBody().readUtf8();
        assertTrue(body.contains("<p>기존 내용</p>"));
        assertTrue(body.contains("<p>추가 내용</p>"));
    }

    // === upsertPage 테스트 ===

    @Test
    void upsertPage_페이지없으면_생성() throws Exception {
        // 1) getPage(title) → 결과 없음
        enqueueJson(Map.of("results", List.of()));
        // 2) getPage(parentTitle) → 부모 존재
        enqueueJson(Map.of("results", List.of(Map.of("id", "100"))));
        // 3) createPage 응답
        enqueueJson(Map.of("id", "200", "title", "새 문서"));

        service.upsertPage("DEV", "새 문서", "<p>내용</p>", "부모 페이지", false);

        assertEquals(3, mockWebServer.getRequestCount());
        mockWebServer.takeRequest(); // getPage
        mockWebServer.takeRequest(); // getPage(parent)
        RecordedRequest createReq = mockWebServer.takeRequest();
        assertEquals("POST", createReq.getMethod());
    }

    @Test
    void upsertPage_페이지있으면_업데이트() throws Exception {
        // 1) getPage → 기존 페이지 존재
        Map<String, Object> existing = Map.of(
                "id", "12345",
                "version", Map.of("number", 3),
                "body", Map.of("storage", Map.of("value", "<p>기존</p>"))
        );
        enqueueJson(Map.of("results", List.of(existing)));
        // 2) updatePage 응답
        enqueueJson(Map.of("id", "12345"));

        service.upsertPage("DEV", "기존 문서", "<p>새 내용</p>", "부모", false);

        assertEquals(2, mockWebServer.getRequestCount());
        mockWebServer.takeRequest(); // getPage
        RecordedRequest putReq = mockWebServer.takeRequest();
        assertEquals("PUT", putReq.getMethod());
        String body = putReq.getBody().readUtf8();
        assertTrue(body.contains("\"number\":4")); // version +1
    }

    @Test
    void upsertPage_appendMode_true이면_append() throws Exception {
        // 1) getPage → 기존 페이지 존재
        Map<String, Object> existing = Map.of(
                "id", "12345",
                "version", Map.of("number", 2),
                "body", Map.of("storage", Map.of("value", "<p>기존</p>"))
        );
        enqueueJson(Map.of("results", List.of(existing)));
        // 2) appendToPage → GET 기존 내용
        enqueueJson(Map.of(
                "id", "12345",
                "body", Map.of("storage", Map.of("value", "<p>기존</p>"))
        ));
        // 3) appendToPage → PUT 업데이트
        enqueueJson(Map.of("id", "12345"));

        service.upsertPage("DEV", "추가 문서", "<p>추가</p>", "부모", true);

        assertEquals(3, mockWebServer.getRequestCount());
    }

    // === 인증 테스트 ===

    @Test
    void PAT_설정시_Bearer_인증_사용() throws Exception {
        enqueueJson(Map.of("results", List.of()));

        service.getPage("DEV", "test");

        RecordedRequest request = mockWebServer.takeRequest();
        assertEquals("Bearer test-pat-token", request.getHeader("Authorization"));
    }

    @Test
    void PAT_미설정시_Basic_인증_사용() throws Exception {
        String baseUrl = mockWebServer.url("").toString();
        String confluenceUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;

        ConfluenceTargetService basicAuthService = new ConfluenceTargetService(
                WebClient.builder(),
                confluenceUrl.replace("/rest/api/content", ""),
                "myuser",
                "mypass",
                ""  // PAT 비어있음
        );

        enqueueJson(Map.of("results", List.of()));
        basicAuthService.getPage("DEV", "test");

        RecordedRequest request = mockWebServer.takeRequest();
        String authHeader = request.getHeader("Authorization");
        assertTrue(authHeader.startsWith("Basic "));
    }

    private void enqueueJson(Map<String, Object> body) throws JsonProcessingException {
        mockWebServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(objectMapper.writeValueAsString(body)));
    }
}
