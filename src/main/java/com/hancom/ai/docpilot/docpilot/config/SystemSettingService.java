package com.hancom.ai.docpilot.docpilot.config;

import com.hancom.ai.docpilot.docpilot.entity.SystemSettingEntity;
import com.hancom.ai.docpilot.docpilot.repository.SystemSettingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class SystemSettingService {

    public static final String GITLAB_URL = "gitlab.url";
    public static final String GITLAB_TOKEN = "gitlab.private-token";
    public static final String GITLAB_WEBHOOK_SECRET = "gitlab.webhook-secret";
    public static final String CONFLUENCE_URL = "confluence.url";
    public static final String CONFLUENCE_USERNAME = "confluence.username";
    public static final String CONFLUENCE_PASSWORD = "confluence.password";
    public static final String CONFLUENCE_PAT = "confluence.pat";
    public static final String API_SPEC_TEMPLATE_PAGE_TITLE = "api-spec.template-page-title";
    public static final String API_SPEC_MAX_CONTROLLERS = "api-spec.max-controllers";

    private final SystemSettingRepository repository;

    @Value("${gitlab.url}")
    private String gitlabUrl;

    @Value("${gitlab.private-token}")
    private String gitlabToken;

    @Value("${gitlab.webhook-secret}")
    private String gitlabWebhookSecret;

    @Value("${confluence.url}")
    private String confluenceUrl;

    @Value("${confluence.username:}")
    private String confluenceUsername;

    @Value("${confluence.password:}")
    private String confluencePassword;

    @Value("${confluence.pat:}")
    private String confluencePat;

    @Value("${api-spec.template-page-title:}")
    private String apiSpecTemplatePageTitle;

    @Value("${api-spec.max-controllers:0}")
    private String apiSpecMaxControllers;

    public SystemSettingService(SystemSettingRepository repository) {
        this.repository = repository;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(0)
    public void initializeDefaults() {
        log.info("시스템 설정 초기화 시작...");
        int count = 0;

        count += initIfAbsent(GITLAB_URL, gitlabUrl, "GitLab 서버 URL");
        count += initIfAbsent(GITLAB_TOKEN, gitlabToken, "GitLab Private Token");
        count += initIfAbsent(GITLAB_WEBHOOK_SECRET, gitlabWebhookSecret, "GitLab Webhook Secret");
        count += initIfAbsent(CONFLUENCE_URL, confluenceUrl, "Confluence 서버 URL");
        count += initIfAbsent(CONFLUENCE_USERNAME, confluenceUsername, "Confluence 사용자명");
        count += initIfAbsent(CONFLUENCE_PASSWORD, confluencePassword, "Confluence 비밀번호");
        count += initIfAbsent(CONFLUENCE_PAT, confluencePat, "Confluence PAT");
        count += initIfAbsent(API_SPEC_TEMPLATE_PAGE_TITLE, apiSpecTemplatePageTitle, "API 명세서 서식 템플릿 페이지 제목");
        count += initIfAbsent(API_SPEC_MAX_CONTROLLERS, apiSpecMaxControllers, "Controller 처리 제한 수");

        log.info("시스템 설정 초기화 완료 (신규 {}건)", count);
    }

    private int initIfAbsent(String key, String value, String description) {
        if (!repository.existsById(key)) {
            repository.save(SystemSettingEntity.builder()
                    .settingKey(key)
                    .settingValue(value != null ? value : "")
                    .description(description)
                    .build());
            return 1;
        }
        return 0;
    }

    public String get(String key) {
        return repository.findById(key)
                .map(SystemSettingEntity::getSettingValue)
                .orElse("");
    }

    public int getInt(String key, int defaultValue) {
        String value = get(key);
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public void set(String key, String value) {
        repository.findById(key).ifPresent(entity -> {
            entity.setSettingValue(value);
            repository.save(entity);
        });
    }

    public Map<String, String> getAll() {
        Map<String, String> map = new LinkedHashMap<>();
        repository.findAll().forEach(e -> map.put(e.getSettingKey(), e.getSettingValue()));
        return map;
    }

    public void updateAll(Map<String, String> settings) {
        List<SystemSettingEntity> entities = repository.findAll();
        for (SystemSettingEntity entity : entities) {
            if (settings.containsKey(entity.getSettingKey())) {
                entity.setSettingValue(settings.get(entity.getSettingKey()));
            }
        }
        repository.saveAll(entities);
        log.info("시스템 설정 일괄 업데이트 완료: {}건", settings.size());
    }
}
