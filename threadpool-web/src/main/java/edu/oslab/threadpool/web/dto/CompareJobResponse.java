package edu.oslab.threadpool.web.dto;

import edu.oslab.threadpool.web.service.CompareJob.State;

public record CompareJobResponse(String jobId, State state, int totalPolicies, int repetitions) {
}
