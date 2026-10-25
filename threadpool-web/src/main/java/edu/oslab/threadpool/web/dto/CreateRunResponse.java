package edu.oslab.threadpool.web.dto;

import edu.oslab.threadpool.web.service.RunState;

public record CreateRunResponse(String runId, RunState state, String policy, int taskCount) {
}
