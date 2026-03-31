package com.hancom.ai.docpilot.docpilot.webhook;

import com.hancom.ai.docpilot.docpilot.config.model.ConfluenceStructure;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Slf4j
@Service
public class ExpressApiSpecService {

    public void initializeApiSpecForProject(String spaceKey, ConfluenceStructure.ProjectMapping project, int maxCount) {
        log.warn("Express API 명세서 생성은 아직 지원되지 않습니다. project={}", project.getGitlabPath());
    }

    public void processControllerUpdate(String spaceKey, ConfluenceStructure.ProjectMapping project,
                                         List<String> controllerPaths, String branch) {
        log.warn("Express API 명세서 업데이트는 아직 지원되지 않습니다. project={}", project.getGitlabPath());
    }

    public List<String> findRouteFiles(Long projectId, String branch) {
        log.warn("Express 라우트 파일 탐색은 아직 지원되지 않습니다. projectId={}", projectId);
        return Collections.emptyList();
    }

    public List<String> filterChangedRouteFiles(List<String> changedFiles) {
        return changedFiles.stream()
                .filter(f -> (f.contains("/routes/") || f.contains("/controllers/"))
                        && (f.endsWith(".js") || f.endsWith(".ts")))
                .toList();
    }
}
