package source.kfe.service;

import jakarta.annotation.PreDestroy;
import org.bitcoinj.core.NetworkParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import org.zeromq.ZMsg;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Option 1: subscribe to Bitcoin Core ZMQ {@code hashblock} (+ optional {@code rawtx}),
 * filter against monitored cold addresses, and kick a debounced observe/balance refresh.
 *
 * <p>Poll-based {@link KfeOnchainBalanceSyncService} / {@link KfeColdWalletObservationService}
 * remain the safety net if ZMQ is down or events are missed.
 */
@Component
@ConditionalOnProperty(name = "kfe.bitcoin.zmq.enabled", havingValue = "true")
public class KfeBitcoinZmqWatcher implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(KfeBitcoinZmqWatcher.class);

    private final KfeMonitoredChainAddressIndex addressIndex;
    private final KfeColdWalletReactiveRefreshService refreshService;
    private final ObjectProvider<KfeColdWalletObservationService> coldObservationService;
    private final ObjectProvider<KfeCustodialDepositObservationService> custodialDepositObservationService;
    private final String hashblockEndpoint;
    private final String rawtxEndpoint;
    private final boolean subscribeRawTx;
    private final NetworkParameters networkParameters;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<Thread> workers = new ArrayList<>();
    private ZContext zContext;

    public KfeBitcoinZmqWatcher(
            KfeMonitoredChainAddressIndex addressIndex,
            KfeColdWalletReactiveRefreshService refreshService,
            ObjectProvider<KfeColdWalletObservationService> coldObservationService,
            ObjectProvider<KfeCustodialDepositObservationService> custodialDepositObservationService,
            @Value("${kfe.bitcoin.zmq.hashblock:}") String hashblockEndpoint,
            @Value("${kfe.bitcoin.zmq.rawtx:}") String rawtxEndpoint,
            @Value("${kfe.bitcoin.zmq.subscribe-rawtx:true}") boolean subscribeRawTx,
            @Value("${bitcoin.network:mainnet}") String bitcoinNetwork) {
        this.addressIndex = addressIndex;
        this.refreshService = refreshService;
        this.coldObservationService = coldObservationService;
        this.custodialDepositObservationService = custodialDepositObservationService;
        this.hashblockEndpoint = blankToNull(hashblockEndpoint);
        this.rawtxEndpoint = blankToNull(rawtxEndpoint);
        this.subscribeRawTx = subscribeRawTx;
        this.networkParameters = KfeBitcoinZmqTxMatcher.networkParameters(bitcoinNetwork);
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        if (hashblockEndpoint == null && rawtxEndpoint == null) {
            log.warn("[KFE ZMQ] enabled but no hashblock/rawtx endpoints configured — watcher idle");
            running.set(false);
            return;
        }
        try {
            addressIndex.rebuild();
        } catch (RuntimeException exception) {
            log.warn("[KFE ZMQ] initial address index rebuild failed: {}", exception.getMessage());
        }
        zContext = new ZContext();
        if (hashblockEndpoint != null) {
            // Prefer hashblock (32-byte tip hash). Some local bitcoind images only
            // publish rawblock on the same port — accept either topic name.
            startWorker("kfe-zmq-hashblock", hashblockEndpoint, "hashblock", this::onHashBlock);
            startWorker("kfe-zmq-rawblock", hashblockEndpoint, "rawblock", this::onHashBlock);
        }
        if (subscribeRawTx && rawtxEndpoint != null) {
            startWorker("kfe-zmq-rawtx", rawtxEndpoint, "rawtx", this::onRawTx);
        }
        log.info(
                "[KFE ZMQ] watcher started hashblock={} rawtx={} addresses={}",
                hashblockEndpoint != null,
                subscribeRawTx && rawtxEndpoint != null,
                addressIndex.addressCount());
    }

    @Override
    public void stop() {
        stopInternal();
    }

    @PreDestroy
    public void destroy() {
        stopInternal();
    }

    private void stopInternal() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (zContext != null) {
            try {
                zContext.close();
            } catch (RuntimeException ignored) {
                // shutdown
            }
            zContext = null;
        }
        for (Thread worker : workers) {
            try {
                worker.interrupt();
                worker.join(2000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        workers.clear();
        log.info("[KFE ZMQ] watcher stopped");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 50;
    }

    private void startWorker(String name, String endpoint, String topic, PayloadHandler handler) {
        Thread worker = new Thread(() -> listenLoop(endpoint, topic, handler), name);
        worker.setDaemon(true);
        workers.add(worker);
        worker.start();
    }

    private void listenLoop(String endpoint, String topic, PayloadHandler handler) {
        int backoffMs = 500;
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try (ZMQ.Socket socket = zContext.createSocket(SocketType.SUB)) {
                socket.setReceiveTimeOut(3000);
                socket.connect(endpoint);
                socket.subscribe(topic.getBytes(StandardCharsets.UTF_8));
                log.info("[KFE ZMQ] subscribed topic={} endpoint={}", topic, endpoint);
                backoffMs = 500;
                long received = 0L;
                while (running.get() && !Thread.currentThread().isInterrupted()) {
                    // Bitcoin Core multipart: [topic][body][sequence]
                    byte[] topicFrame = socket.recv(0);
                    if (topicFrame == null) {
                        continue; // timeout
                    }
                    byte[] body = socket.recv(0);
                    // drain optional sequence / trailing frames
                    while (socket.hasReceiveMore()) {
                        socket.recv(0);
                    }
                    if (body == null) {
                        continue;
                    }
                    String receivedTopic = new String(topicFrame, StandardCharsets.UTF_8).trim();
                    if (!topic.equalsIgnoreCase(receivedTopic)) {
                        continue;
                    }
                    received++;
                    if (received == 1L || received % 100L == 0L) {
                        log.info(
                                "[KFE ZMQ] topic={} messages={} bodyBytes={}",
                                topic,
                                received,
                                body.length);
                    }
                    handler.handle(body);
                }
            } catch (Exception exception) {
                if (!running.get()) {
                    break;
                }
                log.warn(
                        "[KFE ZMQ] listener error topic={} endpoint={}: {} — retry in {}ms",
                        topic,
                        endpoint,
                        exception.getMessage(),
                        backoffMs);
                sleep(backoffMs);
                backoffMs = Math.min(15_000, backoffMs * 2);
            }
        }
    }

    private void onHashBlock(byte[] body) {
        // body is 32-byte block hash — we only need the tip signal
        log.debug("[KFE ZMQ] hashblock signal ({} bytes)", body == null ? 0 : body.length);
        refreshService.onNewBlock();
    }

    private void onRawTx(byte[] body) {
        if (body == null || body.length == 0) {
            return;
        }
        KfeBitcoinZmqTxMatcher.ParsedRawTx parsed =
                KfeBitcoinZmqTxMatcher.parse(body, networkParameters);
        if (parsed == null) {
            return;
        }
        Set<String> outputs = new java.util.LinkedHashSet<>();
        for (KfeBitcoinZmqTxMatcher.ParsedOutput out : parsed.outputs()) {
            if (out.address() != null && !out.address().isBlank()) {
                outputs.add(out.address());
            }
        }
        Set<UUID> hits = new java.util.HashSet<>();
        if (!outputs.isEmpty()) {
            hits.addAll(addressIndex.walletIdsForAddresses(outputs));
        }
        // Electrum spends: inputs spend our known cold funding txids (no change required).
        Set<String> fundingTxids = new java.util.LinkedHashSet<>();
        for (KfeBitcoinZmqTxMatcher.ParsedInput in : parsed.inputs()) {
            if (in.fundingTxid() != null && !in.fundingTxid().isBlank()) {
                fundingTxids.add(in.fundingTxid());
            }
        }
        if (!fundingTxids.isEmpty()) {
            hits.addAll(addressIndex.walletIdsForFundingTxids(fundingTxids));
        }
        if (hits.isEmpty()) {
            return;
        }
        log.info(
                "[KFE ZMQ] rawtx matched wallets={} outputs={} fundingInputs={} txid={}",
                hits.size(),
                outputs.size(),
                fundingTxids.size(),
                parsed.txid());
        // Instant expose at 0 confs — do not wait for scantxoutset / debounce.
        // Cold: ingest raw outputs into history as VALIDATING.
        KfeColdWalletObservationService coldObs = coldObservationService.getIfAvailable();
        if (coldObs != null) {
            try {
                coldObs.ingestZmqRawTx(parsed, hits);
            } catch (RuntimeException exception) {
                log.warn(
                        "[KFE ZMQ] instant cold ingest failed txid={}: {}",
                        parsed.txid(),
                        exception.getMessage());
            }
        }
        // Custodial / INTERNAL: ingest outputs at 0 conf immediately (listunspent often
        // lags mempool). Debounced observeWallet still refreshes confs + available credit.
        KfeCustodialDepositObservationService custodialObs =
                custodialDepositObservationService.getIfAvailable();
        if (custodialObs != null) {
            try {
                custodialObs.ingestZmqRawTx(parsed, hits);
            } catch (RuntimeException exception) {
                log.warn(
                        "[KFE ZMQ] instant custodial ingest failed txid={}: {}",
                        parsed.txid(),
                        exception.getMessage());
            }
        }
        // Debounced full observe still runs for balance truth + confirmation refresh.
        refreshService.onWalletsTouched(hits);
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @FunctionalInterface
    private interface PayloadHandler {
        void handle(byte[] body);
    }
}
