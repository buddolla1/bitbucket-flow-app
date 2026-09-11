package com.jira.analytics;

import com.jira.analytics.config.BitbucketProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(BitbucketProperties.class)
public class BitbucketFlowApplication {

    private static final Logger log = LoggerFactory.getLogger(BitbucketFlowApplication.class);

    public static void main(String[] args) {
        log.info("Starting Bitbucket Flow application");
        SpringApplication.run(BitbucketFlowApplication.class, args);
    }
}
