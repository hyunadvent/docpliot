package com.hancom.ai.docpilot.docpilot.web;

import com.hancom.ai.docpilot.docpilot.config.ConfigLoaderService;
import com.hancom.ai.docpilot.docpilot.config.model.ConfluenceStructure;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
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
    public ResponseEntity<List<ConfluenceStructure.ProjectMapping>> list() {
        return ResponseEntity.ok(configLoaderService.getConfluenceStructure().getProjects());
    }

    @GetMapping("/{projectId}")
    public ResponseEntity<ConfluenceStructure.ProjectMapping> get(@PathVariable Long projectId) {
        ConfluenceStructure.ProjectMapping project = configLoaderService.getProjectMapping(projectId);
        if (project == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(project);
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> create(@RequestBody ConfluenceStructure.ProjectMapping newProject) {
        ConfluenceStructure structure = configLoaderService.getConfluenceStructure();

        // 중복 체크
        boolean exists = structure.getProjects().stream()
                .anyMatch(p -> p.getGitlabProjectId().equals(newProject.getGitlabProjectId()));
        if (exists) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "이미 등록된 프로젝트 ID: " + newProject.getGitlabProjectId()));
        }

        // 기본 pages가 없으면 빈 리스트
        if (newProject.getPages() == null) {
            newProject.setPages(new ArrayList<>());
        }

        List<ConfluenceStructure.ProjectMapping> projects = new ArrayList<>(structure.getProjects());
        projects.add(newProject);
        structure.setProjects(projects);

        configLoaderService.saveConfluenceStructure(structure);
        log.info("프로젝트 추가: id={}, path={}", newProject.getGitlabProjectId(), newProject.getGitlabPath());

        return ResponseEntity.ok(Map.of("message", "프로젝트가 등록되었습니다."));
    }

    @PutMapping("/{projectId}")
    public ResponseEntity<Map<String, String>> update(@PathVariable Long projectId,
                                                       @RequestBody ConfluenceStructure.ProjectMapping updated) {
        ConfluenceStructure structure = configLoaderService.getConfluenceStructure();

        List<ConfluenceStructure.ProjectMapping> projects = new ArrayList<>(structure.getProjects());
        boolean found = false;
        for (int i = 0; i < projects.size(); i++) {
            if (projects.get(i).getGitlabProjectId().equals(projectId)) {
                updated.setGitlabProjectId(projectId);
                if (updated.getPages() == null) {
                    updated.setPages(projects.get(i).getPages());
                }
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
        log.info("프로젝트 수정: id={}", projectId);

        return ResponseEntity.ok(Map.of("message", "프로젝트가 수정되었습니다."));
    }

    @DeleteMapping("/{projectId}")
    public ResponseEntity<Map<String, String>> delete(@PathVariable Long projectId) {
        ConfluenceStructure structure = configLoaderService.getConfluenceStructure();

        List<ConfluenceStructure.ProjectMapping> projects = new ArrayList<>(structure.getProjects());
        boolean removed = projects.removeIf(p -> p.getGitlabProjectId().equals(projectId));

        if (!removed) {
            return ResponseEntity.notFound().build();
        }

        structure.setProjects(projects);
        configLoaderService.saveConfluenceStructure(structure);
        log.info("프로젝트 삭제: id={}", projectId);

        return ResponseEntity.ok(Map.of("message", "프로젝트가 삭제되었습니다."));
    }

    /**
     * 브랜치 설정 조회/수정
     */
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
}
