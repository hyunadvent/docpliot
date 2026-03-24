package com.hancom.ai.docpilot.docpilot.source.gitlab;

import org.gitlab4j.api.CommitsApi;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.RepositoryFileApi;
import org.gitlab4j.api.models.Commit;
import org.gitlab4j.api.models.Diff;
import org.gitlab4j.api.models.RepositoryFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GitLabSourceServiceTest {

    @Mock
    private GitLabApi gitLabApi;

    @Mock
    private CommitsApi commitsApi;

    @Mock
    private RepositoryFileApi repositoryFileApi;

    private GitLabSourceService gitLabSourceService;

    @BeforeEach
    void setUp() throws Exception {
        gitLabSourceService = new GitLabSourceService();

        // gitLabApi 필드를 Mock으로 교체
        Field field = GitLabSourceService.class.getDeclaredField("gitLabApi");
        field.setAccessible(true);
        field.set(gitLabSourceService, gitLabApi);
    }

    @Test
    void getChangedFiles_변경파일_목록_반환() throws GitLabApiException {
        when(gitLabApi.getCommitsApi()).thenReturn(commitsApi);

        Diff diff1 = new Diff();
        diff1.setNewPath("src/main/java/App.java");
        Diff diff2 = new Diff();
        diff2.setNewPath("README.md");

        when(commitsApi.getDiff(1L, "abc123")).thenReturn(List.of(diff1, diff2));

        List<String> files = gitLabSourceService.getChangedFiles(1L, "abc123");

        assertEquals(2, files.size());
        assertEquals("src/main/java/App.java", files.get(0));
        assertEquals("README.md", files.get(1));
    }

    @Test
    void getChangedFiles_변경파일_없으면_빈목록() throws GitLabApiException {
        when(gitLabApi.getCommitsApi()).thenReturn(commitsApi);
        when(commitsApi.getDiff(1L, "abc123")).thenReturn(List.of());

        List<String> files = gitLabSourceService.getChangedFiles(1L, "abc123");

        assertTrue(files.isEmpty());
    }

    @Test
    void getFileContent_파일내용_반환() throws GitLabApiException {
        when(gitLabApi.getRepositoryFileApi()).thenReturn(repositoryFileApi);

        RepositoryFile repoFile = new RepositoryFile();
        String originalContent = "public class App {}";
        repoFile.setContent(Base64.getEncoder().encodeToString(originalContent.getBytes()));
        repoFile.setEncoding(org.gitlab4j.api.Constants.Encoding.BASE64);

        when(repositoryFileApi.getFile(1L, "src/App.java", "main")).thenReturn(repoFile);

        String content = gitLabSourceService.getFileContent(1L, "src/App.java", "main");

        assertEquals(originalContent, content);
    }

    @Test
    void getCommitMessage_커밋메시지_반환() throws GitLabApiException {
        when(gitLabApi.getCommitsApi()).thenReturn(commitsApi);

        Commit commit = new Commit();
        commit.setMessage("feat: 로그인 기능 추가");

        when(commitsApi.getCommit(1L, "abc123")).thenReturn(commit);

        String message = gitLabSourceService.getCommitMessage(1L, "abc123");

        assertEquals("feat: 로그인 기능 추가", message);
    }

    @Test
    void getChangedFiles_GitLab_예외시_전파() throws GitLabApiException {
        when(gitLabApi.getCommitsApi()).thenReturn(commitsApi);
        when(commitsApi.getDiff(1L, "bad-sha"))
                .thenThrow(new GitLabApiException("Not Found", 404));

        assertThrows(GitLabApiException.class,
                () -> gitLabSourceService.getChangedFiles(1L, "bad-sha"));
    }
}
