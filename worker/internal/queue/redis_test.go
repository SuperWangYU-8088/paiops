package queue

import "testing"

func TestExecutionIDFromPayload(t *testing.T) {
	id, ok := executionIDFromPayload(`{"executionId":42,"flowId":7,"mode":"START"}`)
	if !ok || id != 42 {
		t.Fatalf("任务 ID 解析错误: id=%d ok=%v", id, ok)
	}
}

func TestExecutionIDFromPayloadRejectsPoisonMessages(t *testing.T) {
	tests := []string{
		`not-json`,
		`{}`,
		`{"executionId":0}`,
		`{"executionId":-1}`,
	}
	for _, payload := range tests {
		if id, ok := executionIDFromPayload(payload); ok {
			t.Fatalf("毒消息不应被接受: payload=%q id=%d", payload, id)
		}
	}
}
