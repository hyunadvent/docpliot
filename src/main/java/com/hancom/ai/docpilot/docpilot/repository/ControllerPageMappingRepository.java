package com.hancom.ai.docpilot.docpilot.repository;

import com.hancom.ai.docpilot.docpilot.entity.ControllerPageMappingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface ControllerPageMappingRepository extends JpaRepository<ControllerPageMappingEntity, Long> {
    List<ControllerPageMappingEntity> findByGitlabProjectId(Long gitlabProjectId);

    Optional<ControllerPageMappingEntity> findByGitlabProjectIdAndControllerPath(
            Long gitlabProjectId, String controllerPath);

    @Query("SELECT c.controllerPath FROM ControllerPageMappingEntity c WHERE c.gitlabProjectId = :projectId AND c.processed = true")
    Set<String> findProcessedControllerPaths(@Param("projectId") Long gitlabProjectId);

    int countByGitlabProjectIdAndProcessedTrue(Long gitlabProjectId);
}
