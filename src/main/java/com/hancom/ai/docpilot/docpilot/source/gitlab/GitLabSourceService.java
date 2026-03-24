package com.hancom.ai.docpilot.docpilot.source.gitlab;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.Commit;
import org.gitlab4j.api.models.Diff;
import org.gitlab4j.api.models.Project;
import org.gitlab4j.api.models.RepositoryFile;
import org.gitlab4j.api.models.TreeItem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class GitLabSourceService {

    @Value("${gitlab.url}")
    private String gitlabUrl;

    @Value("${gitlab.private-token}")
    private String privateToken;

    private GitLabApi gitLabApi;

    @PostConstruct
    public void init() {
        gitLabApi = new GitLabApi(gitlabUrl, privateToken);
        log.info("GitLab API 초기화 완료: {}", gitlabUrl);
    }

    /**
     * 커밋에서 변경된 파일 경로 목록을 반환합니다.
     */
    public List<String> getChangedFiles(Long projectId, String commitSha) throws GitLabApiException {
        List<Diff> diffs = gitLabApi.getCommitsApi().getDiff(projectId, commitSha);
        List<String> files = diffs.stream()
                .map(Diff::getNewPath)
                .collect(Collectors.toList());

        log.debug("커밋 {} 변경 파일 {}개: {}", commitSha, files.size(), files);
        return files;
    }

    /**
     * 특정 브랜치 기준으로 파일 내용을 반환합니다.
     * gitlab4j-api가 Base64 디코딩을 내부적으로 처리합니다.
     */
    public String getFileContent(Long projectId, String filePath, String ref) throws GitLabApiException {
        RepositoryFile file = gitLabApi.getRepositoryFileApi().getFile(projectId, filePath, ref);
        String content = file.getDecodedContentAsString();

        log.debug("파일 내용 조회: project={}, path={}, ref={}, length={}", projectId, filePath, ref, content.length());
        return content;
    }

    /**
     * 프로젝트의 default branch를 반환합니다.
     */
    public String getDefaultBranch(Long projectId) throws GitLabApiException {
        Project project = gitLabApi.getProjectApi().getProject(projectId);
        return project.getDefaultBranch();
    }

    /**
     * 프로젝트에서 파일명에 특정 문자열이 포함된 파일 경로 목록을 반환합니다.
     */
    public List<String> findFiles(Long projectId, String ref, String nameContains) throws GitLabApiException {
        List<TreeItem> items = gitLabApi.getRepositoryApi().getTree(projectId, null, ref, true);
        return items.stream()
                .filter(item -> "blob".equals(item.getType().toString()))
                .filter(item -> item.getName().contains(nameContains))
                .map(TreeItem::getPath)
                .collect(Collectors.toList());
    }

    /**
     * 커밋 메시지를 반환합니다.
     */
    public String getCommitMessage(Long projectId, String commitSha) throws GitLabApiException {
        Commit commit = gitLabApi.getCommitsApi().getCommit(projectId, commitSha);
        String message = commit.getMessage();

        log.debug("커밋 메시지 조회: {} → {}", commitSha, message);
        return message;
    }
}
