package com.paiagent.service;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OpenAiCompatibleImageClientTest {

    private final OpenAiCompatibleImageClient client = new OpenAiCompatibleImageClient();

    @Test
    void normalizeImageEndpoint_AppendsOpenAiImagePath() {
        assertEquals(
                "https://api.agnes-ai.com/v1/images/generations",
                client.normalizeImageEndpoint("https://api.agnes-ai.com")
        );
    }

    @Test
    void normalizeImageEndpoint_KeepsExplicitImageEndpoint() {
        assertEquals(
                "https://api.agnes-ai.com/v1/images/generations",
                client.normalizeImageEndpoint("https://api.agnes-ai.com/v1/images/generations")
        );
    }

    @Test
    void normalizeImageEndpoint_RewritesChatCompletionsEndpoint() {
        assertEquals(
                "https://apihub.agnes-ai.com/v1/images/generations",
                client.normalizeImageEndpoint("https://apihub.agnes-ai.com/v1/chat/completions")
        );
    }

    @Test
    void normalizeImageEndpoint_AgnesProvider_RewritesLegacyApiHostToApiHub() {
        assertEquals(
                "https://apihub.agnes-ai.com/v1/images/generations",
                client.normalizeImageEndpoint("https://api.agnes-ai.com/v1/chat/completions", "agnes")
        );
    }

    @Test
    void normalizeImageEndpoint_PreservesProxyPrefixBeforeVersionPath() {
        assertEquals(
                "https://proxy.example.com/agnes/v1/images/generations",
                client.normalizeImageEndpoint("https://proxy.example.com/agnes/v1/chat/completions")
        );
    }

    @Test
    void buildRequest_AgnesProvider_UsesExtraBodyResponseFormatAndImageArray() {
        ResolvedAgentPlanConfig config = new ResolvedAgentPlanConfig(
                1L,
                "agnes",
                "https://apihub.agnes-ai.com",
                "key",
                "agnes-image-2.1-flash",
                null,
                "agnes-image-2.1-flash",
                null,
                false
        );

        JSONObject request = client.buildRequest(
                config,
                "Make it cinematic",
                "https://example.com/input.png",
                "1024x768",
                1,
                null,
                null
        );

        assertFalse(request.containsKey("response_format"));
        assertEquals("agnes-image-2.1-flash", request.getString("model"));
        assertEquals("1024x768", request.getString("size"));
        JSONObject extraBody = request.getJSONObject("extra_body");
        assertEquals("url", extraBody.getString("response_format"));
        assertEquals("https://example.com/input.png", extraBody.getJSONArray("image").getString(0));
    }
}
