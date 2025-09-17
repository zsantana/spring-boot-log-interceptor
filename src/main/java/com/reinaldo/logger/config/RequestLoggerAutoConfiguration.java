package com.reinaldo.logger.config;

import com.reinaldo.logger.filter.RequestLoggingFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RequestLoggerProperties.class)
public class RequestLoggerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "request-logger", name = "enabled", havingValue = "true", matchIfMissing = true)
    public RequestLoggingFilter requestLoggingFilter(RequestLoggerProperties properties) {
        return new RequestLoggingFilter(properties);
    }
}
