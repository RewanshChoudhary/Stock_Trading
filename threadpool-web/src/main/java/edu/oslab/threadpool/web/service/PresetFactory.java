package edu.oslab.threadpool.web.service;

import edu.oslab.threadpool.web.dto.PresetResponse;
import edu.oslab.threadpool.web.dto.TaskSpecDto;

import java.util.ArrayList;
import java.util.List;

/**
 * Hard-coded demo workloads. Each preset is a normal run definition, so the frontend only has to
 * POST it to {@code /api/runs} and start it.
 */
public final class PresetFactory {

    private static final int DEFAULT_WORKERS = 4;

    /**
     * A short quantum keeps the demos interesting: burst times of 9-28 ms then take two to three
     * cooperative slices, so the frontend sees yields (and MLFQ demotions) instead of one slice per task.
     */
    private static final long DEFAULT_QUANTUM_MS = 10;

    private PresetFactory() {
    }

    public static List<PresetResponse> presets() {
        return List.of(
                buildPipeline(),
                cycleDemo(),
                diamond(),
                wideFanOut(),
                failureCascade(),
                recoveryBuildMesh(),
                recoveryServiceGraph(),
                recoveryReleaseGate(),
                recoveryEcommerceMesh(),
                recoveryFinancialSettlement(),
                recoveryCloudAiPipeline());
    }

    /** A nine-task build pipeline with a branch that rejoins before linking. */
    private static PresetResponse buildPipeline() {
        return new PresetResponse(
                "Build Pipeline",
                "Nine-task build pipeline: parse and compile, then a parallel optimize/lint branch that rejoins for link, test, package and deploy.",
                PolicyKind.FCFS.name(),
                DEFAULT_WORKERS,
                DEFAULT_QUANTUM_MS,
                List.of(
                        ioTask("checkout", 14, 3),
                        task("parse", 11, 3, "checkout"),
                        task("compile", 27, 3, "parse"),
                        task("optimize", 23, 2, "compile"),
                        task("lint", 20, 2, "compile"),
                        task("link", 17, 3, "optimize", "lint"),
                        task("test", 28, 3, "link"),
                        task("package", 14, 2, "test"),
                        ioTask("deploy", 9, 2, "package")),
                "RECOVER",
                "RULE_BASED");
    }

    /** A deliberately cyclic graph; starting it must be rejected with the cycle path. */
    private static PresetResponse cycleDemo() {
        return new PresetResponse(
                "Cycle Demo",
                "Three tasks that depend on each other in a ring. Creating the run succeeds; starting it is rejected with the full cycle path.",
                PolicyKind.FCFS.name(),
                2,
                DEFAULT_QUANTUM_MS,
                List.of(
                        task("cycle-a", 10, 2, "cycle-c"),
                        task("cycle-b", 10, 2, "cycle-a"),
                        task("cycle-c", 10, 2, "cycle-b")),
                "STRICT",
                "RULE_BASED");
    }

    /** Classic diamond: one root, two parallel branches, one join. */
    private static PresetResponse diamond() {
        return new PresetResponse(
                "Diamond",
                "One root fans out to two branches that must both finish before the join runs.",
                PolicyKind.ROUND_ROBIN.name(),
                DEFAULT_WORKERS,
                DEFAULT_QUANTUM_MS,
                List.of(
                        task("source", 18, 2),
                        task("unit-tests", 28, 2, "source"),
                        task("lint", 15, 2, "source"),
                        task("package", 17, 2, "unit-tests", "lint")),
                "RECOVER",
                "RULE_BASED");
    }

    /** One root, nine parallel dependants: grows the worker pool and shows scheduling order. */
    private static PresetResponse wideFanOut() {
        List<TaskSpecDto> tasks = new ArrayList<>();
        tasks.add(task("checkout", 12, 3));
        for (int index = 1; index <= 9; index++) {
            tasks.add(task("fan-out-" + index, 10 + (index % 4) * 6L, 1 + (index % 3), "checkout"));
        }
        return new PresetResponse(
                "Wide Fan-Out",
                "Ten tasks: one root with nine parallel dependants, which grows the pool and exposes the ready queue depth.",
                PolicyKind.MLFQ.name(),
                DEFAULT_WORKERS,
                DEFAULT_QUANTUM_MS,
                tasks,
                "RECOVER",
                "RULE_BASED");
    }

