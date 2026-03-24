package com.hancom.ai.docpilot.docpilot.llm;

import com.hancom.ai.docpilot.docpilot.config.ConfigLoaderService;
import com.hancom.ai.docpilot.docpilot.config.model.LLMConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class LLMRouter {

    private final ConfigLoaderService configLoaderService;
    private final ClaudeClientService claudeClientService;
    private final OpenAIClientService openAIClientService;

    public LLMRouter(ConfigLoaderService configLoaderService,
                     ClaudeClientService claudeClientService,
                     OpenAIClientService openAIClientService) {
        this.configLoaderService = configLoaderService;
        this.claudeClientService = claudeClientService;
        this.openAIClientService = openAIClientService;
    }

    /**
     * 활성 LLM에 프롬프트를 전달하고 응답을 반환합니다.
     */
    public String generate(String prompt) {
        LLMConfig.LLM activeLlm = configLoaderService.getActiveLLM();
        String name = activeLlm.getName();
        String apiKey = activeLlm.getApiKey();
        String model = activeLlm.getModel();

        log.info("LLM 호출: provider={}, model={}", name, model);

        return switch (name) {
            case "claude" -> claudeClientService.generate(prompt, apiKey, model);
            case "openai" -> openAIClientService.generate(prompt, apiKey, model);
            default -> throw new IllegalStateException("지원하지 않는 LLM: " + name);
        };
    }

    /**
     * 런타임 중 llm_config.json 변경 사항을 반영합니다.
     */
    public void reloadConfig() {
        configLoaderService.reload();
        log.info("LLM 설정 재로드 완료");
    }
}
