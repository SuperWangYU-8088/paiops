package com.paiagent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.paiagent.config.JwtSecretProvider;
import com.paiagent.entity.AppUser;
import com.paiagent.mapper.AppUserMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

/**
 * 认证与口令管理服务。
 *
 * <p>环境变量中的管理员口令只用于首次引导。第一次成功登录会在 {@code app_user}
 * 中创建 PBKDF2 哈希；之后登录和修改密码均以数据库为准，环境变量中的旧口令不再生效。</p>
 */
@Service
public class AuthService {

    @Value("${paiagent.default-username:}")
    private String defaultUsername;

    @Value("${paiagent.default-password:}")
    private String defaultPassword;

    private static final String ACCESS_TOKEN_TYPE = "access";
    private static final String DEFAULT_ROLE = "ADMIN";
    private static final String REFRESH_TOKEN_PREFIX = "auth:refresh:";
    private static final String SSE_TICKET_PREFIX = "auth:sse-ticket:";

    @Autowired
    private JwtSecretProvider jwtSecretProvider;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private AppUserMapper appUserMapper;

    @Autowired
    private PasswordHashService passwordHashService;

    @Value("${paiagent.auth.access-token-expiration-minutes:120}")
    private long accessTokenExpirationMinutes;

    @Value("${paiagent.auth.refresh-token-expiration-hours:168}")
    private long refreshTokenExpirationHours;

    /** 校验数据库口令；数据库用户不存在时才允许用部署口令完成首次引导。 */
    public AuthTokens login(String username, String password) {
        AppUser user = findUser(username);
        if (user != null) {
            return Boolean.TRUE.equals(user.getEnabled())
                    && passwordHashService.matches(password, user.getPasswordHash())
                    ? issueTokens(user.getUsername(), normalizedRole(user), tokenVersion(user))
                    : null;
        }

        if (!matchesBootstrapCredential(username, password)) {
            return null;
        }
        AppUser bootstrapped = createBootstrapUser(username, password);
        return issueTokens(bootstrapped.getUsername(), normalizedRole(bootstrapped), tokenVersion(bootstrapped));
    }

    /**
     * 修改当前用户密码并递增令牌版本。
     *
     * <p>版本递增后，所有旧 access token 和 refresh token 都会失效，调用方应退出并重新登录。</p>
     */
    public void changePassword(String username, String currentPassword, String newPassword) {
        validateNewPassword(username, currentPassword, newPassword);
        AppUser user = findUser(username);
        if (user == null) {
            if (!matchesBootstrapCredential(username, currentPassword)) {
                throw new IllegalArgumentException("当前密码不正确");
            }
            user = createBootstrapUser(username, currentPassword);
        }
        if (!Boolean.TRUE.equals(user.getEnabled())
                || !passwordHashService.matches(currentPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("当前密码不正确");
        }

        user.setPasswordHash(passwordHashService.hash(newPassword));
        user.setTokenVersion(tokenVersion(user) + 1);
        user.setPasswordChangedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        appUserMapper.updateById(user);
    }

    public AuthTokens refresh(String refreshToken) {
        RefreshSession session = getRefreshSession(refreshToken);
        if (session == null || !isCurrentSession(session)) {
            revokeRefreshToken(refreshToken);
            return null;
        }
        revokeRefreshToken(refreshToken);
        AppUser user = findUser(session.username());
        String role = user == null ? DEFAULT_ROLE : normalizedRole(user);
        return issueTokens(session.username(), role, session.tokenVersion());
    }

    public void logout(String refreshToken) {
        revokeRefreshToken(refreshToken);
    }

    /** 验证签名、类型、有效期和数据库令牌版本。 */
    public boolean validateToken(String token) {
        try {
            Claims claims = parseClaims(token);
            if (!ACCESS_TOKEN_TYPE.equals(claims.get("tokenType"))
                    || claims.getExpiration() == null
                    || !claims.getExpiration().after(new Date())) {
                return false;
            }
            return isCurrentSession(new RefreshSession(
                    claims.getSubject(), claimTokenVersion(claims)));
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public String getUsernameByToken(String token) {
        if (!validateToken(token)) {
            return null;
        }
        return parseClaims(token).getSubject();
    }

    public String getRoleByToken(String token) {
        if (!validateToken(token)) {
            return null;
        }
        return parseClaims(token).get("role", String.class);
    }

    /** 签发 60 秒一次性 SSE 票据，避免长效 JWT 出现在 URL 和代理日志。 */
    public String issueSseTicket(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("用户身份不能为空");
        }
        String ticket = UUID.randomUUID().toString().replace("-", "");
        stringRedisTemplate.opsForValue().set(
                SSE_TICKET_PREFIX + ticket,
                username,
                Duration.ofSeconds(60)
        );
        return ticket;
    }

    /** 读取后立即删除，确保票据无法重放到另一个任务流连接。 */
    public String consumeSseTicket(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return null;
        }
        return stringRedisTemplate.opsForValue().getAndDelete(SSE_TICKET_PREFIX + ticket);
    }

    public void revokeRefreshToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        stringRedisTemplate.delete(buildRefreshTokenKey(refreshToken));
    }

