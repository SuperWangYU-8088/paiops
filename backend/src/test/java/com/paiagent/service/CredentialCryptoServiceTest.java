package com.paiagent.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CredentialCryptoServiceTest {

    @Test
    void encryptsWithRandomNonceAndDecrypts() {
        CredentialCryptoService service = new CredentialCryptoService(
                "a-test-master-key-with-at-least-32-characters"
        );

        String first = service.encrypt("sensitive-value");
        String second = service.encrypt("sensitive-value");

        // 相同明文使用随机 nonce，密文不应重复，避免数据库泄露相同值模式。
        assertNotEquals(first, second);
        assertEquals("sensitive-value", service.decrypt(first));
        assertEquals("sensitive-value", service.decrypt(second));
    }

    @Test
    void rejectsTamperedCiphertext() {
        CredentialCryptoService service = new CredentialCryptoService(
                "a-test-master-key-with-at-least-32-characters"
        );
        String encrypted = service.encrypt("sensitive-value");
        char replacement = encrypted.charAt(encrypted.length() - 1) == 'A' ? 'B' : 'A';
        String tampered = encrypted.substring(0, encrypted.length() - 1) + replacement;

        assertThrows(IllegalStateException.class, () -> service.decrypt(tampered));
    }

    @Test
    void differentMasterKeyCannotDecrypt() {
        CredentialCryptoService first = new CredentialCryptoService("first-master-key");
        CredentialCryptoService second = new CredentialCryptoService("second-master-key");

        assertThrows(IllegalStateException.class,
                () -> second.decrypt(first.encrypt("sensitive-value")));
    }

    @Test
    void versionedSecretSupportsLegacyPlaintextDuringMigration() {
        CredentialCryptoService service = new CredentialCryptoService("migration-master-key");

        String encrypted = service.encryptSecret("new-secret");
        assertNotEquals("new-secret", encrypted);
        assertEquals("new-secret", service.decryptSecret(encrypted));

        // 历史记录没有 enc:v1 前缀时按明文兼容读取，下一次保存会自动转成密文。
        assertEquals("legacy-secret", service.decryptSecret("legacy-secret"));
    }
}
