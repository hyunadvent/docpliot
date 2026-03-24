package com.hancom.ai.docpilot.docpilot.web;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 현재 API 명세서 생성 중인 Controller를 추적합니다.
 */
@Component
public class ProcessingStatusTracker {

    private final Set<String> processingControllers = ConcurrentHashMap.newKeySet();

    public void markProcessing(String controllerPath) {
        processingControllers.add(controllerPath);
    }

    public void markDone(String controllerPath) {
        processingControllers.remove(controllerPath);
    }

    public boolean isProcessing(String controllerPath) {
        return processingControllers.contains(controllerPath);
    }

    public Set<String> getProcessingControllers() {
        return Collections.unmodifiableSet(processingControllers);
    }
}
