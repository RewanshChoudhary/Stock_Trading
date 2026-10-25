package edu.oslab.threadpool.web.dto;

import java.util.List;

public record ErrorResponse(String code, String message, List<String> details) {

    public ErrorResponse(String code, String message) {
        this(code, message, List.of());
    }
}
