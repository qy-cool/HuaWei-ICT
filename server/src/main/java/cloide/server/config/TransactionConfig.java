package cloide.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 提供需要细粒度事务边界的业务服务使用的事务模板。
 */
@Configuration
public class TransactionConfig {

    /**
     * 创建共享事务模板，使 Metrics 清理任务可以为每个删除批次单独提交事务。
     *
     * @param transactionManager Spring 管理的数据库事务管理器
     * @return 事务模板
     */
    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
