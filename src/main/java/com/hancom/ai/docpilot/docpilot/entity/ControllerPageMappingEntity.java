package com.hancom.ai.docpilot.docpilot.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "controller_page_mappings",
        uniqueConstraints = @UniqueConstraint(columnNames = {"gitlabProjectId", "controllerPath"}),
        indexes = @Index(name = "idx_cpm_project", columnList = "gitlabProjectId"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ControllerPageMappingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long gitlabProjectId;

    @Column(length = 500)
    private String controllerPath;

    private String controllerKoreanName;
    private Long confluencePageId;
    private String confluencePageTitle;
    private boolean processed;
    private int apiCount;
    private LocalDateTime lastUpdated;
}
