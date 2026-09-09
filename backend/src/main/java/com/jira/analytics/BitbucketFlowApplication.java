package com.jira.analytics;

import com.jira.analytics.config.BitbucketProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(BitbucketProperties.class)
public class BitbucketFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(BitbucketFlowApplication.class, args);
    }
}

