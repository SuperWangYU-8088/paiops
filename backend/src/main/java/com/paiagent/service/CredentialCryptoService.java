package com.paiagent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Slf4j
@Service
public class CredentialCryptoService {

    /**
     * 密文版本前缀。显式前缀可以可靠地区分历史明文和新密文，避免把普通
     * Base64 字符串误当成密文，也为后续轮换算法或接入 KMS 预留版本空间。
     */
    private static final String CIPHERTEXT_PREFIX = "enc:v1:";
    private static final int NONCE_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    private final SecureRandom secureRandom = new SecureRandom();
    private final SecretKeySpec key;

    public CredentialCryptoService(@Value("${paiops.security.master-key:}") String configuredKey) {
        byte[] keyBytes;
        if (configuredKey == null || configuredKey.isBlank()) {
            keyBytes = new byte[32];
            secureRandom.nextBytes(keyBytes);
            log.warn("PAIOPS_MASTER_KEY 未配置，当前使用临时凭证加密密钥；重启后无法解密已保存凭证");
        } else {
            keyBytes = deriveKey(configuredKey.trim());
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * 使用随机 96 位 nonce 的 AES-256-GCM 加密。
     * GCM 同时提供保密性和完整性校验，数据库内容被篡改时解密会直接失败。
     */
    public String encrypt(String plaintext) {
        try {
            byte[] nonce = new byte[NONCE_LENGTH];
            secureRandom.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(nonce.length + encrypted.length)
                            .put(nonce)
                            .put(encrypted)
                            .array()
            );
        } catch (Exception exception) {
            throw new IllegalStateException("凭证加密失败", exception);
        }
    }

    public String decrypt(String encryptedPayload) {
        try {
            byte[] payload = Base64.getDecoder().decode(encryptedPayload);
            if (payload.length <= NONCE_LENGTH) {
                throw new IllegalArgumentException("加密凭证格式无效");
            }
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            byte[] nonce = new byte[NONCE_LENGTH];
            buffer.get(nonce);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("凭证解密失败，请检查 PAIOPS_MASTER_KEY", exception);
        }
    }

    /**
     * 加密业务密钥并添加版本前缀。
     *
     * <p>调用方写库时应优先使用此方法，而不是直接调用 {@link #encrypt(String)}。</p>
     */
    public String encryptSecret(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        if (isEncryptedSecret(plaintext)) {
            return plaintext;
        }
        return CIPHERTEXT_PREFIX + encrypt(plaintext);
    }

    /**
     * 解密带版本前缀的业务密钥；没有前缀的数据视为历史明文。
     * 该兼容逻辑使升级不需要停机迁移，记录会在下一次保存时自动转为密文。
     */
    public String decryptSecret(String storedValue) {
        if (storedValue == null) {
            return null;
        }
        if (!isEncryptedSecret(storedValue)) {
            return storedValue;
        }
        return decrypt(storedValue.substring(CIPHERTEXT_PREFIX.length()));
    }

    public boolean isEncryptedSecret(String value) {
        return value != null && value.startsWith(CIPHERTEXT_PREFIX);
    }

    /**
     * 部署密钥既可传 32 字节 Base64，也可传普通高强度字符串。
     * 普通字符串统一经 SHA-256 派生为固定长度 AES 密钥，原文不写入数据库。
     */
    private byte[] deriveKey(String configuredKey) {
        try {
            byte[] decoded = Base64.getDecoder().decode(configuredKey);
            if (decoded.length == 32) {
                return decoded;
            }
        } catch (IllegalArgumentException ignored) {
            // 普通字符串密钥会在下面经 SHA-256 派生。
        }
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(configuredKey.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("无法初始化凭证加密密钥", exception);
        }
    }
}
