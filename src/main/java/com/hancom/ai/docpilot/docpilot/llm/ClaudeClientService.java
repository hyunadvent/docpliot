package com.hancom.ai.docpilot.docpilot.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ClaudeClientService {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final WebClient webClient;

    public ClaudeClientService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder
                .baseUrl(API_URL)
                .build();
    }

    /**
     * Claude API를 호출하여 응답 텍스트를 반환합니다.
     */
    public String generate(String prompt, String apiKey, String model) {
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "max_tokens", 4096,
                "messages", List.of(Map.of("role", "user", "content", prompt))
        );

        log.debug("Claude API 호출: model={}", model);

        Map<String, Object> response = webClient.post()
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        return extractText(response);
    }

    @SuppressWarnings("unchecked")
    private String extractText(Map<String, Object> response) {
        List<Map<String, Object>> content = (List<Map<String, Object>>) response.get("content");
        return (String) content.get(0).get("text");
    }
}
