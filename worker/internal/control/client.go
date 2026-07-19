package control

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"time"
)

type ExecutionJob struct {
	ExecutionID int64  `json:"executionId"`
	FlowID      int64  `json:"flowId"`
	Mode        string `json:"mode"`
	StartNodeID string `json:"startNodeId,omitempty"`
}

type apiResult[T any] struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
	Data    T      `json:"data"`
}

type Client struct {
	baseURL  string
	token    string
	workerID string
	http     *http.Client
}

// RetryableError 表示控制面没有返回可确认的 HTTP 响应。
//
// 这类错误可能发生在请求尚未到达 Java 服务时，Worker 必须保留处理中消息；
// 一旦收到 HTTP/业务响应，则以数据库状态为准并确认消息，避免重复执行高风险动作。
type RetryableError struct {
	err error
}

func (e *RetryableError) Error() string { return e.err.Error() }
func (e *RetryableError) Unwrap() error { return e.err }

func IsRetryable(err error) bool {
	var retryable *RetryableError
	return errors.As(err, &retryable)
}

// NewClient 创建只访问 Java 内部 Worker API 的客户端。
// 浏览器 JWT 与 Worker 内部令牌完全分离，避免后台执行权限泄露给前端会话。
func NewClient(baseURL, token, workerID string) *Client {
	return &Client{
		baseURL:  baseURL,
		token:    token,
		workerID: workerID,
		http: &http.Client{
			Timeout: 24 * time.Hour,
		},
	}
}

func (c *Client) Run(ctx context.Context, job ExecutionJob) error {
	body, err := json.Marshal(job)
	if err != nil {
		return fmt.Errorf("encode job: %w", err)
	}
	path := fmt.Sprintf("/api/internal/executions/%d/run", job.ExecutionID)
	var response json.RawMessage
	return c.do(ctx, http.MethodPost, path, body, &response)
}

// Heartbeat 由独立协程周期调用。控制面据此判断 Worker 是否失联。
func (c *Client) Heartbeat(ctx context.Context, executionID int64) error {
	path := fmt.Sprintf("/api/internal/executions/%d/heartbeat", executionID)
	var response json.RawMessage
	return c.do(ctx, http.MethodPost, path, nil, &response)
}

func (c *Client) StaleExecutions(ctx context.Context, staleAfter time.Duration) ([]int64, error) {
	query := url.Values{}
	query.Set("staleAfterSeconds", strconv.Itoa(int(staleAfter.Seconds())))
	var executionIDs []int64
	err := c.do(
		ctx,
		http.MethodGet,
		"/api/internal/executions/stale?"+query.Encode(),
		nil,
		&executionIDs,
	)
	return executionIDs, err
}

func (c *Client) Requeue(ctx context.Context, executionID int64, staleAfter time.Duration) (bool, error) {
	query := url.Values{}
	query.Set("staleAfterSeconds", strconv.Itoa(int(staleAfter.Seconds())))
	path := fmt.Sprintf("/api/internal/executions/%d/requeue?%s", executionID, query.Encode())
	var result struct {
		Requeued bool `json:"requeued"`
	}
	err := c.do(ctx, http.MethodPost, path, nil, &result)
	return result.Requeued, err
}

func (c *Client) do(
	ctx context.Context,
	method string,
	path string,
	body []byte,
	target any,
) error {
	request, err := http.NewRequestWithContext(ctx, method, c.baseURL+path, bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("create request: %w", err)
	}
	request.Header.Set("X-PaiOps-Worker-Token", c.token)
	request.Header.Set("X-PaiOps-Worker-ID", c.workerID)
	if body != nil {
		request.Header.Set("Content-Type", "application/json")
	}

	response, err := c.http.Do(request)
	if err != nil {
		return &RetryableError{err: fmt.Errorf("call control plane: %w", err)}
	}
	defer response.Body.Close()

	// 限制内部响应体，防止异常节点输出占满 Worker 内存。
	payload, err := io.ReadAll(io.LimitReader(response.Body, 4<<20))
	if err != nil {
		return &RetryableError{err: fmt.Errorf("read control plane response: %w", err)}
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return fmt.Errorf("control plane returned HTTP %d: %s", response.StatusCode, payload)
	}

	var result apiResult[json.RawMessage]
	if err := json.Unmarshal(payload, &result); err != nil {
		return fmt.Errorf("decode control plane response: %w", err)
	}
	if result.Code != 200 {
		return fmt.Errorf("control plane rejected request: %s", result.Message)
	}
	if target != nil && len(result.Data) > 0 && string(result.Data) != "null" {
		if err := json.Unmarshal(result.Data, target); err != nil {
			return fmt.Errorf("decode control plane data: %w", err)
		}
	}
	return nil
}
