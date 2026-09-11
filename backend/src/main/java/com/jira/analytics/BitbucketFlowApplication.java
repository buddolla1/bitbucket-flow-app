package com.jira.analytics;

import com.jira.analytics.config.ApplicationMethodLoggingPostProcessor;
import com.jira.analytics.config.BitbucketProperties;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties(BitbucketProperties.class)
public class BitbucketFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(BitbucketFlowApplication.class, args);
    }

    @Bean
    public static BeanPostProcessor applicationMethodLoggingPostProcessor() {
        return new ApplicationMethodLoggingPostProcessor();
    }
}
