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
        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        return (String) message.get("content");
    }
}
