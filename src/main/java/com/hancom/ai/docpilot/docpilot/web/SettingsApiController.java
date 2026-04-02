package com.hancom.ai.docpilot.docpilot.web;

import com.hancom.ai.docpilot.docpilot.config.SystemSettingService;
import com.hancom.ai.docpilot.docpilot.source.gitlab.GitLabSourceService;
import com.hancom.ai.docpilot.docpilot.target.confluence.ConfluenceTargetService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/settings")
public class SettingsApiController {

    private final SystemSettingService settingService;
    private final GitLabSourceService gitLabSourceService;
    private final ConfluenceTargetService confluenceTargetService;

    public SettingsApiController(SystemSettingService settingService,
                                 GitLabSourceService gitLabSourceService,
                                 ConfluenceTargetService confluenceTargetService) {
        this.settingService = settingService;
        this.gitLabSourceService = gitLabSourceService;
        this.confluenceTargetService = confluenceTargetService;
    }

    @GetMapping
    public ResponseEntity<Map<String, String>> getAll() {
        return ResponseEntity.ok(settingService.getAll());
    }

    @PutMapping
    public ResponseEntity<Map<String, String>> updateAll(@RequestBody Map<String, String> settings) {
        settingService.updateAll(settings);

        // GitLab/Confluence 설정이 변경되었으면 클라이언트 재생성
        if (settings.containsKey(SystemSettingService.GITLAB_URL)
                || settings.containsKey(SystemSettingService.GITLAB_TOKEN)) {
            gitLabSourceService.resetGitLabApi();
            log.info("GitLab API 클라이언트 재생성 예약됨");
        }
        if (settings.containsKey(SystemSettingService.CONFLUENCE_URL)
                || settings.containsKey(SystemSettingService.CONFLUENCE_USERNAME)
                || settings.containsKey(SystemSettingService.CONFLUENCE_PASSWORD)
                || settings.containsKey(SystemSettingService.CONFLUENCE_PAT)) {
            confluenceTargetService.resetWebClient();
            log.info("Confluence WebClient 재생성 예약됨");
        }

        return ResponseEntity.ok(Map.of("message", "설정이 저장되었습니다. 변경 사항이 즉시 반영됩니다."));
    }
}
