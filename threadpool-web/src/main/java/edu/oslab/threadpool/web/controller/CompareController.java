package edu.oslab.threadpool.web.controller;

import edu.oslab.threadpool.web.dto.CompareJobResponse;
import edu.oslab.threadpool.web.dto.CompareRequest;
import edu.oslab.threadpool.web.dto.CompareResultResponse;
import edu.oslab.threadpool.web.service.CompareService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Headless benchmark endpoint: the same workload under every policy, with no live event stream and
 * no interaction once it starts.
 */
@RestController
@RequestMapping("/api/compare")
public class CompareController {

    private final CompareService compareService;

    public CompareController(CompareService compareService) {
        this.compareService = compareService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CompareJobResponse compare(@RequestBody CompareRequest request) {
        return compareService.start(request);
    }

    @GetMapping("/{jobId}")
    public CompareResultResponse result(@PathVariable("jobId") String jobId) {
        return compareService.result(jobId);
    }
}
