package com.hancom.ai.docpilot.docpilot.repository;

import com.hancom.ai.docpilot.docpilot.entity.SystemSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SystemSettingRepository extends JpaRepository<SystemSettingEntity, String> {
}
