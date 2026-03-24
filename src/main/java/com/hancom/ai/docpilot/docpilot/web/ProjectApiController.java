package com.hancom.ai.docpilot.docpilot.web;

import com.hancom.ai.docpilot.docpilot.config.ConfigLoaderService;
import com.hancom.ai.docpilot.docpilot.config.model.ConfluenceStructure;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/projects")
public class ProjectApiController {

    private final ConfigLoaderService configLoaderService;

    public ProjectApiController(ConfigLoaderService configLoaderService) {
        this.configLoaderService = configLoaderService;
    }

    @GetMapping
    public ResponseEntity<List<ConfluenceStructure.ProjectMapping>> list(Authentication auth) {
        List<ConfluenceStructure.ProjectMapping> projects = getAccessibleProjects(auth);
        return ResponseEntity.ok(projects);
    }

    @GetMapping("/{projectId}")
    public ResponseEntity<ConfluenceStructure.ProjectMapping> get(@PathVariable Long projectId, Authentication auth) {
        ConfluenceStructure.ProjectMapping project = configLoaderService.getProjectMapping(projectId);
        if (project == null || !canAccess(auth, project)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(project);
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> create(@RequestBody ConfluenceStructure.ProjectMapping newProject,
                                                       Authentication auth) {
        ConfluenceStructure structure = configLoaderService.getConfluenceStructure();

        boolean exists = structure.getProjects().stream()
                .anyMatch(p -> p.getGitlabProjectId().equals(newProject.getGitlabProjectId()));
        if (exists) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "이미 등록된 프로젝트 ID: " + newProject.getGitlabProjectId()));
        }

        if (newProject.getPages() == null) {
            newProject.setPages(new ArrayList<>());
        }
        // 생성자 정보 저장
        newProject.setCreatedBy(auth.getName());

        List<ConfluenceStructure.ProjectMapping> projects = new ArrayList<>(structure.getProjects());
        projects.add(newProject);
        structure.setProjects(projects);

        configLoaderService.saveConfluenceStructure(structure);
        log.info("프로젝트 추가: id={}, path={}, createdBy={}", newProject.getGitlabProjectId(), newProject.getGitlabPath(), auth.getName());

        return ResponseEntity.ok(Map.of("message", "프로젝트가 등록되었습니다."));
    }

    @PutMapping("/{projectId}")
    public ResponseEntity<Map<String, String>> update(@PathVariable Long projectId,
                                                       @RequestBody ConfluenceStructure.ProjectMapping updated,
                                                       Authentication auth) {
        ConfluenceStructure structure = configLoaderService.getConfluenceStructure();

        List<ConfluenceStructure.ProjectMapping> projects = new ArrayList<>(structure.getProjects());
        boolean found = false;
        for (int i = 0; i < projects.size(); i++) {
            if (projects.get(i).getGitlabProjectId().equals(projectId)) {
                if (!canAccess(auth, projects.get(i))) {
                    return ResponseEntity.status(403).body(Map.of("message", "권한이 없습니다."));
                }
                updated.setGitlabProjectId(projectId);
                if (updated.getPages() == null) {
                    updated.setPages(projects.get(i).getPages());
                }
                updated.setCreatedBy(projects.get(i).getCreatedBy());
                projects.set(i, updated);
                found = true;
                break;
            }
        }

        if (!found) {
            return ResponseEntity.notFound().build();
        }

        structure.setProjects(projects);
        configLoaderService.saveConfluenceStructure(structure);
        return ResponseEntity.ok(Map.of("message", "프로젝트가 수정되었습니다."));
    }

    @DeleteMapping("/{projectId}")
    public ResponseEntity<Map<String, String>> delete(@PathVariable Long projectId, Authentication auth) {
        ConfluenceStructure structure = configLoaderService.getConfluenceStructure();

        List<ConfluenceStructure.ProjectMapping> projects = new ArrayList<>(structure.getProjects());
        ConfluenceStructure.ProjectMapping target = projects.stream()
                .filter(p -> p.getGitlabProjectId().equals(projectId))
                .findFirst().orElse(null);

        if (target == null) {
            return ResponseEntity.notFound().build();
        }
        if (!canAccess(auth, target)) {
            return ResponseEntity.status(403).body(Map.of("message", "권한이 없습니다."));
        }

        projects.remove(target);
        structure.setProjects(projects);
        configLoaderService.saveConfluenceStructure(structure);
        return ResponseEntity.ok(Map.of("message", "프로젝트가 삭제되었습니다."));
    }

    @GetMapping("/branches")
    public ResponseEntity<ConfluenceStructure.Branches> getBranches() {
        return ResponseEntity.ok(configLoaderService.getConfluenceStructure().getBranches());
    }

    @PutMapping("/branches")
    public ResponseEntity<Map<String, String>> updateBranches(@RequestBody ConfluenceStructure.Branches branches) {
        ConfluenceStructure structure = configLoaderService.getConfluenceStructure();
        structure.setBranches(branches);
        configLoaderService.saveConfluenceStructure(structure);
        return ResponseEntity.ok(Map.of("message", "브랜치 설정이 수정되었습니다."));
    }

    /** 현재 사용자가 접근 가능한 프로젝트 목록 */
    public List<ConfluenceStructure.ProjectMapping> getAccessibleProjects(Authentication auth) {
        List<ConfluenceStructure.ProjectMapping> all = configLoaderService.getConfluenceStructure().getProjects();
        if (isAdmin(auth)) {
            return all;
        }
        String username = auth.getName();
        return all.stream()
                .filter(p -> username.equals(p.getCreatedBy()))
                .toList();
    }

    private boolean canAccess(Authentication auth, ConfluenceStructure.ProjectMapping project) {
        if (isAdmin(auth)) return true;
        return auth.getName().equals(project.getCreatedBy());
    }

    private boolean isAdmin(Authentication auth) {
        return auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }
}
