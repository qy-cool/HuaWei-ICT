package cloide.server.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * 验证 JWT Base64 密钥格式和 HS256 最低强度要求。
 */
class JwtKeyConfigTests {

    private static final String VALID_TEST_SECRET =
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private final JwtKeyConfig jwtKeyConfig = new JwtKeyConfig();

    /**
     * 验证解码后达到 32 字节的 Base64 密钥可以创建签名密钥。
     */
    @Test
    void validBase64SecretShouldCreateSigningKey() {
        assertDoesNotThrow(() -> jwtKeyConfig.jwtSigningKey(properties(VALID_TEST_SECRET)));
    }

    /**
     * 验证非法 Base64 密钥导致配置初始化失败。
     */
    @Test
    void invalidBase64SecretShouldFail() {
        assertThrows(
                IllegalStateException.class,
                () -> jwtKeyConfig.jwtSigningKey(properties("not-valid-base64%%%")));
    }

    /**
     * 验证解码后少于 32 字节的密钥被拒绝。
     */
    @Test
    void weakSecretShouldFail() {
        assertThrows(
                IllegalStateException.class,
                () -> jwtKeyConfig.jwtSigningKey(properties("c2hvcnQ=")));
    }

    /**
     * 创建测试所需的应用配置。
     *
     * @param secret JWT 测试密钥
     * @return 应用配置
     */
    private AppProperties properties(String secret) {
        AppProperties properties = new AppProperties();
        properties.getJwt().setSecret(secret);
        properties.getJwt().setExpireHours(24);
        return properties;
    }
}