    /** A linear chain whose second task fails, so the cascade reaches every dependant. */
    private static PresetResponse failureCascade() {
        return new PresetResponse(
                "Failure Cascade",
                "Five-task chain where the second task blows up after 15 ms, failing the whole downstream chain.",
                PolicyKind.PRIORITY.name(),
                3,
                DEFAULT_QUANTUM_MS,
                List.of(
                        task("build", 20, 3),
                        failingTask("compile", 25, 3, 15, "build"),
                        task("unit-tests", 18, 2, "compile"),
                        task("package", 14, 2, "unit-tests"),
                        task("deploy", 12, 2, "package")),
                "STRICT",
                "RULE_BASED");
    }

    /**
     * Compile diamond with every recovery outcome: cache, retry, substitute, skip, retry-then-cache,
     * exhaustion cascade, and a leaf failure with no dependents.
     */
    private static PresetResponse recoveryBuildMesh() {
        return new PresetResponse(
                "Recovery · Build Mesh",
                "Sixteen-task compile mesh: cache, retry, substitute, skip, retry-then-cache, exhaustion cascade, and a leaf failure each fire once.",
                PolicyKind.CRITICAL_PATH.name(),
                6,
                DEFAULT_QUANTUM_MS,
                List.of(
                        task("bm-workspace", 8, 8),
                        ioTask("bm-checkout", 12, 7),
                        failingTask("bm-parse", 22, 9, 8, "bm-workspace", "bm-checkout"),
                        task("bm-compile", 26, 9, "bm-parse"),
                        failingTask("bm-unit-tests", 24, 8, 10, "bm-compile"),
                        failingTask("bm-optimize", 20, 6, 9, "bm-compile"),
                        failingTask("bm-docs-lint", 14, 3, 6, "bm-checkout"),
                        task("bm-link", 16, 8, "bm-unit-tests", "bm-optimize", "bm-docs-lint"),
                        failingTask("bm-integration", 28, 9, 12, "bm-link"),
                        task("bm-package", 15, 7, "bm-integration"),
                        failingTask("bm-sign-artifact", 18, 8, 7, "bm-package"),
                        ioTask("bm-deploy", 14, 7, "bm-sign-artifact"),
                        task("bm-release-notes", 10, 2, "bm-package"),
                        failingTask("bm-telemetry-ping", 11, 1, 5, "bm-workspace"),
                        task("bm-audit", 9, 3, "bm-deploy", "bm-release-notes"),
                        ioTask("bm-notify", 7, 2, "bm-audit")),
                "RECOVER",
                "RULE_BASED");
    }

    /**
     * Multi-service fan-out/join covering the same recovery outcomes with different topology and timing.
     */
    private static PresetResponse recoveryServiceGraph() {
        return new PresetResponse(
                "Recovery · Service Graph",
                "Eighteen-task multi-service graph: auth, billing, and search branches hit every recovery path including exhaustion and a leaf failure.",
                PolicyKind.MLFQ.name(),
                6,
                DEFAULT_QUANTUM_MS,
                List.of(
                        task("sg-bootstrap", 9, 8),
                        ioTask("sg-registry", 13, 7, "sg-bootstrap"),
                        failingTask("sg-auth-compile", 21, 9, 8, "sg-registry"),
                        task("sg-auth-package", 14, 8, "sg-auth-compile"),
                        task("sg-billing-compile", 19, 7, "sg-registry"),
                        failingTask("sg-billing-tests", 25, 8, 11, "sg-billing-compile"),
                        task("sg-billing-package", 15, 7, "sg-billing-tests"),
                        failingTask("sg-search-index", 23, 6, 10, "sg-registry"),
                        task("sg-search-package", 16, 6, "sg-search-index"),
                        failingTask("sg-docs-publish", 12, 2, 5, "sg-bootstrap"),
                        task("sg-mesh-assemble", 18, 9,
                                "sg-auth-package", "sg-billing-package", "sg-search-package", "sg-docs-publish"),
                        failingTask("sg-contract-suite", 30, 9, 13, "sg-mesh-assemble"),
                        task("sg-image-build", 20, 8, "sg-contract-suite"),
                        failingTask("sg-canary-gate", 17, 9, 7, "sg-image-build"),
                        ioTask("sg-rollout", 15, 8, "sg-canary-gate"),
                        failingTask("sg-metrics-ping", 10, 1, 4, "sg-bootstrap"),
                        task("sg-postmortem-draft", 11, 3, "sg-image-build"),
                        ioTask("sg-status-page", 8, 2, "sg-rollout", "sg-postmortem-draft")),
                "RECOVER",
                "RULE_BASED");
    }

