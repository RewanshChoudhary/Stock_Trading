package edu.oslab.threadpool.web.service;

@FunctionalInterface
public interface LlmTransport {
    String send(String prompt) throws Exception;
}
