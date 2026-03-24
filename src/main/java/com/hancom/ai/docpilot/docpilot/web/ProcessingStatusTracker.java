package com.hancom.ai.docpilot.docpilot.web;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 현재 API 명세서 생성 중인 Controller를 추적합니다.
 * UI에서의 동시 실행 수를 최대 2개로 제한합니다.
 */
@Component
public class ProcessingStatusTracker {

    private static final int MAX_CONCURRENT_UI = 2;
    private final Set<String> processingControllers = ConcurrentHashMap.newKeySet();

    public boolean markProcessing(String controllerPath) {
        // 이미 처리 중인 경우 중복 방지
        if (processingControllers.contains(controllerPath)) return false;
        processingControllers.add(controllerPath);
        return true;
    }

    public void markDone(String controllerPath) {
        processingControllers.remove(controllerPath);
    }

    public boolean isProcessing(String controllerPath) {
        return processingControllers.contains(controllerPath);
    }

    public boolean canAcceptMore() {
        return processingControllers.size() < MAX_CONCURRENT_UI;
    }

    public int getActiveCount() {
        return processingControllers.size();
    }

    public Set<String> getProcessingControllers() {
        return Collections.unmodifiableSet(processingControllers);
    }
}
