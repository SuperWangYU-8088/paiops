package com.paiagent.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("approval_request")
public class ApprovalRequest {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long executionId;
    private Long flowId;
    private String nodeId;
    private String nodeName;
    private String riskLevel;
    private String status;
    private String requestedBy;
    private String reviewedBy;
    private String requestReason;
    private String reviewComment;
    private LocalDateTime requestedAt;
    private LocalDateTime reviewedAt;
    private LocalDateTime expiresAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
