package com.paiagent.engine.executor.impl;

import com.paiagent.dto.ExecutionEvent;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.service.AgentPlanConfigResolver;
import com.paiagent.service.AgnesVideoClient;
import com.paiagent.service.MinioService;
import com.paiagent.service.ResolvedAgentPlanConfig;
import com.paiagent.service.VolcengineAgentPlanClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

@Slf4j
@Component
public class VideoGenerateNodeExecutor extends AbstractAgentPlanNodeExecutor {

    private static final int DEFAULT_MAX_POLLS = 60;
    private static final long DEFAULT_POLL_INTERVAL_MS = 5_000;

    private final AgentPlanConfigResolver configResolver;
    private final VolcengineAgentPlanClient agentPlanClient;
    private final AgnesVideoClient agnesVideoClient;
    private final MinioService minioService;
    private final int maxPolls;
    private final long pollIntervalMs;
    private final Sleeper sleeper;

    @Autowired
    public VideoGenerateNodeExecutor(AgentPlanConfigResolver configResolver,
                                     VolcengineAgentPlanClient agentPlanClient,
                                     AgnesVideoClient agnesVideoClient,
                                     MinioService minioService) {
        this.configResolver = configResolver;
        this.agentPlanClient = agentPlanClient;
        this.agnesVideoClient = agnesVideoClient;
        this.minioService = minioService;
        this.maxPolls = DEFAULT_MAX_POLLS;
        this.pollIntervalMs = DEFAULT_POLL_INTERVAL_MS;
        this.sleeper = Thread::sleep;
    }

    public VideoGenerateNodeExecutor(AgentPlanConfigResolver configResolver,
                                     VolcengineAgentPlanClient agentPlanClient,
                                     MinioService minioService) {
        this(configResolver, agentPlanClient, new AgnesVideoClient(), minioService);
    }

    /** 测试构造器可替换轮询等待，避免单元测试真实休眠五秒。 */
    VideoGenerateNodeExecutor(AgentPlanConfigResolver configResolver,
                              VolcengineAgentPlanClient agentPlanClient,
                              AgnesVideoClient agnesVideoClient,
                              MinioService minioService,
                              int maxPolls,
                              long pollIntervalMs,
                              Sleeper sleeper) {
        this.configResolver = configResolver;
        this.agentPlanClient = agentPlanClient;
        this.agnesVideoClient = agnesVideoClient;
        this.minioService = minioService;
        this.maxPolls = maxPolls;
        this.pollIntervalMs = pollIntervalMs;
        this.sleeper = sleeper;
    }

    @Override
    public Map<String, Object> execute(WorkflowNode node, Map<String, Object> input) throws Exception {
        return execute(node, input, null);
    }

    @Override
    public Map<String, Object> execute(WorkflowNode node, Map<String, Object> input,
                                       Consumer<ExecutionEvent> progressCallback) throws Exception {
        String prompt = configuredTextInput(node, input, "prompt");
        if (prompt == null) {
            prompt = textValue(node, input, "prompt", "input");
        }
        if (prompt == null) {
            throw new IllegalArgumentException("视频生成节点缺少 prompt");
        }
        String referenceImageUrl = configuredTextInput(node, input, "referenceImageUrl");
        if (referenceImageUrl == null) {
            referenceImageUrl = stringData(node, "referenceImageUrl", null);
        }

        ResolvedAgentPlanConfig config = configResolver.resolve(node, "video");
        configResolver.validateApiConfig(config, "视频生成");

        boolean agnesProvider = isAgnesProvider(config.provider());
        int duration = intData(node, "duration", 5);
        String resolution = stringData(node, "resolution", null);
        String ratio = stringData(node, "ratio", "adaptive");
        String cameraMotion = stringData(node, "cameraMotion", null);
        emit(node, progressCallback, buildSubmitMessage(config, duration, referenceImageUrl), Map.of(
                "provider", config.provider(),
                "model", config.model(),
                "duration", duration,
                "hasFirstFrame", StringUtils.hasText(referenceImageUrl)
        ));
        String taskId = createVideoTask(
                agnesProvider,
                config,
                prompt,
                referenceImageUrl,
                duration,
                resolution,
                ratio,
                cameraMotion
        );
        if (!StringUtils.hasText(taskId)) {
            throw new IllegalStateException("视频生成接口未返回 taskId");
        }
        emit(node, progressCallback, "视频任务已提交: taskId=" + shortId(taskId), Map.of("taskId", taskId));

        Map<String, Object> task = Map.of("status", "running");
        long startedAt = System.currentTimeMillis();
        for (int i = 0; i < maxPolls; i++) {
            sleeper.sleep(pollIntervalMs);
            task = getVideoTask(agnesProvider, config, taskId);
            String status = String.valueOf(task.getOrDefault("status", ""));
            emit(node, progressCallback, buildPollingMessage(taskId, i + 1, status, task.get("progress"), startedAt),
                    buildPollingData(taskId, i + 1, status, task.get("progress"), startedAt));
            if (isSuccess(status)) {
                emit(node, progressCallback, buildCompleteMessage(taskId, status, task.get("progress")), buildPollingData(taskId, i + 1, status, task.get("progress"), startedAt));
                break;
            }
            if (isFailed(status)) {
                throw new IllegalStateException("视频生成失败: " + task);
            }
        }

        String videoUrl = asText(task.get("videoUrl"));
        String coverUrl = asText(task.get("coverUrl"));
        if (!StringUtils.hasText(videoUrl)) {
            throw new IllegalStateException("视频生成超时或未返回 videoUrl: " + task);
        }

        emit(node, progressCallback, "视频转存中: taskId=" + shortId(taskId), Map.of("taskId", taskId, "sourceUrl", videoUrl));
        String persistedVideoUrl = persistMedia(videoUrl, "videos/generated-" + taskId + ".mp4", "video/mp4");

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("taskId", taskId);
        output.put("status", "succeeded");
        output.put("videoUrl", persistedVideoUrl);
        output.put("coverUrl", coverUrl);
        output.put("model", config.model());
        output.put("metadata", task.get("raw"));
        output.put("output", persistedVideoUrl);
        applyOutputParams(node, output);
        return output;
    }

