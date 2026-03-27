package com.hancom.ai.docpilot.docpilot.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class OpenAIClientService {

    private static final String API_URL = "https://api.openai.com/v1/chat/completions";

    private final WebClient webClient;

    public OpenAIClientService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder
                .baseUrl(API_URL)
                .build();
    }

    /**
     * OpenAI API를 호출하여 응답 텍스트를 반환합니다.
     */
    public String generate(String prompt, String apiKey, String model) {
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "max_tokens", 4096,
                "messages", List.of(Map.of("role", "user", "content", prompt))
        );

        log.debug("OpenAI API 호출: model={}", model);

        Map<String, Object> response = webClient.post()
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        return extractText(response);
    }

    @SuppressWarnings("unchecked")
    private String extractText(Map<String, Object> response) {
        if (response == null) {
            log.error("OpenAI API 응답이 null입니다.");
            return null;
        }

        if (response.containsKey("error")) {
            Map<String, Object> error = (Map<String, Object>) response.get("error");
            String errorMsg = error != null ? String.valueOf(error.get("message")) : "unknown";
            log.error("OpenAI API 에러 응답: {}", errorMsg);
            throw new RuntimeException("OpenAI API 에러: " + errorMsg);
        }

        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
        if (choices == null || choices.isEmpty()) {
            log.error("OpenAI API 응답에 choices가 없습니다: {}", response);
            return null;
        }
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        return (String) message.get("content");
    }
}
