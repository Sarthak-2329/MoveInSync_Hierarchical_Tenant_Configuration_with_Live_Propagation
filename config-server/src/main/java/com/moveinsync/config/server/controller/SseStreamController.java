package com.moveinsync.config.server.controller;

import com.moveinsync.config.server.service.ConfigEngineService;
import com.moveinsync.config.server.service.SseBroadcaster;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Server-Sent Events (SSE) streaming endpoint.
 *
 * Connected client libraries receive instant notifications whenever
 * a configuration change is committed.
 */
@RestController
@RequestMapping("/api/config")
public class SseStreamController {

    private final SseBroadcaster sseBroadcaster;
    private final ConfigEngineService configEngineService;

    public SseStreamController(SseBroadcaster sseBroadcaster, ConfigEngineService configEngineService) {
        this.sseBroadcaster = sseBroadcaster;
        this.configEngineService = configEngineService;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId
    ) {
        long currentVersion = configEngineService.getCurrentVersion();
        return sseBroadcaster.subscribe(lastEventId, currentVersion);
    }
}
