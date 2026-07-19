package queue

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"
)

var ErrQueueEmpty = errors.New("queue is empty")

type RedisQueue struct {
	client *redis.Client
}

func NewRedisQueue(options *redis.Options) *RedisQueue {
	return &RedisQueue{client: redis.NewClient(options)}
}

func (q *RedisQueue) Close() error {
	return q.client.Close()
}

func (q *RedisQueue) Pop(
	ctx context.Context,
	queueKey string,
	processingKey string,
	wait time.Duration,
) (string, error) {
	// BRPOPLPUSH 先把任务原子移动到处理中列表，再交给 Worker。
	// Worker 崩溃时任务仍留在 Redis，不会像 BRPOP 那样永久丢失。
	payload, err := q.client.BRPopLPush(ctx, queueKey, processingKey, wait).Result()
	if errors.Is(err, redis.Nil) {
		return "", ErrQueueEmpty
	}
	if err != nil {
		return "", err
	}
	return payload, nil
}

// Ack 只有在任务已完成、已明确失败或确认是重复消息后才调用。
func (q *RedisQueue) Ack(ctx context.Context, processingKey, payload string) error {
	return q.client.LRem(ctx, processingKey, 1, payload).Err()
}

func (q *RedisQueue) TryLock(
	ctx context.Context,
	key string,
	owner string,
	ttl time.Duration,
) (bool, error) {
	return q.client.SetNX(ctx, key, owner, ttl).Result()
}

// ReleaseLock 使用“比较后删除”的 Lua 脚本。
// 不能直接 DEL：锁过期后可能已被另一个 Worker 重新获取，直接删除会误删新锁。
func (q *RedisQueue) ReleaseLock(ctx context.Context, key, owner string) error {
	const releaseScript = `
if redis.call("GET", KEYS[1]) == ARGV[1] then
  return redis.call("DEL", KEYS[1])
end
return 0
`
	return q.client.Eval(ctx, releaseScript, []string{key}, owner).Err()
}

// RenewLock 仅允许当前持有者延长 TTL。返回 false 表示锁已经过期或被其他 Worker 接管。
func (q *RedisQueue) RenewLock(
	ctx context.Context,
	key string,
	owner string,
	ttl time.Duration,
) (bool, error) {
	const renewScript = `
if redis.call("GET", KEYS[1]) == ARGV[1] then
  return redis.call("PEXPIRE", KEYS[1], ARGV[2])
end
return 0
`
	result, err := q.client.Eval(
		ctx,
		renewScript,
		[]string{key},
		owner,
		ttl.Milliseconds(),
	).Int64()
	return result == 1, err
}

// RecoverAbandoned 扫描所有处理中列表，把“没有执行锁”的遗留任务原子放回主队列。
// 正在运行的任务一定持有 paiops:execution:lock:<id>，因此不会被错误回收。
func (q *RedisQueue) RecoverAbandoned(
	ctx context.Context,
	queueKey string,
	processingPattern string,
) (int, error) {
	var cursor uint64
	recovered := 0
	for {
		keys, next, err := q.client.Scan(ctx, cursor, processingPattern, 100).Result()
		if err != nil {
			return recovered, err
		}
		for _, processingKey := range keys {
			payloads, err := q.client.LRange(ctx, processingKey, 0, 199).Result()
			if err != nil {
				return recovered, err
			}
			for _, payload := range payloads {
				executionID, ok := executionIDFromPayload(payload)
				if !ok {
					// 无法解析的消息没有重试价值，直接确认，防止毒消息无限循环。
					if err := q.Ack(ctx, processingKey, payload); err != nil {
						return recovered, err
					}
					continue
				}
				moved, err := q.requeueIfUnlocked(
					ctx,
					queueKey,
					processingKey,
					fmt.Sprintf("paiops:execution:lock:%d", executionID),
					payload,
				)
				if err != nil {
					return recovered, err
				}
				if moved {
					recovered++
				}
			}
		}
		cursor = next
		if cursor == 0 {
			return recovered, nil
		}
	}
}

func (q *RedisQueue) requeueIfUnlocked(
	ctx context.Context,
	queueKey string,
	processingKey string,
	lockKey string,
	payload string,
) (bool, error) {
	const script = `
if redis.call("EXISTS", KEYS[3]) == 0 then
  local removed = redis.call("LREM", KEYS[2], 1, ARGV[1])
  if removed == 1 then
    redis.call("LPUSH", KEYS[1], ARGV[1])
    return 1
  end
end
return 0
`
	result, err := q.client.Eval(
		ctx,
		script,
		[]string{queueKey, processingKey, lockKey},
		payload,
	).Int64()
	return result == 1, err
}

func executionIDFromPayload(payload string) (int64, bool) {
	var envelope struct {
		ExecutionID int64 `json:"executionId"`
	}
	if err := json.Unmarshal([]byte(payload), &envelope); err != nil || envelope.ExecutionID <= 0 {
		return 0, false
	}
	return envelope.ExecutionID, true
}
