package com.paiagent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.Map;

@Data
public class ConnectorCredentialRequest {

    @NotBlank
    private String name;
    @NotBlank
    private String connectorType;
    private String description;
    @NotEmpty
    private Map<String, String> secretData;
}
