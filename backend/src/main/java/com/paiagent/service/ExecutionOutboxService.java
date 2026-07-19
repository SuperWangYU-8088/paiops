package com.paiagent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.paiagent.entity.ExecutionOutbox;
import com.paiagent.mapper.ExecutionOutboxMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 可靠任务投递 Outbox。
 *
 * <p>业务事务只写 MySQL；提交后再尝试写 Redis。Redis 暂时不可用时记录保留为
 * PENDING，由定时任务指数退避重试。若进程在 LPUSH 后、标记 SENT 前崩溃，
 * 消息可能重复但不会丢失，Go Worker 的执行锁和任务状态检查负责去重。</p>
 */
@Slf4j
@Service
public class ExecutionOutboxService {

    private final ExecutionOutboxMapper outboxMapper;
    private final StringRedisTemplate redisTemplate;

    @Value("${paiops.worker.outbox-scheduler-enabled:true}")
    private boolean schedulerEnabled;

    public ExecutionOutboxService(ExecutionOutboxMapper outboxMapper,
                                  StringRedisTemplate redisTemplate) {
        this.outboxMapper = outboxMapper;
        this.redisTemplate = redisTemplate;
    }

    public void persistAndDispatchAfterCommit(Long executionId, String queueKey, String payload) {
        ExecutionOutbox outbox = new ExecutionOutbox();
        outbox.setExecutionId(executionId);
        outbox.setQueueKey(queueKey);
        outbox.setPayload(payload);
        outbox.setStatus("PENDING");
        outbox.setAttempts(0);
        outbox.setNextAttemptAt(LocalDateTime.now());
        outboxMapper.insert(outbox);

        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            dispatch(outbox.getId());
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                dispatch(outbox.getId());
            }
        });
    }

    @Scheduled(fixedDelayString = "${paiops.worker.outbox-dispatch-interval-ms:5000}")
    public void retryPending() {
        if (!schedulerEnabled) {
            return;
        }
        // 上次进程可能在“领取后、发送前”退出，超过两分钟的 PROCESSING 重新开放领取。
        outboxMapper.update(null, new LambdaUpdateWrapper<ExecutionOutbox>()
                .eq(ExecutionOutbox::getStatus, "PROCESSING")
                .lt(ExecutionOutbox::getUpdatedAt, LocalDateTime.now().minusMinutes(2))
                .set(ExecutionOutbox::getStatus, "PENDING")
                .set(ExecutionOutbox::getNextAttemptAt, LocalDateTime.now()));

        List<ExecutionOutbox> pending = outboxMapper.selectList(
                new LambdaQueryWrapper<ExecutionOutbox>()
                        .eq(ExecutionOutbox::getStatus, "PENDING")
                        .le(ExecutionOutbox::getNextAttemptAt, LocalDateTime.now())
                        .orderByAsc(ExecutionOutbox::getId)
                        .last("LIMIT 100"));
        pending.forEach(item -> dispatch(item.getId()));
    }

    public void dispatch(Long outboxId) {
        int claimed = outboxMapper.update(null, new LambdaUpdateWrapper<ExecutionOutbox>()
                .eq(ExecutionOutbox::getId, outboxId)
                .eq(ExecutionOutbox::getStatus, "PENDING")
                .set(ExecutionOutbox::getStatus, "PROCESSING"));
        if (claimed != 1) {
            return;
        }

        ExecutionOutbox item = outboxMapper.selectById(outboxId);
        try {
            redisTemplate.opsForList().leftPush(item.getQueueKey(), item.getPayload());
            outboxMapper.update(null, new LambdaUpdateWrapper<ExecutionOutbox>()
                    .eq(ExecutionOutbox::getId, outboxId)
                    .eq(ExecutionOutbox::getStatus, "PROCESSING")
                    .set(ExecutionOutbox::getStatus, "SENT")
                    .set(ExecutionOutbox::getSentAt, LocalDateTime.now())
                    .set(ExecutionOutbox::getLastError, null));
        } catch (Exception exception) {
            int attempts = (item.getAttempts() == null ? 0 : item.getAttempts()) + 1;
            long delaySeconds = Math.min(300, 1L << Math.min(attempts, 8));
            outboxMapper.update(null, new LambdaUpdateWrapper<ExecutionOutbox>()
                    .eq(ExecutionOutbox::getId, outboxId)
                    .eq(ExecutionOutbox::getStatus, "PROCESSING")
                    .set(ExecutionOutbox::getStatus, "PENDING")
                    .set(ExecutionOutbox::getAttempts, attempts)
                    .set(ExecutionOutbox::getNextAttemptAt, LocalDateTime.now().plusSeconds(delaySeconds))
                    .set(ExecutionOutbox::getLastError, abbreviate(exception.getMessage(), 1000)));
            log.warn("执行任务 Outbox 投递失败，将自动重试: outboxId={}, executionId={}, attempts={}",
                    outboxId, item.getExecutionId(), attempts, exception);
        }
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
