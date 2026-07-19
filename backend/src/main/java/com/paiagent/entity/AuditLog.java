package com.paiagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("audit_log")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String actor;
    private String action;
    private String resourceType;
    private String resourceId;
    private String result;
    private String detail;
    private String ipAddress;
    private LocalDateTime createdAt;
}
