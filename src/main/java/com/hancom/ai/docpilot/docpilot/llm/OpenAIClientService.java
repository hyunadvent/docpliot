package com.hancom.ai.docpilot.docpilot.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class OpenAIClientService {

    private static final String API_URL = "https://api.openai.com/v1/chat/completions";
    private static final int MAX_TOKENS = 16384;
    private static final int MAX_CONTINUATIONS = 3;

    private final WebClient webClient;

    public OpenAIClientService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder
                .baseUrl(API_URL)
                .build();
    }

    /**
     * OpenAI API를 호출하여 응답 텍스트를 반환합니다.
     * finish_reason이 length인 경우 이어서 생성을 계속합니다.
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

            log.debug("OpenAI API 호출: model={}, attempt={}", model, i + 1);

            Map<String, Object> response = webClient.post()
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            String text = extractText(response);
            if (text == null) break;

            fullResponse.append(text);

            String finishReason = extractFinishReason(response);

            if ("stop".equals(finishReason) || finishReason == null) {
                break;
            }

            if ("length".equals(finishReason)) {
                log.warn("OpenAI 응답이 max_tokens({})에 도달하여 이어서 생성합니다. ({}회차)", MAX_TOKENS, i + 1);

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

    @SuppressWarnings("unchecked")
    private String extractFinishReason(Map<String, Object> response) {
        if (response == null) return null;
        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
        if (choices == null || choices.isEmpty()) return null;
        return (String) choices.get(0).get("finish_reason");
    }
}
