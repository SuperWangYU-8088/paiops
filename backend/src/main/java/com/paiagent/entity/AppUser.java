package com.paiagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 应用用户。
 *
 * <p>数据库只保存不可逆口令哈希，不保存用户输入的明文口令。当前版本只有管理员账号，
 * 但表结构保留角色、启用状态和令牌版本，便于后续接入多用户或统一身份认证。</p>
 */
@Data
@TableName("app_user")
public class AppUser {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String passwordHash;
    private String role;
    private Boolean enabled;
    private Integer tokenVersion;
    private LocalDateTime passwordChangedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