    /**
     * Staging-to-production gate with blocking I/O, covering every recovery outcome once.
     */
    private static PresetResponse recoveryReleaseGate() {
        return new PresetResponse(
                "Recovery · Release Gate",
                "Fifteen-task release gate with blocking I/O: image scan through prod health exercise every recovery outcome plus a leaf failure.",
                PolicyKind.ROUND_ROBIN.name(),
                5,
                DEFAULT_QUANTUM_MS,
                List.of(
                        task("rg-candidate", 10, 8),
                        ioTask("rg-image-build", 18, 8, "rg-candidate"),
                        failingTask("rg-image-scan", 20, 9, 8, "rg-image-build"),
                        ioTask("rg-deploy-staging", 16, 8, "rg-image-scan"),
                        failingTask("rg-smoke-tests", 22, 8, 9, "rg-deploy-staging"),
                        failingTask("rg-load-probe", 24, 6, 10, "rg-deploy-staging"),
                        failingTask("rg-changelog", 11, 2, 5, "rg-candidate"),
                        task("rg-staging-gate", 14, 9, "rg-smoke-tests", "rg-load-probe", "rg-changelog"),
                        failingTask("rg-staging-verify", 26, 9, 12, "rg-staging-gate"),
                        ioTask("rg-promote", 13, 8, "rg-staging-verify"),
                        failingTask("rg-prod-health", 19, 9, 7, "rg-promote"),
                        ioTask("rg-traffic-shift", 15, 8, "rg-prod-health"),
                        failingTask("rg-heartbeat", 9, 1, 4, "rg-candidate"),
                        task("rg-rollback-plan", 12, 4, "rg-staging-verify"),
                        ioTask("rg-announce", 8, 2, "rg-traffic-shift", "rg-rollback-plan")),
                "RECOVER",
                "RULE_BASED");
    }

