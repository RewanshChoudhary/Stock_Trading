package edu.oslab.threadpool.web.controller;

import edu.oslab.threadpool.web.dto.CreateRunRequest;
import edu.oslab.threadpool.web.dto.CreateRunResponse;
import edu.oslab.threadpool.web.dto.PresetResponse;
import edu.oslab.threadpool.web.dto.RecoveryCatalogEntry;
import edu.oslab.threadpool.web.dto.RunMetricsResponse;
import edu.oslab.threadpool.web.dto.RunStateResponse;
import edu.oslab.threadpool.web.event.PoolEvent;
import edu.oslab.threadpool.web.service.PresetFactory;
import edu.oslab.threadpool.web.service.RunService;
import edu.oslab.threadpool.web.service.RecoveryCatalog;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/** REST and SSE surface for one simulator run. */
@RestController
@RequestMapping("/api")
public class RunController {

    private final RunService runService;

    public RunController(RunService runService) {
        this.runService = runService;
    }

    @GetMapping("/presets")
    public List<PresetResponse> presets() {
        return PresetFactory.presets();
    }

    @GetMapping("/catalog")
    public List<RecoveryCatalogEntry> catalog() {
        return RecoveryCatalog.entries();
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.CREATED)
    public CreateRunResponse createRun(@RequestBody CreateRunRequest request) {
        return runService.createRun(request);
    }

    @PostMapping("/runs/{runId}/start")
    public RunStateResponse startRun(@PathVariable("runId") String runId) {
        return runService.startRun(runId);
    }

    @GetMapping("/runs/{runId}")
    public RunStateResponse getRun(@PathVariable("runId") String runId) {
        return runService.runState(runId);
    }

    /**
     * Streams the run's events. A client that reconnects with {@code Last-Event-ID} gets every
     * later event replayed before the live stream resumes.
     */
    @GetMapping(path = "/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @PathVariable("runId") String runId,
            @RequestHeader(value = "Last-Event-ID", required = false) Long lastEventId) {
        return runService.subscribe(runId, lastEventId);
    }

    @GetMapping("/runs/{runId}/trace")
    public List<PoolEvent> trace(@PathVariable("runId") String runId) {
        return runService.trace(runId);
    }

    @GetMapping("/runs/{runId}/metrics")
    public RunMetricsResponse metrics(@PathVariable("runId") String runId) {
        return runService.metrics(runId);
    }

    @PostMapping("/runs/{runId}/tasks/{taskId}/fail")
    public RunStateResponse failTask(
            @PathVariable("runId") String runId, @PathVariable("taskId") String taskId) {
        runService.failTask(runId, taskId);
        return runService.runState(runId);
    }

    @PostMapping("/runs/{runId}/cancel")
    public RunStateResponse cancelRun(@PathVariable("runId") String runId) {
        return runService.cancelRun(runId);
    }

    @DeleteMapping("/runs/{runId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRun(@PathVariable("runId") String runId) {
        runService.deleteRun(runId);
    }
}
