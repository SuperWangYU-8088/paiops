package com.paiagent.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkflowSecretPolicyTest {

    private final WorkflowSecretPolicy policy = new WorkflowSecretPolicy();

    @Test
    void acceptsCredentialReferences() {
        String flowData = """
                {"nodes":[{"id":"http-1","data":{"credentialId":12,"configId":3}}],"edges":[]}
                """;

        assertDoesNotThrow(() -> policy.assertNoInlineSecrets(flowData));
    }

    @Test
    void rejectsInlineSecretsRecursively() {
        String flowData = """
                {"nodes":[{"id":"llm-1","data":{"nested":{"apiKey":"sk-sensitive"}}}],"edges":[]}
                """;

        assertThrows(IllegalArgumentException.class,
                () -> policy.assertNoInlineSecrets(flowData));
    }

    @Test
    void acceptsEmptyLegacySecretField() {
        String flowData = """
                {"nodes":[{"id":"llm-1","data":{"apiKey":"","configId":3}}],"edges":[]}
                """;

        assertDoesNotThrow(() -> policy.assertNoInlineSecrets(flowData));
    }

    @Test
    void rejectsAuthorizationHiddenInsideHeaderJson() {
        String flowData = """
                {"nodes":[{"id":"http-1","data":{"headers":"{\\\"Authorization\\\":\\\"Bearer secret\\\"}"}}]}
                """;

        assertThrows(IllegalArgumentException.class,
                () -> policy.assertNoInlineSecrets(flowData));
    }
}
