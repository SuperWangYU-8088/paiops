package config

import (
	"log/slog"
	"testing"
	"time"
)

func TestLoadRequiresWorkerToken(t *testing.T) {
	t.Setenv("PAIOPS_WORKER_TOKEN", "")

	_, err := Load()
	if err == nil {
		t.Fatal("未配置 Worker 令牌时应拒绝启动")
	}
}

func TestLoadAppliesDefaultsAndNormalizesBaseURL(t *testing.T) {
	t.Setenv("PAIOPS_WORKER_TOKEN", "test-token")
	t.Setenv("PAIOPS_API_URL", "http://backend:8084///")
	t.Setenv("PAIOPS_WORKER_ID", "worker-test")
	t.Setenv("PAIOPS_WORKER_CONCURRENCY", "")
	t.Setenv("PAIOPS_HEARTBEAT_INTERVAL", "")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("加载默认配置失败: %v", err)
	}

	if cfg.APIBaseURL != "http://backend:8084" {
		t.Fatalf("控制面地址未去除尾部斜杠: %q", cfg.APIBaseURL)
	}
	if cfg.Concurrency != 2 {
		t.Fatalf("默认并发数应为 2，实际为 %d", cfg.Concurrency)
	}
	if cfg.HeartbeatEvery != 10*time.Second {
		t.Fatalf("默认心跳间隔错误: %s", cfg.HeartbeatEvery)
	}
	if cfg.ExecutionLockTTL != 90*time.Second || cfg.LockRenewEvery != 30*time.Second {
		t.Fatalf("默认锁租约或续租间隔错误: ttl=%s renew=%s", cfg.ExecutionLockTTL, cfg.LockRenewEvery)
	}
	if cfg.LogLevel != slog.LevelInfo {
		t.Fatalf("默认日志级别错误: %s", cfg.LogLevel)
	}
}

func TestLoadClampsLockRenewInterval(t *testing.T) {
	t.Setenv("PAIOPS_WORKER_TOKEN", "test-token")
	t.Setenv("PAIOPS_EXECUTION_LOCK_TTL", "30s")
	t.Setenv("PAIOPS_EXECUTION_LOCK_RENEW_INTERVAL", "20s")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("加载锁配置失败: %v", err)
	}
	if cfg.LockRenewEvery != 10*time.Second {
		t.Fatalf("续租间隔应自动收敛为 TTL 的三分之一，实际为 %s", cfg.LockRenewEvery)
	}
}

func TestLoadClampsUnsafeValues(t *testing.T) {
	t.Setenv("PAIOPS_WORKER_TOKEN", "test-token")
	t.Setenv("PAIOPS_WORKER_CONCURRENCY", "999")
	t.Setenv("REDIS_DB", "-1")
	t.Setenv("PAIOPS_HEARTBEAT_INTERVAL", "0s")
	t.Setenv("PAIOPS_RECOVERY_INTERVAL", "not-a-duration")
	t.Setenv("PAIOPS_LOG_LEVEL", "warn")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("加载限幅配置失败: %v", err)
	}

	// 高并发和零间隔轮询都可能拖垮个人服务器，因此配置层直接限制在安全范围内。
	if cfg.Concurrency != 32 {
		t.Fatalf("并发数上限应为 32，实际为 %d", cfg.Concurrency)
	}
	if cfg.Redis.DB != 0 {
		t.Fatalf("Redis DB 下限应回退为 0，实际为 %d", cfg.Redis.DB)
	}
	if cfg.HeartbeatEvery != 10*time.Second {
		t.Fatalf("零心跳间隔应回退默认值，实际为 %s", cfg.HeartbeatEvery)
	}
	if cfg.RecoveryEvery != 30*time.Second {
		t.Fatalf("非法恢复间隔应回退默认值，实际为 %s", cfg.RecoveryEvery)
	}
	if cfg.LogLevel != slog.LevelWarn {
		t.Fatalf("日志级别解析错误: %s", cfg.LogLevel)
	}
}
