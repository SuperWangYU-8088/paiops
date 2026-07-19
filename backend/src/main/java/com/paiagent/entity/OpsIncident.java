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
@TableName("ops_incident")
public class OpsIncident {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String severity;
    private String status;
    private String summary;
    private String assignee;
    private Integer alertCount;
    private Long runbookId;
    private Long executionId;
    private String rootCause;
    private String resolution;
    private String postmortem;
    private LocalDateTime startedAt;
    private LocalDateTime resolvedAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
