package com.kerosene.kfe.liquidity.adapters.in.http;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.kerosene.common.dto.ApiResponse;
import com.kerosene.kfe.adapters.in.http.dto.liquidity.KfeChannelDecisionResponse;
import com.kerosene.kfe.adapters.in.http.dto.liquidity.KfeChannelSnapshotResponse;
import com.kerosene.kfe.adapters.in.http.dto.liquidity.KfeCloseChannelRequest;
import com.kerosene.kfe.adapters.in.http.dto.liquidity.KfeOpenChannelRequest;
import com.kerosene.kfe.adapters.in.http.dto.liquidity.KfePpmAdjustRequest;
import com.kerosene.kfe.adapters.in.http.dto.liquidity.KfeRebalanceChannelRequest;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeChannelCapacityJobEntity;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeChannelRebalanceJobEntity;
import com.kerosene.kfe.liquidity.adapters.out.observability.KfeCapacitySignalStore;
import com.kerosene.kfe.liquidity.adapters.in.scheduling.KfeChannelCapacityController;
import com.kerosene.kfe.liquidity.adapters.out.persistence.KfeChannelCapacityQueueService;
import com.kerosene.kfe.liquidity.adapters.in.scheduling.KfeChannelCapacityWorker;
import com.kerosene.kfe.liquidity.adapters.in.compatibility.KfeChannelLifecycleService;
import com.kerosene.kfe.liquidity.adapters.in.scheduling.KfeChannelRebalanceWorker;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/admin/kfe/channels")
public class KfeChannelAdminController {

    private final KfeChannelLifecycleService channelLifecycleService;
    private final KfeChannelRebalanceWorker rebalanceWorker;
    private final KfeChannelCapacityQueueService capacityQueue;
    private final KfeChannelCapacityWorker capacityWorker;
    private final KfeChannelCapacityController capacityController;
    private final KfeCapacitySignalStore capacitySignals;

    public KfeChannelAdminController(
            KfeChannelLifecycleService channelLifecycleService,
            KfeChannelRebalanceWorker rebalanceWorker,
            KfeChannelCapacityQueueService capacityQueue,
            KfeChannelCapacityWorker capacityWorker,
            KfeChannelCapacityController capacityController,
            KfeCapacitySignalStore capacitySignals) {
        this.channelLifecycleService = channelLifecycleService;
        this.rebalanceWorker = rebalanceWorker;
        this.capacityQueue = capacityQueue;
        this.capacityWorker = capacityWorker;
        this.capacityController = capacityController;
        this.capacitySignals = capacitySignals;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<KfeChannelSnapshotResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE lightning channels listed.",
                channelLifecycleService.listChannels()));
    }

    @GetMapping("/rebalance/jobs")
    public ResponseEntity<ApiResponse<List<KfeChannelRebalanceJobEntity>>> pendingRebalances(
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return ResponseEntity.ok(ApiResponse.success(
                "Pending channel rebalance jobs listed.",
                channelLifecycleService.pendingRebalances(limit)));
    }

    @PostMapping("/rebalance/jobs/process")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> processRebalanceJobs(
            @RequestParam(name = "limit", defaultValue = "5") int limit) {
        int processed = rebalanceWorker.processBatch(limit);
        return ResponseEntity.ok(ApiResponse.success(
                "Rebalance worker batch executed.",
                Map.of("processed", processed)));
    }

    @GetMapping("/capacity/jobs")
    public ResponseEntity<ApiResponse<List<KfeChannelCapacityJobEntity>>> pendingCapacityJobs(
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return ResponseEntity.ok(ApiResponse.success(
                "Pending channel capacity jobs listed.",
                capacityQueue.pending(limit)));
    }

    @GetMapping("/capacity/signals")
    public ResponseEntity<ApiResponse<KfeCapacitySignalStore.CapacitySignals>> capacitySignals() {
        return ResponseEntity.ok(ApiResponse.success(
                "Capacity stress signals (in-process window).",
                capacitySignals.snapshot(900_000L)));
    }

    @PostMapping("/capacity/scan")
    public ResponseEntity<ApiResponse<Map<String, String>>> capacityScan() {
        capacityController.scan();
        return ResponseEntity.ok(ApiResponse.success(
                "Capacity controller scan executed.",
                Map.of("status", "OK")));
    }

    @PostMapping("/capacity/jobs/process")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> processCapacityJobs(
            @RequestParam(name = "limit", defaultValue = "3") int limit) {
        int processed = capacityWorker.processBatch(limit);
        return ResponseEntity.ok(ApiResponse.success(
                "Capacity worker batch executed.",
                Map.of("processed", processed)));
    }

    @PostMapping("/open/evaluate")
    public ResponseEntity<ApiResponse<KfeChannelDecisionResponse>> evaluateOpen(
            @Valid @RequestBody KfeOpenChannelRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Channel open decision evaluated.",
                channelLifecycleService.evaluateOpen(request)));
    }

    @PostMapping("/open")
    public ResponseEntity<ApiResponse<KfeChannelDecisionResponse>> open(
            @Valid @RequestBody KfeOpenChannelRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Channel open decision processed.",
                channelLifecycleService.openChannel(request)));
    }

    @PostMapping("/rebalance/evaluate")
    public ResponseEntity<ApiResponse<KfeChannelDecisionResponse>> evaluateRebalance(
            @Valid @RequestBody KfeRebalanceChannelRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Channel rebalance decision evaluated.",
                channelLifecycleService.evaluateRebalance(request)));
    }

    @PostMapping("/rebalance")
    public ResponseEntity<ApiResponse<KfeChannelDecisionResponse>> rebalance(
            @Valid @RequestBody KfeRebalanceChannelRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Channel rebalance decision processed.",
                channelLifecycleService.rebalance(request)));
    }

    @PostMapping("/close/evaluate")
    public ResponseEntity<ApiResponse<KfeChannelDecisionResponse>> evaluateClose(
            @Valid @RequestBody KfeCloseChannelRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Channel close decision evaluated.",
                channelLifecycleService.evaluateClose(request)));
    }

    @PostMapping("/close")
    public ResponseEntity<ApiResponse<KfeChannelDecisionResponse>> close(
            @Valid @RequestBody KfeCloseChannelRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Channel close decision processed.",
                channelLifecycleService.closeChannel(request)));
    }

    @PostMapping("/ppm/evaluate")
    public ResponseEntity<ApiResponse<KfeChannelDecisionResponse>> evaluatePpm(
            @Valid @RequestBody KfePpmAdjustRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Channel PPM decision evaluated.",
                channelLifecycleService.evaluatePpm(request)));
    }

    @PostMapping("/ppm")
    public ResponseEntity<ApiResponse<KfeChannelDecisionResponse>> adjustPpm(
            @Valid @RequestBody KfePpmAdjustRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Channel PPM decision processed.",
                channelLifecycleService.adjustPpm(request)));
    }
}
