package com.jira.analytics.dto;

import java.time.OffsetDateTime;
import org.springframework.util.StringUtils;

public record BitbucketUserMapping(
        String bitbucketUserId,
        String bitbucketUsername,
        String bitbucketSlug,
        OffsetDateTime lastResolvedAt
) {
    public boolean isValid() {
        return StringUtils.hasText(bitbucketUserId) && (StringUtils.hasText(bitbucketUsername) || StringUtils.hasText(bitbucketSlug));
    }

    public String preferredAuthorFilter() {
        if (StringUtils.hasText(bitbucketUsername)) {
            return bitbucketUsername.trim();
        }
        if (StringUtils.hasText(bitbucketSlug)) {
            return bitbucketSlug.trim();
        }
        return StringUtils.hasText(bitbucketUserId) ? bitbucketUserId.trim() : null;
    }
}
