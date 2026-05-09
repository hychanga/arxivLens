package com.arxivlens.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class TopicDtos {

    private TopicDtos() {}

    public record CreateTopicRequest(
            @NotNull Long sourceId,
            @NotBlank @Size(max = 64) String code,
            @NotBlank @Size(max = 128) String name,
            Boolean enabled
    ) {}

    public record UpdateTopicRequest(
            @Size(max = 128) String name,
            Boolean enabled
    ) {}
}
