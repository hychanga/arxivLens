package com.arxivlens.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "settings")
@Getter
@Setter
@NoArgsConstructor
public class Setting {

    @Id
    private Long id;

    @Column(name = "default_days", nullable = false)
    private Integer defaultDays;

    @Column(name = "max_results_per_sync", nullable = false)
    private Integer maxResultsPerSync;

    @Column(name = "auto_refresh_interval_minutes", nullable = false)
    private Integer autoRefreshIntervalMinutes;
}