    /**
     * 48-task global omnichannel e-commerce fulfillment mesh with critical path scheduling,
     * virtual lane payment/shipping I/O, and full recovery profile coverage.
     */
    private static PresetResponse recoveryEcommerceMesh() {
        return new PresetResponse(
                "Recovery · E-Commerce Mesh",
                "Forty-eight-task omnichannel fulfillment mesh: Critical Path policy, 8 workers, mixed platform/virtual lanes, and every recovery outcome under high load.",
                PolicyKind.CRITICAL_PATH.name(),
                8,
                DEFAULT_QUANTUM_MS,
                List.of(
                        task("ecom-cart-validate", 12, 9),
                        ioTask("ecom-customer-auth", 15, 8),
                        ioTask("ecom-geo-ip-lookup", 10, 6),
                        task("ecom-pricing-engine", 24, 9, "ecom-cart-validate"),
                        task("ecom-promo-eval", 18, 7, "ecom-cart-validate", "ecom-customer-auth"),
                        failingTask("ecom-tax-nexus", 22, 9, 6, "ecom-geo-ip-lookup", "ecom-pricing-engine"),
                        ioTask("ecom-address-normalize", 14, 7, "ecom-geo-ip-lookup"),
                        failingTask("ecom-fraud-score", 28, 9, 12, "ecom-customer-auth", "ecom-pricing-engine"),
                        ioTask("ecom-kyc-verify", 16, 7, "ecom-customer-auth"),
                        failingIoTask("ecom-inventory-hold", 25, 9, 8, "ecom-pricing-engine"),
                        task("ecom-order-total-seal", 14, 9, "ecom-pricing-engine", "ecom-promo-eval", "ecom-tax-nexus"),
                        ioTask("ecom-3ds-challenge", 18, 9, "ecom-fraud-score", "ecom-order-total-seal"),
                        failingIoTask("ecom-payment-primary", 26, 10, 10, "ecom-3ds-challenge", "ecom-inventory-hold"),
                        failingTask("ecom-loyalty-calc", 12, 3, 5, "ecom-order-total-seal"),
                        task("ecom-order-ledger-record", 16, 9, "ecom-payment-primary"),
                        task("ecom-receipt-token", 11, 8, "ecom-payment-primary"),
                        task("ecom-loyalty-credit", 10, 3, "ecom-payment-primary", "ecom-loyalty-calc"),
                        task("ecom-warehouse-allocate", 20, 9, "ecom-order-ledger-record", "ecom-address-normalize"),
                        ioTask("ecom-stock-decrement", 15, 8, "ecom-warehouse-allocate"),
                        task("ecom-packing-slip-gen", 14, 7, "ecom-warehouse-allocate"),
                        ioTask("ecom-shipping-carrier-rate", 18, 8, "ecom-warehouse-allocate", "ecom-address-normalize"),
                        task("ecom-shipping-label-gen", 16, 8, "ecom-shipping-carrier-rate", "ecom-packing-slip-gen"),
                        task("ecom-customs-declaration", 19, 7, "ecom-shipping-carrier-rate", "ecom-tax-nexus"),
                        task("ecom-barcode-render", 10, 6, "ecom-shipping-label-gen"),
                        ioTask("ecom-package-tracking-init", 12, 7, "ecom-shipping-label-gen"),
                        failingTask("ecom-fiscal-seal", 20, 8, 7, "ecom-order-ledger-record"),
                        task("ecom-invoice-pdf-render", 22, 7, "ecom-fiscal-seal"),
                        ioTask("ecom-tax-filing-archive", 15, 8, "ecom-fiscal-seal"),
                        ioTask("ecom-customs-manifest-post", 16, 7, "ecom-fiscal-seal", "ecom-customs-declaration"),
                        ioTask("ecom-s3-invoice-archive", 14, 6, "ecom-invoice-pdf-render"),
                        ioTask("ecom-email-customer-receipt", 15, 7, "ecom-receipt-token", "ecom-package-tracking-init"),
                        ioTask("ecom-sms-dispatch", 9, 5, "ecom-receipt-token"),
                        ioTask("ecom-app-push-notify", 8, 4, "ecom-receipt-token"),
                        ioTask("ecom-erp-general-ledger", 24, 7, "ecom-order-ledger-record", "ecom-stock-decrement"),
                        task("ecom-reconciliation-stream", 14, 6, "ecom-erp-general-ledger"),
                        task("ecom-bi-sales-fact-ingest", 18, 4, "ecom-order-ledger-record"),
                        task("ecom-fraud-model-feedback", 13, 5, "ecom-payment-primary", "ecom-fraud-score"),
                        task("ecom-vendor-payout-calc", 17, 6, "ecom-erp-general-ledger"),
                        ioTask("ecom-vendor-wire-queue", 12, 6, "ecom-vendor-payout-calc"),
                        task("ecom-warehouse-robot-route", 20, 8, "ecom-barcode-render", "ecom-stock-decrement"),
                        ioTask("ecom-box-sorter-signal", 11, 8, "ecom-warehouse-robot-route"),
                        task("ecom-dock-staging-assign", 13, 7, "ecom-box-sorter-signal"),
                        task("ecom-telemetry-metrics-push", 9, 2, "ecom-cart-validate"),
                        failingTask("ecom-analytics-beacon", 8, 1, 4, "ecom-pricing-engine"),
                        task("ecom-audit-checkpoint", 10, 5, "ecom-dock-staging-assign", "ecom-s3-invoice-archive"),
                        task("ecom-fulfillment-completed", 7, 9, "ecom-dock-staging-assign"),
                        ioTask("ecom-ops-dashboard-sync", 8, 3, "ecom-fulfillment-completed", "ecom-audit-checkpoint"),
                        task("ecom-order-closed", 6, 10, "ecom-fulfillment-completed", "ecom-ops-dashboard-sync")),
                "RECOVER",
                "RULE_BASED");
    }

