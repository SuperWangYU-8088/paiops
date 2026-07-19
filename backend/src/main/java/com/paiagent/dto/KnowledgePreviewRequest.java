package com.paiagent.dto;

import lombok.Data;

@Data
public class KnowledgePreviewRequest {

    private Integer chunkSize;

    private Integer chunkOverlap;
}
