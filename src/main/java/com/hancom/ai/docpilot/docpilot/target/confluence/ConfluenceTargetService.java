package com.hancom.ai.docpilot.docpilot.target.confluence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ConfluenceTargetService {

    private final WebClient webClient;

    public ConfluenceTargetService(
            WebClient.Builder webClientBuilder,
            @Value("${confluence.url}") String confluenceUrl,
            @Value("${confluence.username:}") String username,
            @Value("${confluence.password:}") String password,
            @Value("${confluence.pat:}") String pat) {

        WebClient.Builder builder = webClientBuilder
                .baseUrl(confluenceUrl + "/rest/api/content");

        if (pat != null && !pat.isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + pat);
            log.info("Confluence 인증: PAT (Bearer)");
        } else {
            String credentials = Base64.getEncoder()
                    .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + credentials);
            log.info("Confluence 인증: Basic Auth");
        }

        this.webClient = builder.build();
    }

    /**
     * 스페이스에서 제목으로 페이지를 조회합니다. 없으면 null을 반환합니다.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getPage(String spaceKey, String title) {
        try {
            Map<String, Object> response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .queryParam("spaceKey", spaceKey)
                            .queryParam("title", title)
                            .queryParam("expand", "body.storage,version")
                            .build())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");
            if (results == null || results.isEmpty()) {
                log.debug("페이지 없음: space={}, title={}", spaceKey, title);
                return null;
            }

            log.debug("페이지 조회: space={}, title={}, id={}", spaceKey, title, results.get(0).get("id"));
            return results.get(0);
        } catch (WebClientResponseException e) {
            log.error("페이지 조회 실패: space={}, title={}, status={}", spaceKey, title, e.getStatusCode(), e);
            throw e;
        }
    }

    /**
     * 특정 부모 페이지의 자식 중에서 제목으로 페이지를 찾습니다. 없으면 null.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getChildPage(Long parentId, String title) {
        try {
            List<Map<String, Object>> allResults = fetchAllChildPages(parentId, "version,body.storage");
            return allResults.stream()
                    .filter(page -> title.equals(page.get("title")))
                    .findFirst()
                    .orElse(null);
        } catch (WebClientResponseException e) {
            log.error("자식 페이지 조회 실패: parentId={}, title={}, status={}", parentId, title, e.getStatusCode(), e);
            throw e;
        }
    }

    /**
     * 특정 부모 페이지의 모든 자식 페이지를 body.storage 포함하여 반환합니다.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getChildPagesFull(Long parentId) {
        try {
            List<Map<String, Object>> allResults = fetchAllChildPages(parentId, "version,body.storage");
            Map<String, Object> response = new HashMap<>();
            response.put("results", allResults);
            return response;
        } catch (WebClientResponseException e) {
            log.error("자식 페이지 전체 조회 실패: parentId={}, status={}", parentId, e.getStatusCode(), e);
            throw e;
        }
    }

    /**
     * 페이지네이션을 처리하여 모든 자식 페이지를 조회합니다.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchAllChildPages(Long parentId, String expand) {
        List<Map<String, Object>> allResults = new ArrayList<>();
        int start = 0;
        int limit = 25;

        while (true) {
            int currentStart = start;
            Map<String, Object> response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/{parentId}/child/page")
                            .queryParam("expand", expand)
                            .queryParam("start", currentStart)
                            .queryParam("limit", limit)
                            .build(parentId))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");
            if (results == null || results.isEmpty()) break;

            allResults.addAll(results);

            // 페이지네이션: size가 limit보다 작으면 마지막 페이지
            Number size = (Number) response.get("size");
            if (size == null || size.intValue() < limit) break;

            start += limit;
        }

        return allResults;
    }

    /**
     * 페이지를 신규 생성하고 생성된 페이지를 반환합니다.
     * 같은 스페이스에 동일 제목의 페이지가 이미 존재하면 해당 페이지를 업데이트합니다.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> createPage(String spaceKey, String title, String body, Long parentId) {
        // 먼저 동일 제목의 페이지가 이미 존재하는지 확인
        Map<String, Object> existing = getPage(spaceKey, title);
        if (existing != null) {
            Long pageId = toLong(existing.get("id"));
            Map<String, Object> versionObj = (Map<String, Object>) existing.get("version");
            int currentVersion = ((Number) versionObj.get("number")).intValue();
            int nextVersion = currentVersion + 1;

            log.info("동일 제목의 페이지가 이미 존재하여 업데이트합니다: space={}, title={}, id={}", spaceKey, title, pageId);
            updatePage(pageId, title, body, nextVersion);
            return existing;
        }

        Map<String, Object> requestBody = buildCreateBody(spaceKey, title, body, parentId);

        try {
            Map<String, Object> created = webClient.post()
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            log.info("페이지 생성 완료: space={}, title={}, parentId={}", spaceKey, title, parentId);
            return created;
        } catch (WebClientResponseException e) {
            log.error("페이지 생성 실패: space={}, title={}, parentId={}, status={}, body={}",
                    spaceKey, title, parentId, e.getStatusCode(), e.getResponseBodyAsString());
            throw e;
        }
    }

    /**
     * 페이지를 업데이트합니다. version은 기존 버전 +1로 전달해야 합니다.
     */
    public void updatePage(Long pageId, String title, String body, int version) {
        Map<String, Object> requestBody = buildUpdateBody(title, body, version);

        webClient.put()
                .uri("/{pageId}", pageId)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        log.info("페이지 업데이트 완료: id={}, title={}, version={}", pageId, title, version);
    }

    /**
     * 기존 페이지 body 뒤에 내용을 추가합니다.
     */
    @SuppressWarnings("unchecked")
    public void appendToPage(Long pageId, String title, String additionalBody, int version) {
        Map<String, Object> page = webClient.get()
                .uri("/{pageId}?expand=body.storage", pageId)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        Map<String, Object> bodyObj = (Map<String, Object>) page.get("body");
        Map<String, Object> storage = (Map<String, Object>) bodyObj.get("storage");
        String existingBody = (String) storage.get("value");

        String mergedBody = existingBody + "\n" + additionalBody;
        updatePage(pageId, title, mergedBody, version);

        log.info("페이지 내용 추가 완료: id={}, title={}", pageId, title);
    }

    /**
     * 페이지가 있으면 업데이트(또는 append), 없으면 생성합니다.
     */
    @SuppressWarnings("unchecked")
    public void upsertPage(String spaceKey, String title, String body, String parentTitle, boolean appendMode) {
        Map<String, Object> existing = getPage(spaceKey, title);

        if (existing == null) {
            Long parentId = null;
            if (parentTitle != null) {
                Map<String, Object> parentPage = getPage(spaceKey, parentTitle);
                if (parentPage != null) {
                    parentId = toLong(parentPage.get("id"));
                }
            }
            createPage(spaceKey, title, body, parentId);
        } else {
            Long pageId = toLong(existing.get("id"));
            Map<String, Object> versionObj = (Map<String, Object>) existing.get("version");
            int currentVersion = ((Number) versionObj.get("number")).intValue();
            int nextVersion = currentVersion + 1;

            if (appendMode) {
                appendToPage(pageId, title, body, nextVersion);
            } else {
                updatePage(pageId, title, body, nextVersion);
            }
        }
    }

    /**
     * 특정 부모의 자식 중에서 폴더와 페이지 모두 포함하여 제목으로 검색합니다.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getChildContent(Long parentId, String title) {
        // 폴더에서 먼저 검색
        try {
            Map<String, Object> folderResponse = webClient.get()
                    .uri("/{parentId}/child/folder", parentId)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            List<Map<String, Object>> folders = (List<Map<String, Object>>) folderResponse.get("results");
            if (folders != null) {
                for (Map<String, Object> folder : folders) {
                    if (title.equals(folder.get("title"))) {
                        log.debug("폴더 발견: title={}, id={}", title, folder.get("id"));
                        return folder;
                    }
                }
            }
        } catch (WebClientResponseException e) {
            log.debug("폴더 조회 실패 (미지원일 수 있음): parentId={}, status={}", parentId, e.getStatusCode());
        }

        // 페이지에서 검색
        return getChildPage(parentId, title);
    }

    /**
     * 스페이스 홈페이지 ID를 조회합니다.
     */
    @SuppressWarnings("unchecked")
    private Long getSpaceHomepageId(String spaceKey) {
        Map<String, Object> space = webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .replacePath("/wiki/rest/api/space/" + spaceKey)
                        .queryParam("expand", "homepage")
                        .build())
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        Map<String, Object> homepage = (Map<String, Object>) space.get("homepage");
        return toLong(homepage.get("id"));
    }

    /**
     * 계층 경로를 따라 페이지/폴더를 찾거나 자동 생성하고, 마지막 항목의 ID를 반환합니다.
     * 기존 폴더나 페이지가 있으면 그대로 사용하고, 없을 때만 페이지로 새로 생성합니다.
     */
    public Long ensureParentPages(String spaceKey, List<String> parentPages) {
        // 스페이스 홈페이지부터 시작하여 자식을 탐색
        Long parentId = getSpaceHomepageId(spaceKey);
        log.debug("스페이스 홈페이지 ID: {}", parentId);

        for (String pageTitle : parentPages) {
            // 부모의 자식에서 폴더+페이지 모두 검색
            Map<String, Object> content = getChildContent(parentId, pageTitle);

            if (content == null) {
                // 없으면 페이지로 생성
                Map<String, Object> created = createPage(spaceKey, pageTitle, "<p></p>", parentId);
                parentId = toLong(created.get("id"));
                log.info("부모 페이지 자동 생성: title={}, id={}", pageTitle, parentId);
            } else {
                // 기존 폴더/페이지 사용
                parentId = toLong(content.get("id"));
                log.info("기존 {} 사용: title={}, id={}", content.get("type"), pageTitle, parentId);
            }
        }

        return parentId;
    }

    /**
     * 프로젝트 매핑의 계층 경로 아래에 문서 페이지를 upsert합니다.
     * 부모(폴더/페이지) 아래에서 자식을 검색하여 정확한 위치에 생성/업데이트합니다.
     */
    @SuppressWarnings("unchecked")
    public void upsertPageUnderParents(String spaceKey, List<String> parentPages,
                                        String title, String body, boolean appendMode) {
        Long parentId = ensureParentPages(spaceKey, parentPages);

        // 부모 아래에서 자식 페이지 검색 (폴더 내 페이지도 포함)
        Map<String, Object> existing = getChildPage(parentId, title);

        if (existing == null) {
            createPage(spaceKey, title, body, parentId);
        } else {
            Map<String, Object> versionObj = (Map<String, Object>) existing.get("version");
            Long pageId = toLong(existing.get("id"));
            int currentVersion = ((Number) versionObj.get("number")).intValue();
            int nextVersion = currentVersion + 1;

            if (appendMode) {
                appendToPage(pageId, title, body, nextVersion);
            } else {
                updatePage(pageId, title, body, nextVersion);
            }
        }
    }

    private Map<String, Object> buildCreateBody(String spaceKey, String title, String body, Long parentId) {
        Map<String, Object> result = new java.util.HashMap<>(Map.of(
                "type", "page",
                "title", title,
                "space", Map.of("key", spaceKey),
                "body", Map.of("storage", Map.of(
                        "value", body,
                        "representation", "storage"
                ))
        ));

        if (parentId != null) {
            result.put("ancestors", List.of(Map.of("id", parentId)));
        }

        return result;
    }

    private Map<String, Object> buildUpdateBody(String title, String body, int version) {
        return Map.of(
                "type", "page",
                "title", title,
                "version", Map.of("number", version),
                "body", Map.of("storage", Map.of(
                        "value", body,
                        "representation", "storage"
                ))
        );
    }

    /**
     * 페이지의 너비를 '좁게(fixed)'로 설정합니다.
     */
    public void setPageWidthNarrow(Long pageId) {
        Map<String, Object> property = Map.of(
                "key", "content-appearance-published",
                "value", "fixed"
        );

        try {
            webClient.post()
                    .uri("/{pageId}/property", pageId)
                    .bodyValue(property)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            log.debug("페이지 너비 '좁게' 설정 완료: id={}", pageId);
        } catch (WebClientResponseException e) {
            // 이미 속성이 존재하면 PUT으로 업데이트
            if (e.getStatusCode().value() == 409) {
                updatePageWidthNarrow(pageId);
            } else {
                log.warn("페이지 너비 설정 실패: id={}, status={}", pageId, e.getStatusCode());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void updatePageWidthNarrow(Long pageId) {
        try {
            // 기존 속성 버전 조회
            Map<String, Object> existing = webClient.get()
                    .uri("/{pageId}/property/content-appearance-published", pageId)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            Map<String, Object> version = (Map<String, Object>) existing.get("version");
            int currentVersion = ((Number) version.get("number")).intValue();

            Map<String, Object> property = Map.of(
                    "key", "content-appearance-published",
                    "value", "fixed",
                    "version", Map.of("number", currentVersion + 1)
            );

            webClient.put()
                    .uri("/{pageId}/property/content-appearance-published", pageId)
                    .bodyValue(property)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            log.debug("페이지 너비 '좁게' 업데이트 완료: id={}", pageId);
        } catch (Exception e) {
            log.warn("페이지 너비 업데이트 실패: id={}", pageId, e);
        }
    }

    private Long toLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return Long.parseLong(value.toString());
    }
}