    /**
     * 52-task real-time financial risk and settlement graph with MLFQ policy, Monte Carlo bursts,
     * SWIFT wire transfers, and all recovery profiles.
     */
    private static PresetResponse recoveryFinancialSettlement() {
        return new PresetResponse(
                "Recovery · Financial Settlement",
                "Fifty-two-task high-frequency risk and trade settlement engine: MLFQ scheduling, Monte Carlo simulations, clearing gates, and all recovery profiles.",
                PolicyKind.MLFQ.name(),
                8,
                DEFAULT_QUANTUM_MS,
                List.of(
                        task("fin-trade-intake", 10, 10),
                        task("fin-fix-protocol-decode", 14, 10, "fin-trade-intake"),
                        ioTask("fin-instrument-master-query", 16, 9, "fin-fix-protocol-decode"),
                        ioTask("fin-market-ticks-feed", 18, 8),
                        ioTask("fin-fx-matrix-feed", 15, 8),
                        failingTask("fin-news-sentiment", 12, 2, 5),
                        failingIoTask("fin-counterparty-rating", 24, 8, 8, "fin-instrument-master-query"),
                        task("fin-order-book-depth", 20, 8, "fin-market-ticks-feed", "fin-instrument-master-query"),
                        task("fin-fx-triangulation", 22, 8, "fin-fx-matrix-feed"),
                        task("fin-notional-value-calc", 14, 9, "fin-fix-protocol-decode", "fin-fx-triangulation"),
                        failingTask("fin-volatility-surface", 26, 8, 8, "fin-order-book-depth"),
                        task("fin-black-scholes-greeks", 35, 8, "fin-volatility-surface", "fin-notional-value-calc"),
                        task("fin-monte-carlo-var", 55, 7, "fin-black-scholes-greeks", "fin-news-sentiment"),
                        task("fin-scenario-stress-test", 42, 7, "fin-black-scholes-greeks"),
                        task("fin-credit-exposure-eval", 20, 8, "fin-counterparty-rating", "fin-notional-value-calc"),
                        task("fin-liquidity-horizon-check", 18, 7, "fin-order-book-depth"),
                        failingTask("fin-margin-netting", 28, 9, 11, "fin-credit-exposure-eval", "fin-monte-carlo-var"),
                        ioTask("fin-collateral-allocation", 22, 9, "fin-margin-netting"),
                        task("fin-interest-rate-accrual", 16, 6, "fin-notional-value-calc"),
                        task("fin-portfolio-rebalance-eval", 30, 7, "fin-margin-netting", "fin-scenario-stress-test"),
                        failingTask("fin-aml-sanctions-check", 20, 10, 6, "fin-fix-protocol-decode", "fin-instrument-master-query"),
                        task("fin-fatca-crs-compliance", 18, 8, "fin-aml-sanctions-check"),
                        task("fin-sec-rule-15c3-eval", 22, 9, "fin-margin-netting"),
                        failingIoTask("fin-clearing-primary", 25, 10, 9, "fin-collateral-allocation", "fin-sec-rule-15c3-eval"),
                        ioTask("fin-dtcc-net-settle-post", 20, 9, "fin-clearing-primary"),
                        ioTask("fin-cme-margin-pledge", 18, 8, "fin-clearing-primary"),
                        ioTask("fin-euroclear-custody-hold", 19, 8, "fin-clearing-primary"),
                        task("fin-swift-mt540-format", 16, 9, "fin-dtcc-net-settle-post"),
                        ioTask("fin-swift-wire-dispatch", 24, 9, "fin-swift-mt540-format"),
                        ioTask("fin-fedwire-funds-transfer", 22, 9, "fin-dtcc-net-settle-post"),
                        task("fin-nostro-vostro-reconcile", 18, 8, "fin-swift-wire-dispatch", "fin-fedwire-funds-transfer"),
                        task("fin-cash-ledger-entry", 14, 8, "fin-nostro-vostro-reconcile"),
                        ioTask("fin-securities-depository-record", 16, 8, "fin-euroclear-custody-hold"),
                        task("fin-general-ledger-journal", 18, 7, "fin-cash-ledger-entry", "fin-interest-rate-accrual"),
                        task("fin-sub-ledger-sub-account", 12, 6, "fin-general-ledger-journal"),
                        task("fin-trade-confirmation-pdf", 22, 7, "fin-dtcc-net-settle-post"),
                        ioTask("fin-client-portal-dispatch", 14, 6, "fin-trade-confirmation-pdf"),
                        ioTask("fin-cftc-large-trader-export", 20, 7, "fin-sec-rule-15c3-eval", "fin-portfolio-rebalance-eval"),
                        ioTask("fin-mifid2-transaction-report", 22, 8, "fin-dtcc-net-settle-post", "fin-fatca-crs-compliance"),
                        ioTask("fin-finra-oats-cats-sync", 19, 7, "fin-dtcc-net-settle-post"),
                        ioTask("fin-risk-dashboard-stream", 13, 5, "fin-monte-carlo-var", "fin-scenario-stress-test"),
                        task("fin-trader-pnl-calc", 17, 7, "fin-cash-ledger-entry", "fin-notional-value-calc"),
                        ioTask("fin-trader-blotter-update", 11, 6, "fin-trader-pnl-calc"),
                        task("fin-algorithmic-feedback-loop", 15, 6, "fin-trader-pnl-calc", "fin-liquidity-horizon-check"),
                        failingTask("fin-bloomberg-archive-ping", 9, 1, 4, "fin-fix-protocol-decode"),
                        ioTask("fin-regulatory-vault-archive", 17, 8, "fin-mifid2-transaction-report", "fin-cftc-large-trader-export", "fin-finra-oats-cats-sync"),
                        task("fin-eod-reconciliation-gate", 16, 9, "fin-general-ledger-journal", "fin-securities-depository-record"),
                        task("fin-compliance-audit-token", 12, 8, "fin-regulatory-vault-archive"),
                        ioTask("fin-risk-officer-signoff", 11, 9, "fin-eod-reconciliation-gate", "fin-compliance-audit-token"),
                        task("fin-settlement-finality-seal", 8, 10, "fin-risk-officer-signoff"),
                        task("fin-telemetry-metrics-broadcast", 9, 2, "fin-trade-intake"),
                        task("fin-trade-cycle-complete", 6, 10, "fin-settlement-finality-seal", "fin-trader-blotter-update")),
                "RECOVER",
                "RULE_BASED");
    }

