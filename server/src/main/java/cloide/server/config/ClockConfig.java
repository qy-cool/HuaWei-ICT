package cloide.server.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 提供统一 UTC 时间源，使超时边界可在测试中替换为可控 Clock。 */
@Configuration
public class ClockConfig {

    /** 生产环境使用系统 UTC Clock，避免依赖主机默认时区。 */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
