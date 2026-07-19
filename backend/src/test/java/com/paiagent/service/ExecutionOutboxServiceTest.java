package com.paiagent.service;

import com.paiagent.entity.ExecutionOutbox;
import com.paiagent.mapper.ExecutionOutboxMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutionOutboxServiceTest {

    @AfterEach
    void clearTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void shouldDispatchOnlyAfterDatabaseTransactionCommits() {
        Fixture fixture = fixture();
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        fixture.service.persistAndDispatchAfterCommit(11L, "queue", "payload");

        verify(fixture.listOperations, never()).leftPush(any(), any());
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, synchronizations.size());
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        verify(fixture.listOperations).leftPush("queue", "payload");
    }

    @Test
    void shouldReturnMessageToPendingWhenRedisIsUnavailable() {
        Fixture fixture = fixture();
        when(fixture.listOperations.leftPush("queue", "payload"))
                .thenThrow(new IllegalStateException("redis unavailable"));

        fixture.service.persistAndDispatchAfterCommit(12L, "queue", "payload");

        // 第一次更新用于领取 PENDING 消息，第二次更新用于退回 PENDING 并记录退避时间。
        verify(fixture.mapper, times(2)).update(any(), any());
    }

    @SuppressWarnings("unchecked")
    private Fixture fixture() {
        ExecutionOutboxMapper mapper = mock(ExecutionOutboxMapper.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ListOperations<String, String> listOperations = mock(ListOperations.class);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(mapper.update(any(), any())).thenReturn(1);
        doAnswer(invocation -> {
            ExecutionOutbox item = invocation.getArgument(0);
            item.setId(100L);
            return 1;
        }).when(mapper).insert(any(ExecutionOutbox.class));
        when(mapper.selectById(100L)).thenAnswer(invocation -> {
            ExecutionOutbox item = new ExecutionOutbox();
            item.setId(100L);
            item.setExecutionId(12L);
            item.setQueueKey("queue");
            item.setPayload("payload");
            item.setStatus("PROCESSING");
            item.setAttempts(0);
            return item;
        });
        return new Fixture(new ExecutionOutboxService(mapper, redisTemplate), mapper, listOperations);
    }

    private record Fixture(ExecutionOutboxService service,
                           ExecutionOutboxMapper mapper,
                           ListOperations<String, String> listOperations) {
    }
}
