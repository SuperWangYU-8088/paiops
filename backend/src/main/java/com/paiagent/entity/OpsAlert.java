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
@TableName("ops_alert")
public class OpsAlert {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String fingerprint;
    private String alertName;
    private String severity;
    private String status;
    private String source;
    private String summary;
    private String labels;
    private String annotations;
    private LocalDateTime startsAt;
    private LocalDateTime endsAt;
    private Long incidentId;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime receivedAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
