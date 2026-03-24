package com.hancom.ai.docpilot.docpilot.web;

import com.hancom.ai.docpilot.docpilot.config.ConfigLoaderService;
import com.hancom.ai.docpilot.docpilot.config.model.LLMConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/llm")
public class LlmApiController {

    private final ConfigLoaderService configLoaderService;

    public LlmApiController(ConfigLoaderService configLoaderService) {
        this.configLoaderService = configLoaderService;
    }

    @GetMapping
    public ResponseEntity<List<LLMConfig.LLM>> list() {
        // API 키를 마스킹하여 반환
        List<LLMConfig.LLM> masked = new ArrayList<>();
        for (LLMConfig.LLM llm : configLoaderService.getLlmConfig().getLlms()) {
            LLMConfig.LLM copy = new LLMConfig.LLM();
            copy.setName(llm.getName());
            copy.setModel(llm.getModel());
            copy.setActive(llm.isActive());
            copy.setApiKey(maskApiKey(llm.getApiKey()));
            masked.add(copy);
        }
        return ResponseEntity.ok(masked);
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> add(@RequestBody LLMConfig.LLM newLlm) {
        LLMConfig config = configLoaderService.getLlmConfig();
        boolean exists = config.getLlms().stream().anyMatch(l -> l.getName().equals(newLlm.getName()));
        if (exists) {
            return ResponseEntity.badRequest().body(Map.of("message", "이미 등록된 프로바이더: " + newLlm.getName()));
        }

        List<LLMConfig.LLM> llms = new ArrayList<>(config.getLlms());
        llms.add(newLlm);
        config.setLlms(llms);

        configLoaderService.saveLlmConfig(config);
        return ResponseEntity.ok(Map.of("message", "LLM 프로바이더가 추가되었습니다."));
    }

    @PutMapping("/{name}")
    public ResponseEntity<Map<String, String>> update(@PathVariable String name,
                                                       @RequestBody LLMConfig.LLM updated) {
        LLMConfig config = configLoaderService.getLlmConfig();
        List<LLMConfig.LLM> llms = new ArrayList<>(config.getLlms());

        boolean found = false;
        for (int i = 0; i < llms.size(); i++) {
            if (llms.get(i).getName().equals(name)) {
                // API 키가 마스킹 상태면 기존 키 유지
                if (updated.getApiKey() == null || updated.getApiKey().contains("***")) {
                    updated.setApiKey(llms.get(i).getApiKey());
                }
                updated.setName(name);
                llms.set(i, updated);
                found = true;
                break;
            }
        }

        if (!found) {
            return ResponseEntity.notFound().build();
        }

        config.setLlms(llms);
        configLoaderService.saveLlmConfig(config);
        return ResponseEntity.ok(Map.of("message", "LLM 설정이 수정되었습니다."));
    }

    @PutMapping("/{name}/activate")
    public ResponseEntity<Map<String, String>> activate(@PathVariable String name) {
        LLMConfig config = configLoaderService.getLlmConfig();
        boolean found = false;

        for (LLMConfig.LLM llm : config.getLlms()) {
            if (llm.getName().equals(name)) {
                llm.setActive(true);
                found = true;
            } else {
                llm.setActive(false);
            }
        }

        if (!found) {
            return ResponseEntity.notFound().build();
        }

        configLoaderService.saveLlmConfig(config);
        return ResponseEntity.ok(Map.of("message", name + " 프로바이더가 활성화되었습니다."));
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Map<String, String>> delete(@PathVariable String name) {
        LLMConfig config = configLoaderService.getLlmConfig();
        List<LLMConfig.LLM> llms = new ArrayList<>(config.getLlms());

        boolean removed = llms.removeIf(l -> l.getName().equals(name));
        if (!removed) {
            return ResponseEntity.notFound().build();
        }

        config.setLlms(llms);
        configLoaderService.saveLlmConfig(config);
        return ResponseEntity.ok(Map.of("message", "LLM 프로바이더가 삭제되었습니다."));
    }

    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() <= 8) return "***";
        return apiKey.substring(0, 4) + "***" + apiKey.substring(apiKey.length() - 4);
    }
}
