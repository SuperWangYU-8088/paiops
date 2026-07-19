package com.paiagent.engine.executor.impl;

import com.paiagent.dto.ExecutionEvent;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.service.AgentPlanConfigResolver;
import com.paiagent.service.AgnesVideoClient;
import com.paiagent.service.MinioService;
import com.paiagent.service.ResolvedAgentPlanConfig;
import com.paiagent.service.VolcengineAgentPlanClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoGenerateNodeExecutorTest {

    private AgentPlanConfigResolver configResolver;
    private VolcengineAgentPlanClient agentPlanClient;
    private AgnesVideoClient agnesVideoClient;
    private MinioService minioService;
    private Consumer<ExecutionEvent> progressCallback;
    private VideoGenerateNodeExecutor executor;
    private WorkflowNode node;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        configResolver = mock(AgentPlanConfigResolver.class);
        agentPlanClient = mock(VolcengineAgentPlanClient.class);
        agnesVideoClient = mock(AgnesVideoClient.class);
        minioService = mock(MinioService.class);
        progressCallback = mock(Consumer.class);
        executor = new VideoGenerateNodeExecutor(
                configResolver,
                agentPlanClient,
                agnesVideoClient,
                minioService,
                2,
                0,
                ignored -> {
                    // 单元测试不做真实等待。
                }
        );
        node = new WorkflowNode();
        node.setId("video-1");
        node.setType("video_generate");
        node.setData(new HashMap<>());
    }

    @Test
    void executesVolcengineTaskAndPersistsVideo() throws Exception {
        node.getData().put("duration", 5);
        ResolvedAgentPlanConfig config = config("volcengine_agent_plan");
        when(configResolver.resolve(node, "video")).thenReturn(config);
        when(agentPlanClient.createVideoTask(
                eq(config), eq("A cat playing piano"), isNull(), eq(5), isNull(), eq("adaptive"), isNull()))
                .thenReturn("task-123");
        when(agentPlanClient.getVideoTask(config, "task-123")).thenReturn(Map.of(
                "status", "succeeded",
                "videoUrl", "https://origin.example/video.mp4",
                "coverUrl", "https://origin.example/cover.jpg"
        ));
        when(minioService.uploadFromUrl(
                "https://origin.example/video.mp4",
                "videos/generated-task-123.mp4",
                "video/mp4"))
                .thenReturn("https://storage.example/video.mp4");

        Map<String, Object> result = executor.execute(
                node, Map.of("prompt", "A cat playing piano"), progressCallback);

        assertEquals("task-123", result.get("taskId"));
        assertEquals("succeeded", result.get("status"));
        assertEquals("https://storage.example/video.mp4", result.get("videoUrl"));
        assertEquals("video-model", result.get("model"));
        verify(progressCallback, atLeast(4)).accept(any(ExecutionEvent.class));
        verify(agnesVideoClient, never()).createVideoTask(
                any(), anyString(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void routesAgnesProviderToAgnesClient() throws Exception {
        ResolvedAgentPlanConfig config = config("agnes");
        when(configResolver.resolve(node, "video")).thenReturn(config);
        when(agnesVideoClient.createVideoTask(
                eq(config), eq("prompt"), isNull(), eq(5), isNull(), eq("adaptive"), isNull()))
                .thenReturn("video_123");
        when(agnesVideoClient.getVideoTask(config, "video_123")).thenReturn(Map.of(
                "status", "completed", "videoUrl", "https://origin.example/agnes.mp4"));
        when(minioService.uploadFromUrl(anyString(), anyString(), anyString()))
                .thenReturn("https://storage.example/agnes.mp4");

        Map<String, Object> result = executor.execute(node, Map.of("prompt", "prompt"));

        assertEquals("https://storage.example/agnes.mp4", result.get("videoUrl"));
        verify(agentPlanClient, never()).createVideoTask(
                any(), anyString(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void rejectsMissingPromptBeforeCallingExternalApi() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> executor.execute(node, Map.of()));

        assertTrue(exception.getMessage().contains("缺少 prompt"));
        verify(configResolver, never()).resolve(any(), anyString());
    }

    @Test
    void rejectsEmptyTaskId() throws Exception {
        ResolvedAgentPlanConfig config = config("volcengine_agent_plan");
        when(configResolver.resolve(node, "video")).thenReturn(config);
        when(agentPlanClient.createVideoTask(
                any(), anyString(), any(), anyInt(), any(), any(), any())).thenReturn("");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> executor.execute(node, Map.of("prompt", "prompt")));

        assertTrue(exception.getMessage().contains("未返回 taskId"));
    }

    @Test
    void reportsFailedRemoteTask() throws Exception {
        ResolvedAgentPlanConfig config = config("volcengine_agent_plan");
        when(configResolver.resolve(node, "video")).thenReturn(config);
        when(agentPlanClient.createVideoTask(
                any(), anyString(), any(), anyInt(), any(), any(), any())).thenReturn("task-failed");
        when(agentPlanClient.getVideoTask(config, "task-failed"))
                .thenReturn(Map.of("status", "failed", "error", "quota"));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> executor.execute(node, Map.of("prompt", "prompt")));

        assertTrue(exception.getMessage().contains("视频生成失败"));
    }

    @Test
    void timesOutWithoutRealSleep() throws Exception {
        ResolvedAgentPlanConfig config = config("volcengine_agent_plan");
        when(configResolver.resolve(node, "video")).thenReturn(config);
        when(agentPlanClient.createVideoTask(
                any(), anyString(), any(), anyInt(), any(), any(), any())).thenReturn("task-running");
        when(agentPlanClient.getVideoTask(config, "task-running"))
                .thenReturn(Map.of("status", "running"));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> executor.execute(node, Map.of("prompt", "prompt")));

        assertTrue(exception.getMessage().contains("超时或未返回 videoUrl"));
    }

    @Test
    void keepsOriginalUrlWhenMinioIsUnavailable() throws Exception {
        ResolvedAgentPlanConfig config = config("volcengine_agent_plan");
        when(configResolver.resolve(node, "video")).thenReturn(config);
        when(agentPlanClient.createVideoTask(
                any(), anyString(), any(), anyInt(), any(), any(), any())).thenReturn("task-minio");
        when(agentPlanClient.getVideoTask(config, "task-minio")).thenReturn(Map.of(
                "status", "success", "videoUrl", "https://origin.example/video.mp4"));
        when(minioService.uploadFromUrl(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("MinIO unavailable"));

        Map<String, Object> result = executor.execute(node, Map.of("prompt", "prompt"));

        assertEquals("https://origin.example/video.mp4", result.get("videoUrl"));
    }

    @Test
    void exposesStableNodeType() {
        assertEquals("video_generate", executor.getSupportedNodeType());
    }

    private ResolvedAgentPlanConfig config(String provider) {
        return new ResolvedAgentPlanConfig(
                1L,
                provider,
                "https://api.example.com",
                "server-side-secret",
                "video-model",
                "embedding-model",
                "image-model",
                "video-model",
                false
        );
    }
}
