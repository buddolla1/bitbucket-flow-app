package com.jira.analytics.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class FrontendRouteController {

    private static final Logger log = LoggerFactory.getLogger(FrontendRouteController.class);

    @GetMapping({
            "/",
            "/sync",
            "/bitbucket/sync"
    })
    public String index() {
        log.info("Forwarding frontend route to index.html");
        return "forward:/index.html";
    }
}
