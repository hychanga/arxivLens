package com.arxivlens.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class SourceDtos {

    private SourceDtos() {}

    public record CreateSourceRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = "[a-z0-9_-]+") String code,
            @NotBlank @Size(max = 128) String name,
            @Size(max = 512) String description,
            Boolean enabled,
            Integer displayOrder
    ) {}

    public record UpdateSourceRequest(
            @Size(max = 128) String name,
            @Size(max = 512) String description,
            Boolean enabled,
            Integer displayOrder
    ) {}
}
