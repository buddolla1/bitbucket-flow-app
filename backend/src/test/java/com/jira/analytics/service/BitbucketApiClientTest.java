package com.jira.analytics.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jira.analytics.config.BitbucketProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BitbucketApiClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void getUserPullRequestsFollowsAwesomeGraphsPagination() throws Exception {
        List<String> requests = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestURI().toString());
            String query = exchange.getRequestURI().getRawQuery();
            String body = query != null && query.contains("start=2")
                    ? """
                    {"isLastPage":true,"values":[{"id":2}]}
                    """
                    : """
                    {"isLastPage":false,"nextPageStart":2,"values":[{"id":1}]}
                    """;
            byte[] response = body.getBytes();
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        BitbucketProperties properties = new BitbucketProperties();
        properties.setBaseUrl("http://localhost:" + server.getAddress().getPort());
        properties.setPageSize(2);
        BitbucketApiClient client = new BitbucketApiClient(properties, new ObjectMapper());

        List<JsonNode> prs = client.getUserPullRequests("alice");

        assertEquals(2, prs.size());
        assertEquals(1L, prs.get(0).path("id").asLong());
        assertEquals(2L, prs.get(1).path("id").asLong());
        assertEquals("/rest/awesome-graphs-api/latest/users/alice/pull-requests?state=ALL&limit=2", requests.get(0));
        assertEquals("/rest/awesome-graphs-api/latest/users/alice/pull-requests?state=ALL&limit=2&start=2", requests.get(1));
    }
}
