package config

import (
	"fmt"
	"log/slog"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/redis/go-redis/v9"
)

type Config struct {
	Redis            *redis.Options
	APIBaseURL       string
	WorkerToken      string
	WorkerID         string
	QueueKey         string
	Concurrency      int
	HeartbeatEvery   time.Duration
	RecoveryEvery    time.Duration
	StaleAfter       time.Duration
	ExecutionLockTTL time.Duration
	LockRenewEvery   time.Duration
	LogLevel         slog.Level
}

// Load 只从环境变量读取 Worker 配置。
//
// Worker 不读取项目配置文件，也不持有数据库账号；它只需要 Redis 地址和
// Java 控制面的内部令牌。这样部署时可以单独限制 Worker 的权限和网络范围。
func Load() (Config, error) {
	workerToken := strings.TrimSpace(os.Getenv("PAIOPS_WORKER_TOKEN"))
	if workerToken == "" {
		return Config{}, fmt.Errorf("PAIOPS_WORKER_TOKEN is required")
	}

	hostname, _ := os.Hostname()
	workerID := env("PAIOPS_WORKER_ID", hostname+"-"+strconv.Itoa(os.Getpid()))
	concurrency := envInt("PAIOPS_WORKER_CONCURRENCY", 2, 1, 32)
	redisDB := envInt("REDIS_DB", 0, 0, 15)

	lockTTL := envDuration("PAIOPS_EXECUTION_LOCK_TTL", 90*time.Second)
	lockRenewEvery := envDuration("PAIOPS_EXECUTION_LOCK_RENEW_INTERVAL", 30*time.Second)
	// 续租周期必须显著短于 TTL；配置错误时自动收敛到 TTL 的三分之一。
	if lockRenewEvery >= lockTTL/2 {
		lockRenewEvery = lockTTL / 3
	}

	return Config{
		Redis: &redis.Options{
			Addr:     env("REDIS_ADDR", "redis:6379"),
			Password: os.Getenv("REDIS_PASSWORD"),
			DB:       redisDB,
		},
		APIBaseURL:       strings.TrimRight(env("PAIOPS_API_URL", "http://backend:8084"), "/"),
		WorkerToken:      workerToken,
		WorkerID:         workerID,
		QueueKey:         env("PAIOPS_WORKER_QUEUE", "paiops:execution:queue"),
		Concurrency:      concurrency,
		HeartbeatEvery:   envDuration("PAIOPS_HEARTBEAT_INTERVAL", 10*time.Second),
		RecoveryEvery:    envDuration("PAIOPS_RECOVERY_INTERVAL", 30*time.Second),
		StaleAfter:       envDuration("PAIOPS_STALE_AFTER", 90*time.Second),
		ExecutionLockTTL: lockTTL,
		LockRenewEvery:   lockRenewEvery,
		LogLevel:         parseLogLevel(env("PAIOPS_LOG_LEVEL", "INFO")),
	}, nil
}

func env(name, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(name)); value != "" {
		return value
	}
	return fallback
}

func envInt(name string, fallback, minimum, maximum int) int {
	value, err := strconv.Atoi(strings.TrimSpace(os.Getenv(name)))
	if err != nil {
		return fallback
	}
	if value < minimum {
		return minimum
	}
	if value > maximum {
		return maximum
	}
	return value
}

// envDuration 使用 Go 原生时长格式，例如 10s、2m、1h。
// 非法值回退到安全默认值，避免错误配置造成零间隔轮询。
func envDuration(name string, fallback time.Duration) time.Duration {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		return fallback
	}
	parsed, err := time.ParseDuration(value)
	if err != nil || parsed <= 0 {
		return fallback
	}
	return parsed
}

func parseLogLevel(value string) slog.Level {
	switch strings.ToUpper(value) {
	case "DEBUG":
		return slog.LevelDebug
	case "WARN":
		return slog.LevelWarn
	case "ERROR":
		return slog.LevelError
	default:
		return slog.LevelInfo
	}
}
