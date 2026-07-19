package com.paiagent.service;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgnesVideoClientTest {

    private final AgnesVideoClient client = new AgnesVideoClient();

    @Test
    void normalizeCreateEndpoint_RewritesLegacyHostAndUsesVideosEndpoint() {
        assertEquals(
                "https://apihub.agnes-ai.com/v1/videos",
                client.normalizeCreateEndpoint("https://api.agnes-ai.com/v1/chat/completions")
        );
    }

    @Test
    void normalizeCreateEndpoint_PreservesProxyPrefixBeforeVersionPath() {
        assertEquals(
                "https://proxy.example.com/agnes/v1/videos",
                client.normalizeCreateEndpoint("https://proxy.example.com/agnes/v1/chat/completions")
        );
    }

    @Test
    void normalizeQueryEndpoint_UsesAgnesApiForVideoId() {
        assertEquals(
                "https://apihub.agnes-ai.com/agnesapi?video_id=video_123&model_name=agnes-video-v2.0",
                client.normalizeQueryEndpoint("https://api.agnes-ai.com", "video_123", "agnes-video-v2.0")
        );
    }

    @Test
    void buildCreateRequest_UsesAgnesVideoFields() {
        ResolvedAgentPlanConfig config = new ResolvedAgentPlanConfig(
                1L,
                "agnes",
                "https://apihub.agnes-ai.com",
                "key",
                "agnes-video-2.0",
                null,
                null,
                "agnes-video-2.0",
                false
        );

        JSONObject request = client.buildCreateRequest(
                config,
                "A cinematic city sunrise",
                "https://example.com/first-frame.png",
                5
        );

        assertEquals("agnes-video-v2.0", request.getString("model"));
        assertEquals("A cinematic city sunrise", request.getString("prompt"));
        assertEquals("https://example.com/first-frame.png", request.getString("image"));
        assertEquals(121, request.getIntValue("num_frames"));
        assertEquals(24, request.getIntValue("frame_rate"));
    }

    @Test
    void normalizeQueryEndpoint_UsesNormalizedModelAlias() {
        assertEquals(
                "https://apihub.agnes-ai.com/agnesapi?video_id=video_123&model_name=agnes-video-v2.0",
                client.normalizeQueryEndpoint("https://apihub.agnes-ai.com", "video_123", client.normalizeModel("agnes-video-2.0"))
        );
    }

    @Test
    void normalizeNumFrames_UsesEightNPlusOneAndClampsToMaximum() {
        assertEquals(121, client.normalizeNumFrames(5, 24));
        assertEquals(241, client.normalizeNumFrames(10, 24));
        assertEquals(441, client.normalizeNumFrames(30, 24));
    }

    @Test
    void normalizeModel_MapsCommonAgnesVideoAliases() {
        assertEquals("agnes-video-v2.0", client.normalizeModel("agnes-video-2.0"));
        assertEquals("agnes-video-v2.0", client.normalizeModel("agnes-video-v20"));
        assertEquals("agnes-video-v2.0", client.normalizeModel("agnes-video-v2"));
    }
}
