package com.jira.analytics.service;

import com.jira.analytics.config.BitbucketProperties;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.util.StringUtils;

final class BitbucketAuthSupport {

    private BitbucketAuthSupport() {
    }

    static String basicAuthHeader(BitbucketProperties properties) {
        if (properties == null) {
            return null;
        }
        if (!StringUtils.hasText(properties.getUsername()) || !StringUtils.hasText(properties.getApiToken())) {
            return null;
        }
        String credentials = properties.getUsername().trim() + ":" + properties.getApiToken();
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }
}
