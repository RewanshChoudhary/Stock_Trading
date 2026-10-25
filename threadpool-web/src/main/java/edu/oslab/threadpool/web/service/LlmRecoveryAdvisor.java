package edu.oslab.threadpool.web.service;

import edu.oslab.threadpool.RecoveryAdvisor;
import edu.oslab.threadpool.RecoveryContext;
import edu.oslab.threadpool.RecoveryDecision;
import edu.oslab.threadpool.RecoveryOption;
import edu.oslab.threadpool.RuleBasedRecoveryAdvisor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Minimal provider adapter; all failures deliberately become deterministic fallback decisions. */
public final class LlmRecoveryAdvisor implements RecoveryAdvisor {
    private final ObjectMapper objectMapper;
    private final RuleBasedRecoveryAdvisor fallback = new RuleBasedRecoveryAdvisor();
    private final LlmTransport transport;
    private final int maxCalls;
    private final AtomicInteger calls = new AtomicInteger();
    private final Set<String> requestedTaskIds = ConcurrentHashMap.newKeySet();

    public LlmRecoveryAdvisor(
            ObjectMapper objectMapper, LlmTransport transport, int maxCalls) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.transport = Objects.requireNonNull(transport, "transport");
        if (maxCalls < 1) {
            throw new IllegalArgumentException("maxCalls must be positive");
        }
        this.maxCalls = maxCalls;
    }

    @Override
    public RecoveryDecision choose(RecoveryContext context) {
        if (!requestedTaskIds.add(context.taskId()) || calls.incrementAndGet() > maxCalls) {
            return fallback(context, "LLM call cap reached");
        }
        try {
            String raw = transport.send(promptFor(context));
            JsonNode answer = parseAnswer(raw);
            String optionId = answer.path("optionId").asText("").trim();
            String reason = answer.path("reason").asText("").trim();
            if (reason.isEmpty() || reason.length() > 240 || optionId.isEmpty()
                    || context.options().stream().noneMatch(option -> option.id().equals(optionId))
                    || context.attemptedOptionIds().contains(optionId)) {
                return fallback(context, "LLM response selected an invalid option");
            }
            return new RecoveryDecision(optionId, reason, "Live AI", false);
        } catch (Exception failure) {
            return fallback(context, "Live AI unavailable; deterministic fallback selected");
        }
    }

    private RecoveryDecision fallback(RecoveryContext context, String reason) {
        try {
            RecoveryDecision decision = fallback.choose(context);
            return new RecoveryDecision(decision.optionId(), reason, "Rule-based", true);
        } catch (Exception noOption) {
            throw new IllegalStateException("No valid recovery option is available", noOption);
        }
    }

    private String promptFor(RecoveryContext context) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", context.taskId());
        payload.put("failureReason", context.failureReason());
        payload.put("priority", context.priority());
        payload.put("downstreamWorkMillis", context.downstreamWorkMillis());
        payload.put("workerLoad", context.workerLoad());
        payload.put("attemptedOptionIds", context.attemptedOptionIds());
        payload.put("allowedOptions", context.options().stream().map(this::optionPayload).toList());
        return "Choose one allowed recovery option. Return JSON only with exactly "
                + "{\"optionId\":\"...\",\"reason\":\"short explanation\"}. Context: "
                + objectMapper.writeValueAsString(payload);
    }

    private Map<String, Object> optionPayload(RecoveryOption option) {
        return Map.of(
                "optionId", option.id(),
                "type", option.type(),
                "costMillis", option.costMillis(),
                "quality", option.quality(),
                "maxAttempts", option.maxAttempts());
    }

    private JsonNode parseAnswer(String raw) throws Exception {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Empty LLM response");
        }
        JsonNode root = objectMapper.readTree(raw);
        JsonNode text = root.at("/candidates/0/content/parts/0/text");
        String json = text.isMissingNode() ? raw : text.asText("");
        json = json.trim();
        if (json.startsWith("```")) {
            json = json.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("LLM response was not a JSON object");
        }
        return objectMapper.readTree(json.substring(start, end + 1));
    }
}
