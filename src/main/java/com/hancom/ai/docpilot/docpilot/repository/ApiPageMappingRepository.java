package com.hancom.ai.docpilot.docpilot.repository;

import com.hancom.ai.docpilot.docpilot.entity.ApiPageMappingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApiPageMappingRepository extends JpaRepository<ApiPageMappingEntity, Long> {
    List<ApiPageMappingEntity> findByControllerMappingId(Long controllerMappingId);

    Optional<ApiPageMappingEntity> findByControllerMappingIdAndMethodName(
            Long controllerMappingId, String methodName);
}
