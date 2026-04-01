package com.hancom.ai.docpilot.docpilot.web;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 현재 API 명세서 생성 중인 Controller를 추적합니다.
 * UI에서의 동시 실행 수를 최대 2개로 제한합니다.
 */
@Component
public class ProcessingStatusTracker {

    private static final int MAX_CONCURRENT_UI = 2;
    private final Set<String> processingControllers = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> controllerProjectMap = new ConcurrentHashMap<>();
    private final Set<Long> initializingProjects = ConcurrentHashMap.newKeySet();
    private final Map<String, ControllerProgress> controllerProgressMap = new ConcurrentHashMap<>();

    @Data
    @AllArgsConstructor
    public static class ControllerProgress {
        private int completedApis;
        private int totalApis;
        private String currentApiName;
    }

    public boolean markProcessing(String controllerPath) {
        if (processingControllers.contains(controllerPath)) return false;
        processingControllers.add(controllerPath);
        return true;
    }

    public boolean markProcessing(String controllerPath, Long projectId) {
        if (processingControllers.contains(controllerPath)) return false;
        processingControllers.add(controllerPath);
        if (projectId != null) {
            controllerProjectMap.put(controllerPath, projectId);
        }
        return true;
    }

    public void markDone(String controllerPath) {
        processingControllers.remove(controllerPath);
        controllerProjectMap.remove(controllerPath);
        controllerProgressMap.remove(controllerPath);
    }

    public void updateProgress(String controllerPath, int completedApis, int totalApis, String currentApiName) {
        controllerProgressMap.put(controllerPath, new ControllerProgress(completedApis, totalApis, currentApiName));
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

    public Set<Long> getProcessingProjectIds() {
        Set<Long> ids = new HashSet<>(controllerProjectMap.values());
        ids.addAll(initializingProjects);
        return ids;
    }

    public Map<String, Long> getControllerProjectMap() {
        return Collections.unmodifiableMap(controllerProjectMap);
    }

    public Map<String, ControllerProgress> getControllerProgressMap() {
        return Collections.unmodifiableMap(controllerProgressMap);
    }

    public void markProjectInitializing(Long projectId) {
        initializingProjects.add(projectId);
    }

    public void markProjectInitialized(Long projectId) {
        initializingProjects.remove(projectId);
    }

    public boolean isProjectInitializing(Long projectId) {
        return initializingProjects.contains(projectId);
    }

    public Set<Long> getInitializingProjects() {
        return Collections.unmodifiableSet(initializingProjects);
    }
}
