package worker

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"sync"
	"time"

	"paiops/worker/internal/config"
	"paiops/worker/internal/control"
	"paiops/worker/internal/queue"
)

type Runner struct {
	config  config.Config
	queue   *queue.RedisQueue
	control *control.Client
	logger  *slog.Logger
}

func NewRunner(
	cfg config.Config,
	redisQueue *queue.RedisQueue,
	controlClient *control.Client,
	logger *slog.Logger,
) *Runner {
	return &Runner{
		config:  cfg,
		queue:   redisQueue,
		control: controlClient,
		logger:  logger,
	}
}

func (r *Runner) Run(ctx context.Context) error {
	// 每个消费协程对应一个并发槽位；并发数由环境变量限制，
	// 个人部署默认仅为 2，避免多个重任务挤占整台服务器。
	var group sync.WaitGroup
	for index := 0; index < r.config.Concurrency; index++ {
		group.Add(1)
		go func(slot int) {
			defer group.Done()
			r.consume(ctx, slot)
		}(index + 1)
	}

	group.Add(1)
	go func() {
		defer group.Done()
		r.recoverStaleExecutions(ctx)
	}()

	<-ctx.Done()
	group.Wait()
	return nil
}

func (r *Runner) consume(ctx context.Context, slot int) {
	logger := r.logger.With("slot", slot)
	processingKey := fmt.Sprintf(
		"paiops:execution:processing:%s:%d",
		r.config.WorkerID,
		slot,
	)
	for ctx.Err() == nil {
		payload, err := r.queue.Pop(ctx, r.config.QueueKey, processingKey, 5*time.Second)
		if errors.Is(err, queue.ErrQueueEmpty) {
			continue
		}
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			logger.Error("cannot read execution queue", "error", err)
			continue
		}

		var job control.ExecutionJob
		if err := json.Unmarshal([]byte(payload), &job); err != nil {
			logger.Error("discarding invalid execution job", "error", err)
			if ackErr := r.queue.Ack(ctx, processingKey, payload); ackErr != nil {
				logger.Error("cannot acknowledge invalid job", "error", ackErr)
			}
			continue
		}
		if r.execute(ctx, job, logger) {
			if err := r.queue.Ack(ctx, processingKey, payload); err != nil {
				logger.Error("cannot acknowledge execution job", "execution_id", job.ExecutionID, "error", err)
			}
		}
	}
}

// execute 负责一次任务的完整占有周期：
// 先获取 Redis 分布式锁，再启动心跳，最后调用控制面执行 DAG。
// 即使进程异常退出，锁 TTL 和失联扫描也能让其他 Worker 接管。
func (r *Runner) execute(ctx context.Context, job control.ExecutionJob, logger *slog.Logger) bool {
	lockKey := fmt.Sprintf("paiops:execution:lock:%d", job.ExecutionID)
	lockOwner := fmt.Sprintf("%s:%d", r.config.WorkerID, time.Now().UnixNano())
	acquired, err := r.queue.TryLock(ctx, lockKey, lockOwner, r.config.ExecutionLockTTL)
	if err != nil {
		logger.Error("cannot acquire execution lock", "execution_id", job.ExecutionID, "error", err)
		// Redis 暂时不可用时保留处理中消息，恢复协程稍后会重新投递。
		return false
	}
	if !acquired {
		logger.Warn("execution is already owned", "execution_id", job.ExecutionID)
		// 这是同一任务的重复消息，原持有者仍在执行，本条可以安全确认。
		return true
	}
	defer func() {
		// 使用独立短上下文释放锁，避免主任务取消后锁只能等待 TTL。
		releaseCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if err := r.queue.ReleaseLock(releaseCtx, lockKey, lockOwner); err != nil {
			logger.Error("cannot release execution lock", "execution_id", job.ExecutionID, "error", err)
		}
	}()

	executionLogger := logger.With(
		"execution_id", job.ExecutionID,
		"flow_id", job.FlowID,
		"mode", job.Mode,
	)
	executionLogger.Info("execution claimed")

	runCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	heartbeatDone := make(chan struct{})
	lockRenewDone := make(chan struct{})
	go func() {
		defer close(heartbeatDone)
		r.sendHeartbeats(runCtx, job.ExecutionID, executionLogger)
	}()
	go func() {
		defer close(lockRenewDone)
		r.renewExecutionLock(runCtx, cancel, lockKey, lockOwner, job.ExecutionID, executionLogger)
	}()

	err = r.control.Run(runCtx, job)
	cancel()
	<-heartbeatDone
	<-lockRenewDone
	if err != nil {
		executionLogger.Error("execution failed", "error", err)
		if control.IsRetryable(err) {
			// 未收到控制面的确认响应时不能删除消息。释放执行锁后，恢复协程会把它重新入队；
			// 若第一次请求其实已经完成，下一次请求会由数据库终态安全拒绝并确认消息。
			return false
		}
		return true
	}
	executionLogger.Info("execution finished")
	return true
}

