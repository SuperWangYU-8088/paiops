package main

import (
	"context"
	"log/slog"
	"os"
	"os/signal"
	"syscall"

	"paiops/worker/internal/config"
	"paiops/worker/internal/control"
	"paiops/worker/internal/queue"
	"paiops/worker/internal/worker"
)

func main() {
	cfg, err := config.Load()
	if err != nil {
		slog.Error("worker configuration is invalid", "error", err)
		os.Exit(1)
	}

	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{
		Level: cfg.LogLevel,
	}))
	slog.SetDefault(logger)

	redisQueue := queue.NewRedisQueue(cfg.Redis)
	defer redisQueue.Close()

	controlClient := control.NewClient(cfg.APIBaseURL, cfg.WorkerToken, cfg.WorkerID)
	runner := worker.NewRunner(cfg, redisQueue, controlClient, logger)

	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()

	logger.Info("PaiOps worker started",
		"worker_id", cfg.WorkerID,
		"concurrency", cfg.Concurrency,
		"queue", cfg.QueueKey,
	)
	if err := runner.Run(ctx); err != nil {
		logger.Error("worker stopped unexpectedly", "error", err)
		os.Exit(1)
	}
	logger.Info("PaiOps worker stopped")
}
