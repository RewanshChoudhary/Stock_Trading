package edu.oslab.threadpool.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for the REST and SSE layer that exposes the dependency-aware thread pool.
 *
 * <p>The pool itself lives in {@code threadpool-core} and never imports Spring types; this
 * module only wraps it.
 */
@SpringBootApplication(proxyBeanMethods = false)
@ConfigurationPropertiesScan
public class WebApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebApplication.class, args);
    }
}
