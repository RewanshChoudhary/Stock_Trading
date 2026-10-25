package edu.oslab.threadpool.web.dto;

import edu.oslab.threadpool.web.service.CompareJob.State;

import java.util.List;

public record CompareResultResponse(
        String jobId,
        State state,
        int completedPolicies,
        int totalPolicies,
        String error,
        List<PolicyComparisonDto> results) {
}
