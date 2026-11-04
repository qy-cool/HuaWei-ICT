package cloide.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * 验证无 Web 前端时的 CORS 行为：空白名单合法、不启用凭据；配置 Origin 后正常启用。
 */
class CorsConfigTests {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    /** 默认配置应为空白名单，表示当前没有浏览器端。 */
    @Test
    void defaultOriginsShouldBeEmpty() {
        AppProperties.Cors cors = new AppProperties.Cors();
        assertTrue(cors.getAllowedOrigins().isEmpty());
    }

    /** 空白名单不再阻止应用启动。 */
    @Test
    void emptyOriginsShouldPassValidation() {
        AppProperties.Cors cors = new AppProperties.Cors();
        cors.setAllowedOrigins(List.of());
        Set<ConstraintViolation<AppProperties.Cors>> violations = validator.validate(cors);
        assertTrue(violations.isEmpty());
    }

    /** 显式配置 Origin 时，CorsConfiguration 应保留白名单并允许凭据。 */
    @Test
    void corsConfigurationShouldReflectConfiguredOrigin() {
        AppProperties.Cors corsProps = new AppProperties.Cors();
        corsProps.setAllowedOrigins(List.of("https://example.test"));

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(corsProps.getAllowedOrigins());
        configuration.setAllowedMethods(corsProps.getAllowedMethods());
        configuration.setAllowedHeaders(corsProps.getAllowedHeaders());
        configuration.setAllowCredentials(!corsProps.getAllowedOrigins().isEmpty());
        configuration.setMaxAge(corsProps.getMaxAgeSeconds());

        assertNotNull(configuration.getAllowedOrigins());
        assertTrue(configuration.getAllowedOrigins().contains("https://example.test"));
        assertTrue(configuration.getAllowedMethods().contains("GET"));
        assertTrue(configuration.getAllowedMethods().contains("POST"));
        assertTrue(configuration.getAllowedMethods().contains("OPTIONS"));
        assertTrue(configuration.getAllowedHeaders().contains("Authorization"));
        assertTrue(configuration.getAllowedHeaders().contains("Content-Type"));
        assertTrue(configuration.getAllowCredentials());
        assertEquals(3600, configuration.getMaxAge());
    }

    /** 空白名单下不应接受跨域，也不应允许凭据。 */
    @Test
    void emptyOriginsShouldDisableCredentials() {
        AppProperties properties = new AppProperties();
        CorsConfigurationSource source = new CorsConfig().corsConfigurationSource(properties);
        CorsConfiguration configuration =
                source.getCorsConfiguration(new MockHttpServletRequest("GET", "/api/health"));

        assertNotNull(configuration);
        assertTrue(configuration.getAllowedOrigins().isEmpty());
        assertFalse(configuration.getAllowCredentials());
    }

    /** 白名单不应包含通配符 "*"，防止意外开放所有来源。 */
    @Test
    void allowedOriginsShouldNotContainWildcard() {
        AppProperties.Cors cors = new AppProperties.Cors();
        assertFalse(cors.getAllowedOrigins().contains("*"));
    }
}
