package com.hancom.ai.docpilot.docpilot.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "api_page_mappings",
        indexes = @Index(name = "idx_apm_controller", columnList = "controller_mapping_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiPageMappingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "controller_mapping_id", nullable = false)
    private ControllerPageMappingEntity controllerMapping;

    private String methodName;
    private String httpMethod;
    private String path;
    private String koreanName;
    private Long confluencePageId;
    private String confluencePageTitle;
    private LocalDateTime lastUpdated;
}
