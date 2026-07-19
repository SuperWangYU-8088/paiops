package com.paiagent.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ConnectorCredentialResponse {

    private Long id;
    private String name;
    private String connectorType;
    private String description;
    private String keyVersion;
    private List<String> secretFields;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
