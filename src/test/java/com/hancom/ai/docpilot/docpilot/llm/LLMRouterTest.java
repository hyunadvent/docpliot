package com.hancom.ai.docpilot.docpilot.llm;

import com.hancom.ai.docpilot.docpilot.config.ConfigLoaderService;
import com.hancom.ai.docpilot.docpilot.config.model.LLMConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LLMRouterTest {

    @Mock
    private ConfigLoaderService configLoaderService;

    @Mock
    private ClaudeClientService claudeClientService;

    @Mock
    private OpenAIClientService openAIClientService;

    @InjectMocks
    private LLMRouter llmRouter;

    @Test
    void claude가_active이면_ClaudeClientService_호출() {
        LLMConfig.LLM activeLlm = new LLMConfig.LLM();
        activeLlm.setName("claude");
        activeLlm.setApiKey("sk-ant-test");
        activeLlm.setModel("claude-sonnet-4-5-20250514");
        activeLlm.setActive(true);

        when(configLoaderService.getActiveLLM()).thenReturn(activeLlm);
        when(claudeClientService.generate("test prompt", "sk-ant-test", "claude-sonnet-4-5-20250514"))
                .thenReturn("claude response");

        String result = llmRouter.generate("test prompt");

        assertEquals("claude response", result);
        verify(claudeClientService).generate("test prompt", "sk-ant-test", "claude-sonnet-4-5-20250514");
        verify(openAIClientService, never()).generate(any(), any(), any());
    }

    @Test
    void openai가_active이면_OpenAIClientService_호출() {
        LLMConfig.LLM activeLlm = new LLMConfig.LLM();
        activeLlm.setName("openai");
        activeLlm.setApiKey("sk-test");
        activeLlm.setModel("gpt-4o");
        activeLlm.setActive(true);

        when(configLoaderService.getActiveLLM()).thenReturn(activeLlm);
        when(openAIClientService.generate("test prompt", "sk-test", "gpt-4o"))
                .thenReturn("openai response");

        String result = llmRouter.generate("test prompt");

        assertEquals("openai response", result);
        verify(openAIClientService).generate("test prompt", "sk-test", "gpt-4o");
        verify(claudeClientService, never()).generate(any(), any(), any());
    }

    @Test
    void 지원하지_않는_LLM이면_예외() {
        LLMConfig.LLM activeLlm = new LLMConfig.LLM();
        activeLlm.setName("unknown");
        activeLlm.setApiKey("key");
        activeLlm.setModel("model");
        activeLlm.setActive(true);

        when(configLoaderService.getActiveLLM()).thenReturn(activeLlm);

        assertThrows(IllegalStateException.class, () -> llmRouter.generate("test"));
    }

    @Test
    void reloadConfig_호출시_ConfigLoaderService_reload_호출() {
        llmRouter.reloadConfig();
        verify(configLoaderService).reload();
    }
}