    /**
     * 50-task autonomous multi-cloud AI infrastructure and K8s pipeline with priority scheduling,
     * GPU provisioning, model verification, and all recovery profiles.
     */
    private static PresetResponse recoveryCloudAiPipeline() {
        return new PresetResponse(
                "Recovery · Cloud AI Pipeline",
                "Fifty-task autonomous cloud and MLOps deployment pipeline: Priority policy, K8s cluster bootstrap, GPU pool allocations, canary routing, and all recovery profiles.",
                PolicyKind.PRIORITY.name(),
                8,
                DEFAULT_QUANTUM_MS,
                List.of(
                        ioTask("cloud-git-tag-webhook", 10, 9),
                        task("cloud-terraform-plan-eval", 24, 9, "cloud-git-tag-webhook"),
                        ioTask("cloud-aws-vpc-peer-init", 20, 8, "cloud-terraform-plan-eval"),
                        ioTask("cloud-gcp-interconnect-check", 18, 8, "cloud-terraform-plan-eval"),
                        ioTask("cloud-azure-vnet-handshake", 19, 7, "cloud-terraform-plan-eval"),
                        task("cloud-mesh-routing-table", 16, 8, "cloud-aws-vpc-peer-init", "cloud-gcp-interconnect-check", "cloud-azure-vnet-handshake"),
                        failingIoTask("cloud-vault-secrets-fetch", 22, 9, 7, "cloud-git-tag-webhook"),
                        failingTask("cloud-etcd-cluster-sync", 26, 9, 10, "cloud-mesh-routing-table", "cloud-vault-secrets-fetch"),
                        ioTask("cloud-k8s-control-plane-boot", 28, 9, "cloud-etcd-cluster-sync"),
                        task("cloud-cilium-cni-daemonset", 18, 8, "cloud-k8s-control-plane-boot"),
                        task("cloud-coredns-autoscaler", 14, 7,
                                "cloud-cilium-cni-daemonset", "cloud-datadog-agent-init"),
                        failingTask("cloud-datadog-agent-init", 12, 3, 4, "cloud-k8s-control-plane-boot"),
                        ioTask("cloud-cpu-worker-pool-spinup", 25, 8, "cloud-cilium-cni-daemonset"),
                        failingIoTask("cloud-gpu-instance-alloc", 30, 10, 9, "cloud-cilium-cni-daemonset"),
                        task("cloud-nvidia-operator-install", 22, 9, "cloud-gpu-instance-alloc"),
                        task("cloud-cuda-driver-verify", 16, 9, "cloud-nvidia-operator-install"),
                        failingIoTask("cloud-docker-base-layer", 25, 8, 8, "cloud-git-tag-webhook"),
                        task("cloud-pytorch-runtime-build", 32, 8, "cloud-docker-base-layer"),
                        task("cloud-triton-inference-server-image", 24, 8, "cloud-pytorch-runtime-build"),
                        ioTask("cloud-s3-model-weights-download", 38, 9, "cloud-vault-secrets-fetch"),
                        task("cloud-model-sha256-verify", 26, 9, "cloud-s3-model-weights-download"),
                        task("cloud-quantization-int8-eval", 35, 7, "cloud-model-sha256-verify"),
                        task("cloud-lora-adapter-merge", 28, 8, "cloud-model-sha256-verify"),
                        ioTask("cloud-pv-claim-nvme-bind", 17, 8, "cloud-cuda-driver-verify"),
                        task("cloud-model-checkpoint-hydrate", 34, 9, "cloud-pv-claim-nvme-bind", "cloud-lora-adapter-merge"),
                        ioTask("cloud-triton-deployment-rollout", 26, 9, "cloud-model-checkpoint-hydrate", "cloud-triton-inference-server-image", "cloud-cpu-worker-pool-spinup"),
                        task("cloud-keda-autoscaler-policy", 15, 6, "cloud-triton-deployment-rollout"),
                        failingTask("cloud-root-cert-authority", 20, 9, 6, "cloud-k8s-control-plane-boot", "cloud-vault-secrets-fetch"),
                        task("cloud-cert-manager-issuer", 16, 8, "cloud-root-cert-authority"),
                        ioTask("cloud-tls-wildcard-cert-gen", 18, 8, "cloud-cert-manager-issuer"),
                        task("cloud-envoy-gateway-mtls", 22, 8, "cloud-tls-wildcard-cert-gen"),
                        task("cloud-istio-virtual-service", 17, 8, "cloud-envoy-gateway-mtls"),
                        ioTask("cloud-canary-pod-warmup", 24, 8, "cloud-triton-deployment-rollout"),
                        ioTask("cloud-synthetic-load-probe", 28, 8, "cloud-canary-pod-warmup"),
                        task("cloud-p99-latency-benchmark", 20, 8, "cloud-synthetic-load-probe"),
                        task("cloud-token-throughput-eval", 19, 7, "cloud-synthetic-load-probe"),
                        ioTask("cloud-canary-traffic-10pct", 16, 9, "cloud-p99-latency-benchmark", "cloud-token-throughput-eval", "cloud-istio-virtual-service"),
                        task("cloud-error-budget-gauge", 14, 8, "cloud-canary-traffic-10pct"),
                        ioTask("cloud-canary-traffic-50pct", 18, 9, "cloud-error-budget-gauge"),
                        ioTask("cloud-full-traffic-cutover", 20, 10, "cloud-canary-traffic-50pct"),
                        ioTask("cloud-cloudflare-dns-switch", 18, 9, "cloud-full-traffic-cutover"),
                        ioTask("cloud-edge-cache-purge", 13, 8, "cloud-cloudflare-dns-switch"),
                        task("cloud-prometheus-alert-arm", 12, 7, "cloud-cloudflare-dns-switch"),
                        ioTask("cloud-grafana-dash-publish", 14, 5, "cloud-prometheus-alert-arm"),
                        failingTask("cloud-pagerduty-heartbeat", 8, 1, 3, "cloud-terraform-plan-eval"),
                        ioTask("cloud-slack-release-announce", 10, 6, "cloud-edge-cache-purge"),
                        ioTask("cloud-old-replicaset-drain", 22, 7, "cloud-edge-cache-purge"),
                        task("cloud-cluster-autoscaler-steady", 16, 7, "cloud-old-replicaset-drain", "cloud-keda-autoscaler-policy"),
                        task("cloud-deployment-audit-seal", 11, 9, "cloud-cluster-autoscaler-steady", "cloud-grafana-dash-publish"),
                        task("cloud-release-pipeline-success", 7, 10, "cloud-deployment-audit-seal", "cloud-slack-release-announce")),
                "RECOVER",
                "RULE_BASED");
    }

    private static TaskSpecDto task(String id, long burstMs, int priority, String... dependsOn) {
        return new TaskSpecDto(id, burstMs, priority, List.of(dependsOn), null, false);
    }

    /** A task that blocks on I/O, so it is routed to the virtual lane. */
    private static TaskSpecDto ioTask(String id, long burstMs, int priority, String... dependsOn) {
        return new TaskSpecDto(id, burstMs, priority, List.of(dependsOn), null, true);
    }

    private static TaskSpecDto failingTask(
            String id, long burstMs, int priority, long failAtMs, String... dependsOn) {
        return new TaskSpecDto(id, burstMs, priority, List.of(dependsOn), failAtMs, false);
    }

    private static TaskSpecDto failingIoTask(
            String id, long burstMs, int priority, long failAtMs, String... dependsOn) {
        return new TaskSpecDto(id, burstMs, priority, List.of(dependsOn), failAtMs, true);
    }
}
