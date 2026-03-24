package com.hancom.ai.docpilot.docpilot.config.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class LLMConfig {

    private List<LLM> llms;

    @Data
    public static class LLM {
        private String name;

        @JsonProperty("api_key")
        private String apiKey;

        private String model;

        private boolean active;
    }
}
