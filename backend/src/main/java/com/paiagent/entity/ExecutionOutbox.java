package com.paiagent.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据库到 Redis 的持久化任务发件箱。
 *
 * <p>业务事务只负责写入本表，提交成功后再投递 Redis；即使 Redis 暂时不可用，
 * 待发送记录仍会保留并由定时调度器重试，避免“数据库有任务、队列没消息”。</p>
 */
@Data
@TableName("execution_outbox")
public class ExecutionOutbox {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long executionId;
    private String queueKey;
    private String payload;
    private String status;
    private Integer attempts;
    private LocalDateTime nextAttemptAt;
    private String lastError;
    private LocalDateTime sentAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
