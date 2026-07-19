package com.paiagent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.paiagent.entity.LLMGlobalConfig;
import com.paiagent.mapper.LLMGlobalConfigMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.List;

/**
 * 全局模型配置服务，LLM 与 TTS 节点共用。
 */
@Service
public class LLMGlobalConfigService extends ServiceImpl<LLMGlobalConfigMapper, LLMGlobalConfig> {

    private static final String MASKED_SECRET = "******";
    private final CredentialCryptoService credentialCryptoService;

    public LLMGlobalConfigService(CredentialCryptoService credentialCryptoService) {
        this.credentialCryptoService = credentialCryptoService;
    }

    /**
     * 获取某提供商的所有配置
     */
    public List<LLMGlobalConfig> listByProvider(String provider) {
        LambdaQueryWrapper<LLMGlobalConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LLMGlobalConfig::getProvider, provider)
               .orderByDesc(LLMGlobalConfig::getIsDefault)
               .orderByDesc(LLMGlobalConfig::getUpdatedAt);
        return this.list(wrapper).stream().map(this::toSafeConfig).toList();
    }

    /**
     * 提供给管理接口的安全列表。任何 API 都不能把数据库密文或解密后的原文返回浏览器。
     */
    public List<LLMGlobalConfig> listSafeConfigs() {
        return this.list().stream().map(this::toSafeConfig).toList();
    }

    public LLMGlobalConfig getSafeById(Long id) {
        return toSafeConfig(this.getById(id));
    }

    public LLMGlobalConfig getSafeDefaultConfig(String provider) {
        return toSafeConfig(findDefaultConfig(provider));
    }

    /**
     * 仅供节点执行器调用。密钥只在服务端执行阶段短暂解密，禁止把该对象交给控制器。
     */
    public LLMGlobalConfig getRuntimeConfig(Long id) {
        return toRuntimeConfig(this.getById(id));
    }

    /**
     * 获取某提供商的默认配置
     */
    public LLMGlobalConfig getDefaultConfig(String provider) {
        return toRuntimeConfig(findDefaultConfig(provider));
    }

    private LLMGlobalConfig findDefaultConfig(String provider) {
        LambdaQueryWrapper<LLMGlobalConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LLMGlobalConfig::getProvider, provider)
               .eq(LLMGlobalConfig::getIsDefault, 1);
        return this.getOne(wrapper);
    }

    /**
     * 设置默认配置
     * 先清除该提供商的其他默认配置，再设置指定配置为默认
     */
    @Transactional
    public void setDefaultConfig(Long id) {
        LLMGlobalConfig config = this.getById(id);
        if (config == null) {
            throw new RuntimeException("配置不存在");
        }

        // 清除该提供商的其他默认配置
        LambdaUpdateWrapper<LLMGlobalConfig> clearWrapper = new LambdaUpdateWrapper<>();
        clearWrapper.eq(LLMGlobalConfig::getProvider, config.getProvider())
                    .set(LLMGlobalConfig::getIsDefault, 0);
        this.update(clearWrapper);

        // 设置指定配置为默认
        config.setIsDefault(1);
        this.updateById(config);
    }

    /**
     * 保存配置（新增或更新）
     * 如果是第一个配置，自动设置为默认
     */
    @Transactional
    public LLMGlobalConfig saveConfig(LLMGlobalConfig config) {
        normalizeConfig(config);
        preserveOrEncryptApiKey(config);
        validateConfig(config);
        purgeDeletedDuplicate(config);
        ensureUniqueProviderAndConfigName(config);

        // 检查是否是该提供商的第一个配置
        long count = this.countByProvider(config.getProvider());

        if (config.getId() == null) {
            // 新增配置
            if (count == 0) {
                config.setIsDefault(1);
            } else if (config.getIsDefault() == null) {
                config.setIsDefault(0);
            }
            this.save(config);
        } else {
            // 更新配置
            if (config.getIsDefault() != null && config.getIsDefault() == 1) {
                // 如果要设置为默认，先清除其他默认配置
                LambdaUpdateWrapper<LLMGlobalConfig> clearWrapper = new LambdaUpdateWrapper<>();
                clearWrapper.eq(LLMGlobalConfig::getProvider, config.getProvider())
                           .ne(LLMGlobalConfig::getId, config.getId())
                           .set(LLMGlobalConfig::getIsDefault, 0);
                this.update(clearWrapper);
            }
            this.updateById(config);
        }

        return toSafeConfig(config);
    }

    /**
     * 更新时前端只需提交空值即可保留旧密钥；若提交新值，则立即加密后再写库。
     * 同时兼容旧版前端可能回传的六个星号，避免把掩码误存为真实密钥。
     */
    private void preserveOrEncryptApiKey(LLMGlobalConfig config) {
        String submitted = trimToNull(config.getApiKey());
        if (config.getId() != null && (submitted == null || MASKED_SECRET.equals(submitted))) {
            LLMGlobalConfig existing = this.getById(config.getId());
            if (existing == null) {
                throw new IllegalArgumentException("模型配置不存在");
            }
            config.setApiKey(existing.getApiKey());
            return;
        }
        if (submitted != null) {
            config.setApiKey(credentialCryptoService.encryptSecret(submitted));
        }
    }

    private LLMGlobalConfig toSafeConfig(LLMGlobalConfig source) {
        if (source == null) {
            return null;
        }
        LLMGlobalConfig safe = copy(source);
        safe.setApiKeyConfigured(source.getApiKey() != null && !source.getApiKey().isBlank());
        safe.setApiKey(null);
        return safe;
    }

    private LLMGlobalConfig toRuntimeConfig(LLMGlobalConfig source) {
        if (source == null) {
            return null;
        }
        LLMGlobalConfig runtime = copy(source);
        runtime.setApiKey(credentialCryptoService.decryptSecret(source.getApiKey()));
        return runtime;
    }

    private LLMGlobalConfig copy(LLMGlobalConfig source) {
        LLMGlobalConfig target = new LLMGlobalConfig();
        BeanUtils.copyProperties(source, target);
        return target;
    }

    private void purgeDeletedDuplicate(LLMGlobalConfig config) {
        LLMGlobalConfig existing = baseMapper.findAnyByProviderAndConfigName(
                config.getProvider(),
                config.getConfigName()
        );

        if (existing == null) {
            return;
        }

        boolean sameRecord = config.getId() != null && config.getId().equals(existing.getId());
        if (!sameRecord && existing.getDeleted() != null && existing.getDeleted() == 1) {
            baseMapper.hardDeleteById(existing.getId());
        }
    }

    private void normalizeConfig(LLMGlobalConfig config) {
        config.setProvider(canonicalizeProvider(trimToNull(config.getProvider())));
        config.setConfigName(trimToNull(config.getConfigName()));
        config.setApiUrl(trimToNull(config.getApiUrl()));
        config.setApiKey(trimToNull(config.getApiKey()));
        config.setModel(trimToNull(config.getModel()));
        config.setTtsModel(trimToNull(config.getTtsModel()));
        config.setEmbeddingModel(trimToNull(config.getEmbeddingModel()));
        config.setImageModel(trimToNull(config.getImageModel()));
        config.setVideoModel(trimToNull(config.getVideoModel()));
        config.setMemoryEnabled(config.getMemoryEnabled() != null && config.getMemoryEnabled() == 1 ? 1 : 0);
    }

    private void validateConfig(LLMGlobalConfig config) {
        if (config.getProvider() == null) {
            throw new IllegalArgumentException("供应商不能为空");
        }
        if (config.getConfigName() == null) {
            throw new IllegalArgumentException("配置别名不能为空");
        }
        if (config.getApiUrl() == null) {
            throw new IllegalArgumentException("API 地址不能为空");
        }
        if (config.getApiKey() == null) {
            throw new IllegalArgumentException("API 密钥不能为空");
        }
        if (config.getModel() == null) {
            throw new IllegalArgumentException("模型名称不能为空");
        }
    }

    private void ensureUniqueProviderAndConfigName(LLMGlobalConfig config) {
        LambdaQueryWrapper<LLMGlobalConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LLMGlobalConfig::getProvider, config.getProvider())
               .eq(LLMGlobalConfig::getConfigName, config.getConfigName());

        if (config.getId() != null) {
            wrapper.ne(LLMGlobalConfig::getId, config.getId());
        }

        if (this.count(wrapper) > 0) {
            throw new IllegalArgumentException("同一供应商下的配置别名不能重复");
        }
    }

    /**
     * 删除配置
     * 如果删除的是默认配置，自动将下一个配置设为默认
     */
    @Transactional
    public void deleteConfig(Long id) {
        LLMGlobalConfig config = this.getById(id);
        if (config == null) {
            return;
        }

        String provider = config.getProvider();
        boolean wasDefault = config.getIsDefault() == 1;

        baseMapper.hardDeleteById(id);

        // 如果删除的是默认配置，将下一个配置设为默认
        if (wasDefault) {
            List<LLMGlobalConfig> remaining = this.listByProvider(provider);
            if (!remaining.isEmpty()) {
                LLMGlobalConfig newDefault = remaining.get(0);
                newDefault.setIsDefault(1);
                this.updateById(newDefault);
            }
        }
    }

    /**
     * 统计某提供商的配置数量
     */
    private long countByProvider(String provider) {
        LambdaQueryWrapper<LLMGlobalConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LLMGlobalConfig::getProvider, provider);
        return this.count(wrapper);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String canonicalizeProvider(String provider) {
        if (provider == null) {
            return null;
        }

        return switch (provider.toLowerCase(Locale.ROOT)) {
            case "open ai" -> "openai";
            case "deep seek" -> "deepseek";
            case "通义千问" -> "qwen";
            case "stepfun", "阶跃星辰" -> "step";
            case "ai ping" -> "ai_ping";
            case "智谱" -> "zhipu";
            case "agnes", "agnes ai" -> "agnes";
            case "api free", "apifree.ai", "skyclaw", "skyclaw-v1", "skyclaw-v1.0",
                 "skyclaw-v1-lite", "skyclaw-v1.0-lite", "skywork-ai/skyclaw-v1",
                 "skywork-ai/skyclaw-v1-lite" -> "apifree";
            case "volcengine", "ark", "agent_plan", "agent plan", "火山方舟" -> "volcengine_agent_plan";
            default -> provider.toLowerCase(Locale.ROOT);
        };
    }
}
