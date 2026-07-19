package control

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestRunSendsInternalIdentityAndJob(t *testing.T) {
	var received ExecutionJob
	server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		if request.URL.Path != "/api/internal/executions/42/run" {
			t.Errorf("请求路径错误: %s", request.URL.Path)
		}
		if request.Header.Get("X-PaiOps-Worker-Token") != "secret-token" {
			t.Error("请求未携带独立 Worker 令牌")
		}
		if request.Header.Get("X-PaiOps-Worker-ID") != "worker-01" {
			t.Error("请求未携带 Worker 标识")
		}
		if err := json.NewDecoder(request.Body).Decode(&received); err != nil {
			t.Fatalf("解析任务请求失败: %v", err)
		}
		response.Header().Set("Content-Type", "application/json")
		_, _ = response.Write([]byte(`{"code":200,"message":"操作成功","data":{}}`))
	}))
	defer server.Close()

	client := NewClient(server.URL, "secret-token", "worker-01")
	job := ExecutionJob{ExecutionID: 42, FlowID: 7, Mode: "START"}
	if err := client.Run(context.Background(), job); err != nil {
		t.Fatalf("调用执行接口失败: %v", err)
	}
	if received != job {
		t.Fatalf("任务内容不一致: %+v", received)
	}
}

func TestStaleExecutionsDecodesDataAndDuration(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		if request.URL.Query().Get("staleAfterSeconds") != "95" {
			t.Errorf("失联阈值未按秒传递: %s", request.URL.RawQuery)
		}
		_, _ = response.Write([]byte(`{"code":200,"message":"操作成功","data":[11,12]}`))
	}))
	defer server.Close()

	client := NewClient(server.URL, "secret-token", "worker-01")
	ids, err := client.StaleExecutions(context.Background(), 95*time.Second)
	if err != nil {
		t.Fatalf("查询失联任务失败: %v", err)
	}
	if len(ids) != 2 || ids[0] != 11 || ids[1] != 12 {
		t.Fatalf("失联任务解析错误: %v", ids)
	}
}

func TestClientReportsHTTPAndBusinessErrors(t *testing.T) {
	tests := []struct {
		name       string
		statusCode int
		body       string
		want       string
	}{
		{
			name:       "HTTP unauthorized",
			statusCode: http.StatusUnauthorized,
			body:       `{"code":401,"message":"令牌无效"}`,
			want:       "HTTP 401",
		},
		{
			name:       "business rejection",
			statusCode: http.StatusOK,
			body:       `{"code":409,"message":"任务状态冲突","data":null}`,
			want:       "任务状态冲突",
		},
	}

	for _, testCase := range tests {
		t.Run(testCase.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
				response.WriteHeader(testCase.statusCode)
				_, _ = response.Write([]byte(testCase.body))
			}))
			defer server.Close()

			client := NewClient(server.URL, "secret-token", "worker-01")
			err := client.Heartbeat(context.Background(), 1)
			if err == nil || !strings.Contains(err.Error(), testCase.want) {
				t.Fatalf("错误信息不符合预期，实际为: %v", err)
			}
		})
	}
}

func TestTransportFailureIsRetryableButHTTPResponseIsNot(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		response.WriteHeader(http.StatusInternalServerError)
		_, _ = response.Write([]byte(`{"code":500,"message":"执行失败"}`))
	}))
	client := NewClient(server.URL, "secret-token", "worker-01")
	httpErr := client.Run(context.Background(), ExecutionJob{ExecutionID: 1, FlowID: 2})
	if httpErr == nil || IsRetryable(httpErr) {
		t.Fatalf("已收到 HTTP 响应时不应重复投递: %v", httpErr)
	}
	server.Close()

	transportErr := client.Run(context.Background(), ExecutionJob{ExecutionID: 1, FlowID: 2})
	if transportErr == nil || !IsRetryable(transportErr) {
		t.Fatalf("连接失败时必须保留消息重试: %v", transportErr)
	}
}
