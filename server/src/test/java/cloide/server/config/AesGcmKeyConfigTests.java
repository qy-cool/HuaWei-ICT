package cloide.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

/**
 * 验证 AES-GCM Base64 密钥格式和严格的 256 位长度要求。
 */
class AesGcmKeyConfigTests {

    private static final String VALID_AES_GCM_KEY =
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private final AesGcmKeyConfig aesGcmKeyConfig = new AesGcmKeyConfig();

    /**
     * 验证解码后严格等于 32 字节的配置可以创建 AES 密钥。
     */
    @Test
    void validBase64KeyShouldCreateAesKey() {
        SecretKey secretKey = aesGcmKeyConfig.aesGcmKey(properties(VALID_AES_GCM_KEY));

        assertEquals("AES", secretKey.getAlgorithm());
        assertEquals(32, secretKey.getEncoded().length);
    }

    /**
     * 验证非法 Base64 配置导致密钥初始化失败。
     */
    @Test
    void invalidBase64KeyShouldFail() {
        assertThrows(
                IllegalStateException.class,
                () -> aesGcmKeyConfig.aesGcmKey(properties("not-valid-base64%%%")));
    }

    /**
     * 验证解码后不是 32 字节的 AES 密钥被拒绝。
     */
    @Test
    void keyWithInvalidLengthShouldFail() {
        assertThrows(
                IllegalStateException.class,
                () -> aesGcmKeyConfig.aesGcmKey(properties("c2hvcnQ=")));
    }

    /**
     * 创建包含 AES-GCM 测试密钥的应用配置。
     *
     * @param aesGcmKey Base64 AES-GCM 测试密钥
     * @return 应用配置
     */
    private AppProperties properties(String aesGcmKey) {
        AppProperties properties = new AppProperties();
        properties.getSecurity().setAesGcmKey(aesGcmKey);
        return properties;
    }
}
