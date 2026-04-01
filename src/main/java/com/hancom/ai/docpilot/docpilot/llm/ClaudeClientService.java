package com.hancom.ai.docpilot.docpilot.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ClaudeClientService {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int MAX_TOKENS = 16384;
    private static final int MAX_CONTINUATIONS = 3;

    private final WebClient webClient;

    public ClaudeClientService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder
                .baseUrl(API_URL)
                .build();
    }

    /**
     * Claude API를 호출하여 응답 텍스트를 반환합니다.
     * stop_reason이 max_tokens인 경우 이어서 생성을 계속합니다.
     */
    @SuppressWarnings("unchecked")
    public String generate(String prompt, String apiKey, String model) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", prompt));

        StringBuilder fullResponse = new StringBuilder();

        for (int i = 0; i <= MAX_CONTINUATIONS; i++) {
            Map<String, Object> requestBody = Map.of(
                    "model", model,
                    "max_tokens", MAX_TOKENS,
                    "messages", messages
            );

            log.debug("Claude API 호출: model={}, attempt={}", model, i + 1);

            Map<String, Object> response = webClient.post()
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            String text = extractText(response);
            if (text == null) break;

            fullResponse.append(text);

            String stopReason = response != null ? (String) response.get("stop_reason") : null;

            if ("end_turn".equals(stopReason) || stopReason == null) {
                break;
            }

            if ("max_tokens".equals(stopReason)) {
                log.warn("Claude 응답이 max_tokens({})에 도달하여 이어서 생성합니다. ({}회차)", MAX_TOKENS, i + 1);

                if (i == MAX_CONTINUATIONS) {
                    log.warn("최대 연속 생성 횟수({})에 도달. 응답이 불완전할 수 있습니다.", MAX_CONTINUATIONS + 1);
                    break;
                }

                // 이전 응답을 assistant 메시지로 추가하고 이어서 생성 요청
                messages.add(Map.of("role", "assistant", "content", text));
                messages.add(Map.of("role", "user", "content", "이어서 작성해주세요. 이전 내용을 반복하지 말고 끊긴 부분부터 바로 이어서 작성하세요."));
            } else {
                break;
            }
        }

        return fullResponse.length() > 0 ? fullResponse.toString() : null;
    }

    @SuppressWarnings("unchecked")
    private String extractText(Map<String, Object> response) {
        if (response == null) {
            log.error("Claude API 응답이 null입니다.");
            return null;
        }

        // 에러 응답 처리
        if (response.containsKey("error")) {
            Map<String, Object> error = (Map<String, Object>) response.get("error");
            String errorMsg = error != null ? String.valueOf(error.get("message")) : "unknown";
            log.error("Claude API 에러 응답: {}", errorMsg);
            throw new RuntimeException("Claude API 에러: " + errorMsg);
        }

        List<Map<String, Object>> content = (List<Map<String, Object>>) response.get("content");
        if (content == null || content.isEmpty()) {
            log.error("Claude API 응답에 content가 없습니다: {}", response);
            return null;
        }
        return (String) content.get(0).get("text");
    }
}
