package edu.oslab.threadpool.web.service;

import edu.oslab.threadpool.RecoveryAdvisor;
import edu.oslab.threadpool.RecoveryContext;
import edu.oslab.threadpool.RecoveryDecision;
import edu.oslab.threadpool.RecoveryOption;
import edu.oslab.threadpool.RuleBasedRecoveryAdvisor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LLM-backed recovery advisor that calls the configured transport for each unique failing task.
 * The prompt explicitly enumerates the exact optionId strings the model must echo back, removing
 * ambiguity between the human-readable {@code type} field (e.g. {@code "CACHE"}) and the
 * machine-readable {@code id} (e.g. {@code "cached-result"}). A second-chance resolver performs
 * case-insensitive and common-alias matching before falling back to the rule-based advisor so
 * that minor LLM hallucinations (e.g. {@code "RETRY"} instead of {@code "retry"}) are recovered
 * without a full fallback.
 */
public final class LlmRecoveryAdvisor implements RecoveryAdvisor {
    private static final Logger LOGGER = LoggerFactory.getLogger(LlmRecoveryAdvisor.class);
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
            LOGGER.debug("LLM skip for task {} (already requested or cap reached)", context.taskId());
            return fallback(context, "LLM call cap reached");
        }
        try {
            String raw = transport.send(promptFor(context));
            LOGGER.debug("LLM raw response for task {}: {}", context.taskId(), raw);
            JsonNode answer = parseAnswer(raw);
            String rawOptionId = answer.path("optionId").asText("").trim();
            String reason = answer.path("reason").asText("").trim();

            // First: exact match (the happy path).
            String optionId = resolveOptionId(rawOptionId, context);

            LOGGER.debug("LLM response for task {}: rawOptionId={} resolved={} reason={}",
                    context.taskId(), rawOptionId, optionId, reason);

            if (reason.isEmpty() || reason.length() > 240 || optionId == null
                    || context.attemptedOptionIds().contains(optionId)) {
                LOGGER.warn("LLM gave invalid response for task {}: rawOptionId='{}' resolved={}",
                        context.taskId(), rawOptionId, optionId);
                return fallback(context, "LLM response selected an invalid option");
            }
            return new RecoveryDecision(optionId, reason, "Live AI", false);
        } catch (Exception failure) {
            LOGGER.warn("LLM call failed for task {}: {}", context.taskId(), failure.getMessage());
            return fallback(context, "Live AI unavailable; deterministic fallback selected");
        }
    }

    /**
     * Resolves the raw string returned by the LLM to an exact option ID available in the context.
     * Tries, in order:
     * <ol>
     *   <li>Exact match against {@code option.id()}</li>
     *   <li>Case-insensitive match against {@code option.id()}</li>
     *   <li>Case-insensitive match against {@code option.type()} (LLM may echo the type field)</li>
     *   <li>Common alias resolution ({@code "cache"} → {@code "cached-result"}, etc.)</li>
     * </ol>
     *
     * @return the canonical option ID, or {@code null} if no valid match is found.
     */
    private String resolveOptionId(String raw, RecoveryContext context) {
        if (raw == null || raw.isEmpty()) return null;
        List<RecoveryOption> options = context.options();

        // 1. Exact match.
        for (RecoveryOption o : options) {
            if (o.id().equals(raw)) return o.id();
        }

        // 2. Case-insensitive id match.
        String lower = raw.toLowerCase(java.util.Locale.ROOT);
        for (RecoveryOption o : options) {
            if (o.id().equalsIgnoreCase(raw)) return o.id();
        }

        // 3. Case-insensitive type match (LLM may return "CACHE", "RETRY", etc.).
        for (RecoveryOption o : options) {
            if (o.type().equalsIgnoreCase(raw)) return o.id();
        }

        // 4. Common alias table for known LLM confusions.
        String canonical = switch (lower) {
            case "cache", "cached", "use-cache", "use_cache", "cache-result" -> "cached-result";
            case "retry", "re-try", "re_try" -> "retry";
            case "substitute", "sub", "alt", "alternative" -> "substitute";
            case "skip", "skip-optional", "skip_optional", "optional-skip" -> "skip-optional";
            default -> null;
        };
        if (canonical != null) {
            final String c = canonical;
            boolean available = options.stream().anyMatch(o -> o.id().equals(c));
            if (available) return canonical;
        }

        return null;
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
        // Build the valid-IDs list first so we can embed it verbatim in the instruction.
        List<String> validIds = context.options().stream()
                .filter(o -> !context.attemptedOptionIds().contains(o.id()))
                .map(RecoveryOption::id)
                .toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", context.taskId());
        payload.put("failureReason", context.failureReason());
        payload.put("priority", context.priority());
        payload.put("downstreamWorkMillis", context.downstreamWorkMillis());
        payload.put("workerLoad", context.workerLoad());
        payload.put("attemptedOptionIds", context.attemptedOptionIds());
        // Only expose id, costMillis, quality to avoid the model echoing the 'type' field.
        payload.put("allowedOptions", context.options().stream().map(this::optionPayload).toList());

        return "You are a recovery advisor for a task dependency scheduler. "
                + "A task has failed and you must choose exactly ONE recovery option. "
                + "IMPORTANT: the \"optionId\" field in your response MUST be copied EXACTLY "
                + "(character-for-character, case-sensitive) from this list of valid IDs: "
                + validIds + ". "
                + "Do NOT paraphrase, uppercase, or invent an ID — use one from the list verbatim. "
                + "Respond with a single JSON object and nothing else: "
                + "{\"optionId\": \"<exact id from list>\", \"reason\": \"<max 200 chars why>\"}. "
                + "Context: " + objectMapper.writeValueAsString(payload);
    }

    private Map<String, Object> optionPayload(RecoveryOption option) {
        // Intentionally omit 'type' to avoid the model confusing type with optionId.
        return Map.of(
                "optionId", option.id(),
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