    private AuthTokens issueTokens(String username, String role, int version) {
        String accessToken = createAccessToken(username, role, version);
        String refreshToken = UUID.randomUUID().toString().replace("-", "");
        stringRedisTemplate.opsForValue().set(
                buildRefreshTokenKey(refreshToken),
                username + "|" + version,
                Duration.ofHours(refreshTokenExpirationHours)
        );
        return new AuthTokens(accessToken, refreshToken, username);
    }

    private String createAccessToken(String username, String role, int version) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(accessTokenExpirationMinutes, ChronoUnit.MINUTES);
        return Jwts.builder()
                .subject(username)
                .claim("tokenType", ACCESS_TOKEN_TYPE)
                .claim("role", role)
                .claim("tokenVersion", version)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(getSigningKey())
                .compact();
    }

    private AppUser createBootstrapUser(String username, String password) {
        AppUser user = new AppUser();
        user.setUsername(username.trim());
        user.setPasswordHash(passwordHashService.hash(password));
        user.setRole(DEFAULT_ROLE);
        user.setEnabled(true);
        user.setTokenVersion(0);
        user.setPasswordChangedAt(LocalDateTime.now());
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        try {
            appUserMapper.insert(user);
            return user;
        } catch (DuplicateKeyException race) {
            AppUser existing = findUser(username);
            if (existing != null && passwordHashService.matches(password, existing.getPasswordHash())) {
                return existing;
            }
            throw new IllegalStateException("管理员账户初始化冲突，请重试", race);
        }
    }

    private AppUser findUser(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        return appUserMapper.selectOne(new LambdaQueryWrapper<AppUser>()
                .eq(AppUser::getUsername, username.trim())
                .last("LIMIT 1"));
    }

    private boolean matchesBootstrapCredential(String username, String password) {
        if (username == null || password == null
                || defaultUsername == null || defaultPassword == null
                || defaultUsername.isBlank() || defaultPassword.isBlank()
                || !defaultUsername.equals(username)) {
            return false;
        }
        return MessageDigest.isEqual(
                defaultPassword.getBytes(StandardCharsets.UTF_8),
                password.getBytes(StandardCharsets.UTF_8));
    }

    private void validateNewPassword(String username, String currentPassword, String newPassword) {
        if (newPassword == null || newPassword.length() < 8 || newPassword.length() > 128) {
            throw new IllegalArgumentException("新密码长度必须为 8～128 个字符");
        }
        if (!newPassword.equals(newPassword.trim())) {
            throw new IllegalArgumentException("新密码首尾不能包含空格");
        }
        if (newPassword.equals(currentPassword)) {
            throw new IllegalArgumentException("新密码不能与当前密码相同");
        }
        if (username != null && newPassword.equalsIgnoreCase(username)) {
            throw new IllegalArgumentException("新密码不能与用户名相同");
        }
        int categories = 0;
        if (newPassword.chars().anyMatch(Character::isLetter)) categories++;
        if (newPassword.chars().anyMatch(Character::isDigit)) categories++;
        if (newPassword.chars().anyMatch(ch -> !Character.isLetterOrDigit(ch) && !Character.isWhitespace(ch))) categories++;
        if (newPassword.chars().anyMatch(Character::isWhitespace)) categories++;
        if (categories < 2) {
            throw new IllegalArgumentException("新密码至少包含字母、数字、符号或空格中的两类");
        }
    }

    private boolean isCurrentSession(RefreshSession session) {
        if (session == null || session.username() == null) {
            return false;
        }
        AppUser user = findUser(session.username());
        if (user != null) {
            return Boolean.TRUE.equals(user.getEnabled())
                    && tokenVersion(user) == session.tokenVersion();
        }
        // 兼容升级前已签发但尚未完成数据库账户引导的版本 0 令牌。
        return session.tokenVersion() == 0 && defaultUsername != null
                && defaultUsername.equals(session.username());
    }

    private RefreshSession getRefreshSession(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return null;
        }
        String value = stringRedisTemplate.opsForValue().get(buildRefreshTokenKey(refreshToken));
        if (value == null || value.isBlank()) {
            return null;
        }
        int separator = value.lastIndexOf('|');
        if (separator < 1) {
            return new RefreshSession(value, 0);
        }
        try {
            return new RefreshSession(value.substring(0, separator),
                    Integer.parseInt(value.substring(separator + 1)));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private int claimTokenVersion(Claims claims) {
        Object value = claims.get("tokenVersion");
        return value instanceof Number number ? number.intValue() : 0;
    }

    private int tokenVersion(AppUser user) {
        return user.getTokenVersion() == null ? 0 : user.getTokenVersion();
    }

    private String normalizedRole(AppUser user) {
        return user.getRole() == null || user.getRole().isBlank()
                ? DEFAULT_ROLE : user.getRole().trim().toUpperCase();
    }

    private String buildRefreshTokenKey(String refreshToken) {
        return REFRESH_TOKEN_PREFIX + refreshToken;
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(jwtSecretProvider.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    private record RefreshSession(String username, int tokenVersion) {
    }

    public record AuthTokens(String accessToken, String refreshToken, String username) {
    }
}
