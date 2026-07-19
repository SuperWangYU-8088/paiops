package com.paiagent.service;

import com.paiagent.config.JwtSecretProvider;
import com.paiagent.entity.AppUser;
import com.paiagent.mapper.AppUserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AuthServiceTest {

    private static final String JWT_SECRET = "test-jwt-secret-key-for-auth-service-123456";
    private static final String DEFAULT_USERNAME = "admin";
    private static final String DEFAULT_PASSWORD = "admin123";

    @Test
    void shouldGenerateAndValidateJwtToken() {
        AuthService authService = createAuthService(new HashMap<>());

        AuthService.AuthTokens tokens = authService.login(DEFAULT_USERNAME, DEFAULT_PASSWORD);

        assertNotNull(tokens);
        assertNotNull(tokens.accessToken());
        assertNotNull(tokens.refreshToken());
        assertTrue(authService.validateToken(tokens.accessToken()));
        assertEquals(DEFAULT_USERNAME, authService.getUsernameByToken(tokens.accessToken()));
    }

    @Test
    void shouldRefreshAcrossServiceInstances() {
        Map<String, String> redisStore = new HashMap<>();
        AuthService issuer = createAuthService(redisStore);
        AuthService validator = createAuthService(redisStore);

        AuthService.AuthTokens issued = issuer.login(DEFAULT_USERNAME, DEFAULT_PASSWORD);
        AuthService.AuthTokens refreshed = validator.refresh(issued.refreshToken());

        assertNotNull(issued);
        assertNotNull(refreshed);
        assertTrue(validator.validateToken(refreshed.accessToken()));
        assertEquals(DEFAULT_USERNAME, validator.getUsernameByToken(refreshed.accessToken()));
        assertNull(validator.refresh(issued.refreshToken()));
    }

    @Test
    void shouldRejectWrongCredentials() {
        AuthService authService = createAuthService(new HashMap<>());

        assertNull(authService.login(DEFAULT_USERNAME, "wrong-password"));
    }

    @Test
    void shouldChangePasswordAndRevokeOldTokens() {
        Map<String, String> redisStore = new HashMap<>();
        AtomicReference<AppUser> userStore = new AtomicReference<>();
        AuthService authService = createAuthService(redisStore, userStore);

        AuthService.AuthTokens oldTokens = authService.login(DEFAULT_USERNAME, DEFAULT_PASSWORD);
        authService.changePassword(DEFAULT_USERNAME, DEFAULT_PASSWORD, "new-password-2026");

        assertFalse(authService.validateToken(oldTokens.accessToken()));
        assertNull(authService.refresh(oldTokens.refreshToken()));
        assertNull(authService.login(DEFAULT_USERNAME, DEFAULT_PASSWORD));
        assertNotNull(authService.login(DEFAULT_USERNAME, "new-password-2026"));
        assertNotNull(userStore.get().getPasswordHash());
        assertFalse(userStore.get().getPasswordHash().contains("new-password-2026"));
    }

    private AuthService createAuthService(Map<String, String> redisStore) {
        return createAuthService(redisStore, new AtomicReference<>());
    }

    private AuthService createAuthService(Map<String, String> redisStore,
                                          AtomicReference<AppUser> userStore) {
        AuthService authService = new AuthService();
        JwtSecretProvider jwtSecretProvider = mock(JwtSecretProvider.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        AppUserMapper appUserMapper = mock(AppUserMapper.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

        when(jwtSecretProvider.getSecret()).thenReturn(JWT_SECRET);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        doAnswer(invocation -> {
            redisStore.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(valueOperations).set(anyString(), anyString(), any(Duration.class));
        when(valueOperations.get(anyString())).thenAnswer(invocation -> redisStore.get(invocation.getArgument(0)));
        when(redisTemplate.delete(anyString())).thenAnswer(invocation -> redisStore.remove(invocation.getArgument(0)) != null);
        when(appUserMapper.selectOne(any())).thenAnswer(invocation -> userStore.get());
        when(appUserMapper.insert(any(AppUser.class))).thenAnswer(invocation -> {
            AppUser user = invocation.getArgument(0);
            user.setId(1L);
            userStore.set(user);
            return 1;
        });
        when(appUserMapper.updateById(any(AppUser.class))).thenAnswer(invocation -> {
            userStore.set(invocation.getArgument(0));
            return 1;
        });

        ReflectionTestUtils.setField(authService, "jwtSecretProvider", jwtSecretProvider);
        ReflectionTestUtils.setField(authService, "accessTokenExpirationMinutes", 120L);
        ReflectionTestUtils.setField(authService, "refreshTokenExpirationHours", 168L);
        ReflectionTestUtils.setField(authService, "defaultUsername", DEFAULT_USERNAME);
        ReflectionTestUtils.setField(authService, "defaultPassword", DEFAULT_PASSWORD);
        ReflectionTestUtils.setField(authService, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(authService, "appUserMapper", appUserMapper);
        ReflectionTestUtils.setField(authService, "passwordHashService", new PasswordHashService());
        return authService;
    }
}
