package com.paiagent.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class AlertWebhookRequest {

    private String source;
    private String status;
    private List<AlertItem> alerts;

    @Data
    public static class AlertItem {
        private String status;
        private String fingerprint;
        private Map<String, String> labels;
        private Map<String, String> annotations;
        private String startsAt;
        private String endsAt;
    }
}
