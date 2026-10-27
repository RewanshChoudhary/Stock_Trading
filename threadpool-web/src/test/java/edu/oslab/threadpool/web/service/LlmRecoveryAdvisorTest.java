package edu.oslab.threadpool.web.service;

import edu.oslab.threadpool.RecoveryContext;
import edu.oslab.threadpool.RecoveryDecision;
import edu.oslab.threadpool.RecoveryOption;
import edu.oslab.threadpool.SimulatedWork;
import edu.oslab.threadpool.TaskSpec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmRecoveryAdvisorTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void validResponseUsesLiveAiAndMalformedResponseFallsBack() {
        RecoveryContext context = context();
        LlmRecoveryAdvisor live = new LlmRecoveryAdvisor(
                mapper, prompt -> "{\"optionId\":\"cache\",\"reason\":\"verified cache\"}", 3);
        RecoveryDecision selected = live.choose(context);
        assertEquals("cache", selected.optionId());
        assertEquals("Live AI", selected.source());
        assertTrue(!selected.fallback());

        LlmRecoveryAdvisor malformed = new LlmRecoveryAdvisor(mapper, prompt -> "not-json", 3);
        RecoveryDecision fallback = malformed.choose(context);
        assertEquals("cache", fallback.optionId());
        assertEquals("Rule-based", fallback.source());
        assertTrue(fallback.fallback());
    }

    @Test
    void parsesGeminiCandidateEnvelope() {
        String geminiResponse = """
                {
                  "candidates": [{
                    "content": {
                      "parts": [{
                        "text": "{\\"optionId\\":\\"cache\\",\\"reason\\":\\"verified cache\\"}"
                      }]
                    }
                  }]
                }
                """;

        RecoveryDecision decision = new LlmRecoveryAdvisor(
                mapper, prompt -> geminiResponse, 1).choose(context());

        assertEquals("cache", decision.optionId());
        assertEquals("Live AI", decision.source());
        assertTrue(!decision.fallback());
    }

    @Test
    void transportFailureUsesTheOfflineAdvisor() {
        RecoveryDecision decision = new LlmRecoveryAdvisor(
                mapper, prompt -> { throw new java.net.http.HttpTimeoutException("timed out"); }, 1)
                .choose(context());
        assertEquals("Rule-based", decision.source());
        assertTrue(decision.fallback());
    }

    private RecoveryContext context() {
        TaskSpec spec = new TaskSpec("cache-attempt", 2, 1, Set.of(), new SimulatedWork(1));
        RecoveryOption cache = new RecoveryOption("cache", "CACHE", 1, 90, 1, spec);
        RecoveryOption retry = new RecoveryOption(
                "retry", "RETRY", 20, 80, 1,
                new TaskSpec("retry-attempt", 2, 2, Set.of(), new SimulatedWork(2)));
        return new RecoveryContext("failed", "simulated failure", 3, 12, 1, List.of(cache, retry), Set.of());
    }
}