// renewExecutionLock 为长任务持续续租。续租失败或发现锁已丢失时立即取消控制面请求，
// 防止两个 Worker 在锁 TTL 到期后并行执行同一项变更。
func (r *Runner) renewExecutionLock(
	ctx context.Context,
	cancel context.CancelFunc,
	lockKey string,
	lockOwner string,
	executionID int64,
	logger *slog.Logger,
) {
	ticker := time.NewTicker(r.config.LockRenewEvery)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			renewCtx, renewCancel := context.WithTimeout(ctx, 5*time.Second)
			renewed, err := r.queue.RenewLock(
				renewCtx,
				lockKey,
				lockOwner,
				r.config.ExecutionLockTTL,
			)
			renewCancel()
			if err != nil {
				logger.Error("execution lock renewal failed", "execution_id", executionID, "error", err)
				cancel()
				return
			}
			if !renewed {
				logger.Error("execution lock lost; canceling run", "execution_id", executionID)
				cancel()
				return
			}
		}
	}
}

func (r *Runner) sendHeartbeats(ctx context.Context, executionID int64, logger *slog.Logger) {
	ticker := time.NewTicker(r.config.HeartbeatEvery)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			heartbeatCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
			err := r.control.Heartbeat(heartbeatCtx, executionID)
			cancel()
			if err != nil && ctx.Err() == nil {
				logger.Warn("heartbeat failed", "error", err)
			}
		}
	}
}

// recoverStaleExecutions 定期询问控制面有哪些 RUNNING 任务心跳超时，
// 再由控制面以事务方式重置状态并重新入队。Worker 不直接操作 MySQL。
func (r *Runner) recoverStaleExecutions(ctx context.Context) {
	ticker := time.NewTicker(r.config.RecoveryEvery)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			recoveryCtx, cancel := context.WithTimeout(ctx, 20*time.Second)
			r.recoverAbandonedMessages(recoveryCtx)
			r.recoverOnce(recoveryCtx)
			cancel()
		}
	}
}

func (r *Runner) recoverAbandonedMessages(ctx context.Context) {
	recovered, err := r.queue.RecoverAbandoned(
		ctx,
		r.config.QueueKey,
		"paiops:execution:processing:*",
	)
	if err != nil {
		r.logger.Warn("abandoned queue recovery failed", "error", err)
		return
	}
	if recovered > 0 {
		r.logger.Info("abandoned execution messages requeued", "count", recovered)
	}
}

func (r *Runner) recoverOnce(ctx context.Context) {
	executionIDs, err := r.control.StaleExecutions(ctx, r.config.StaleAfter)
	if err != nil {
		r.logger.Warn("stale execution scan failed", "error", err)
		return
	}
	for _, executionID := range executionIDs {
		requeued, err := r.control.Requeue(ctx, executionID, r.config.StaleAfter)
		if err != nil {
			r.logger.Warn("stale execution requeue failed",
				"execution_id", executionID,
				"error", err,
			)
			continue
		}
		if requeued {
			r.logger.Info("stale execution requeued", "execution_id", executionID)
		}
	}
}