    private String buildSubmitMessage(ResolvedAgentPlanConfig config, int duration, String referenceImageUrl) {
        return "视频任务提交中: provider=" + config.provider()
                + ", model=" + config.model()
                + ", 时长=" + duration + "s"
                + ", 首帧=" + (StringUtils.hasText(referenceImageUrl) ? "有" : "无");
    }

    private String buildPollingMessage(String taskId, int poll, String status, Object progress, long startedAt) {
        StringBuilder message = new StringBuilder("视频生成中: 第 ")
                .append(poll)
                .append("/")
                .append(maxPolls)
                .append(" 次查询, 已等待 ")
                .append(elapsedSeconds(startedAt))
                .append("s, 状态=")
                .append(StringUtils.hasText(status) ? status : "unknown");
        String progressText = formatProgress(progress);
        if (StringUtils.hasText(progressText)) {
            message.append(", 进度=").append(progressText);
        }
        message.append(", taskId=").append(shortId(taskId));
        return message.toString();
    }

    private String buildCompleteMessage(String taskId, String status, Object progress) {
        StringBuilder message = new StringBuilder("视频生成完成: 状态=")
                .append(StringUtils.hasText(status) ? status : "completed");
        String progressText = formatProgress(progress);
        if (StringUtils.hasText(progressText)) {
            message.append(", 进度=").append(progressText);
        }
        message.append(", taskId=").append(shortId(taskId));
        return message.toString();
    }

    private Map<String, Object> buildPollingData(String taskId, int poll, String status, Object progress, long startedAt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", taskId);
        data.put("poll", poll);
        data.put("maxPolls", maxPolls);
        data.put("elapsedSeconds", elapsedSeconds(startedAt));
        data.put("status", status);
        if (progress != null) {
            data.put("progress", progress);
        }
        return data;
    }

    private long elapsedSeconds(long startedAt) {
        return Math.max(0, (System.currentTimeMillis() - startedAt) / 1_000);
    }

    private String formatProgress(Object progress) {
        if (progress == null) {
            return null;
        }
        if (progress instanceof Number number) {
            double value = number.doubleValue();
            if (value <= 1.0) {
                value *= 100;
            }
            if (Math.abs(value - Math.rint(value)) < 0.0001) {
                return String.valueOf((int) Math.rint(value)) + "%";
            }
            return String.format("%.1f%%", value);
        }
        String text = String.valueOf(progress).trim();
        if (!StringUtils.hasText(text)) {
            return null;
        }
        return text.endsWith("%") ? text : text + "%";
    }

    private String shortId(String taskId) {
        if (!StringUtils.hasText(taskId) || taskId.length() <= 16) {
            return taskId;
        }
        return taskId.substring(0, 8) + "..." + taskId.substring(taskId.length() - 6);
    }

    private String createVideoTask(boolean agnesProvider, ResolvedAgentPlanConfig config, String prompt,
                                   String referenceImageUrl, int duration, String resolution,
                                   String ratio, String cameraMotion) throws Exception {
        if (agnesProvider) {
            return agnesVideoClient.createVideoTask(config, prompt, referenceImageUrl, duration, resolution, ratio, cameraMotion);
        }
        return agentPlanClient.createVideoTask(config, prompt, referenceImageUrl, duration, resolution, ratio, cameraMotion);
    }

    private Map<String, Object> getVideoTask(boolean agnesProvider, ResolvedAgentPlanConfig config, String taskId) throws Exception {
        if (agnesProvider) {
            return agnesVideoClient.getVideoTask(config, taskId);
        }
        return agentPlanClient.getVideoTask(config, taskId);
    }

    private boolean isSuccess(String status) {
        String normalized = status.toLowerCase();
        return normalized.contains("success") || normalized.contains("succeed") || normalized.contains("completed");
    }

    private boolean isFailed(String status) {
        String normalized = status.toLowerCase();
        return normalized.contains("fail") || normalized.contains("error") || normalized.contains("cancel");
    }

    private boolean isAgnesProvider(String provider) {
        return StringUtils.hasText(provider) && "agnes".equals(provider.trim().toLowerCase());
    }

    private String persistMedia(String sourceUrl, String objectName, String contentType) {
        try {
            return minioService.uploadFromUrl(sourceUrl, objectName, contentType);
        } catch (Exception e) {
            log.warn("视频转存 MinIO 失败，保留原始 URL: {}", e.getMessage());
            return sourceUrl;
        }
    }

    private void emit(WorkflowNode node, Consumer<ExecutionEvent> progressCallback, String message, Object data) {
        if (progressCallback != null) {
            progressCallback.accept(ExecutionEvent.nodeProgress(node.getId(), node.getType(), message, data));
        }
    }

    @Override
    public String getSupportedNodeType() {
        return "video_generate";
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
