package so.fox.nodechain.p2p;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.Serializable;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import so.fox.nodechain.config.NodeConfig;
import so.fox.nodechain.core.BlockchainService;
import so.fox.nodechain.core.NodeWalletService;
import so.fox.nodechain.core.Result;
import so.fox.nodechain.crypto.CryptoUtil;
import so.fox.nodechain.model.Block;
import so.fox.nodechain.model.PeerInfo;
import so.fox.nodechain.model.Transaction;
import so.fox.nodechain.p2p.packet.NodePacket;
import so.fox.nodechain.p2p.packet.OpCode;
import so.fox.nodechain.repository.PeerRepository;

// P2P сетевой сервис узла: сетевые подключения, gossip-протокол и синхронизация цепи
@Service
public class P2pClientManager {

  private static final Logger log = LoggerFactory.getLogger(P2pClientManager.class);
  public static final int MAX_CONNECTIONS = 64;
  private static final long MIN_REQUEST_INTERVAL_MS = 100L;
  private static final String VERSION = "1.0.0";

  // Валидирует криптографическое рукопожатие сетевого узла с обязательной подписью RSA
  public static boolean isValidHandshake(HandshakeMessage msg, HandshakeContext ctx) {
    if (msg == null || ctx == null || ctx.chainId() == null || !ctx.chainId().equals(msg.chainId())) return false;
    if (msg.nodeId() == null || msg.nodeId().isBlank() || msg.nodeId().equalsIgnoreCase(ctx.localNodeId())) return false;
    if (msg.height() < 0 || msg.p2pPort() < 0 || msg.p2pPort() > 65535) return false;
    if (msg.latestBlockHash() == null || msg.latestBlockHash().length() != 64) return false;
    if (msg.timestamp() <= 0 || ctx.now() < (msg.timestamp() - 60_000L) || ctx.now() > (msg.timestamp() + 120_000L)) return false;

    if (msg.publicKey() == null || msg.publicKey().isBlank()) return false;
    if (msg.signature() == null || msg.signature().isBlank()) return false;

    PublicKey pubKey = CryptoUtil.parsePublicKey(msg.publicKey());
    if (pubKey == null) return false;

    String derivedAddr = CryptoUtil.getAddressFromPublicKey(pubKey);
    if (!msg.nodeId().equalsIgnoreCase(derivedAddr)) return false;

    return CryptoUtil.isSignatureValid(pubKey, msg.toSignableData(), msg.signature());
  }

  private static ThreadFactory daemonFactory(String name) {
    return r -> {
      Thread t = new Thread(r, name);
      t.setDaemon(true);
      return t;
    };
  }

  private final NodeConfig nodeConfig;
  private final PeerRepository peerRepository;
  private final BlockchainService blockchainService;
  private final NodeWalletService nodeWalletService;

  private final List<PeerConnection> connections = new CopyOnWriteArrayList<>();
  private final Semaphore connectionSlots = new Semaphore(MAX_CONNECTIONS);
  private final Map<String, Long> lastRequestTime = new ConcurrentHashMap<>();
  private final AtomicBoolean isRunning = new AtomicBoolean(false);

  private volatile String nodeId = UUID.randomUUID().toString();
  private ServerSocket serverSocket;
  private ExecutorService serverExecutor;
  private ScheduledExecutorService scheduler;
  private volatile long maxPeerHeight = 0L;

  public P2pClientManager(
    NodeConfig nodeConfig,
    PeerRepository peerRepository,
    @Lazy BlockchainService blockchainService,
    NodeWalletService nodeWalletService
  ) {
    this.nodeConfig = nodeConfig;
    this.peerRepository = peerRepository;
    this.blockchainService = blockchainService;
    this.nodeWalletService = nodeWalletService;
  }

  // Запускает TCP-сервер, циклы синхронизации и heartbeats
  @PostConstruct
  public void start() {
    if (nodeWalletService.address != null && !nodeWalletService.address.isEmpty())
      this.nodeId = nodeWalletService.address;
    startServer();

    scheduler = Executors.newScheduledThreadPool(2, daemonFactory("p2p-client-manager"));
    scheduler.schedule(this::connectToSeedsAndKnownPeers, 1, TimeUnit.SECONDS);
    scheduler.scheduleWithFixedDelay(this::connectToSeedsAndKnownPeers, 30, 30, TimeUnit.SECONDS);
    scheduler.scheduleWithFixedDelay(this::sendHeartbeats, 20, 20, TimeUnit.SECONDS);
  }

  // Останавливает сервер и закрывает все сетевые соединения
  @PreDestroy
  public void stop() {
    isRunning.set(false);
    try {
      if (serverSocket != null) serverSocket.close();
    } catch (IOException ignored) {}
    if (serverExecutor != null) serverExecutor.shutdownNow();
    if (scheduler != null) scheduler.shutdownNow();
    for (PeerConnection conn : connections) {
      conn.close();
    }
    connections.clear();
    connectionSlots.drainPermits();
    connectionSlots.release(MAX_CONNECTIONS);
  }

  // Обрабатывает событие отключения пира и освобождает слот соединения
  public void onDisconnected(PeerConnection conn) {
    if (conn == null) return;
    if (connections.remove(conn)) connectionSlots.release();
    String hostPrefix = conn.getRemoteHost() + ":" + conn.getRemotePort() + ":";
    lastRequestTime.keySet().removeIf(k -> k.startsWith(hostPrefix));
    log.info("P2P peer disconnected: {}:{}", conn.getRemoteHost(), conn.getRemotePort());
  }

  // Инициирует исходящее TCP-соединение с удаленным пиром с атомарным резервированием слота
  public void connectToPeer(String host, int port) {
    if (host == null || port <= 0 || port > 65535) return;
    if (port == nodeConfig.p2pPort && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host))) return;
    for (PeerConnection conn : connections) {
      if (conn.getRemoteHost().equals(host) && conn.getRemotePort() == port) return;
    }
    if (!connectionSlots.tryAcquire()) return;

    Socket socket = new Socket();
    try {
      socket.connect(new java.net.InetSocketAddress(host, port), 5000);
      socket.setSoTimeout(45000);
      PeerConnection conn = new PeerConnection(socket, this);
      conn.remotePort = port;
      connections.add(conn);

      Thread t = new Thread(conn, "peer-" + host + ":" + port);
      t.setDaemon(true);
      t.start();
      sendHandshake(conn);
    } catch (IOException err) {
      connectionSlots.release();
      try {
        socket.close();
      } catch (IOException ignored) {}
      log.debug("Could not connect to peer {}:{}: {}", host, port, err.getMessage());
    }
  }

  // Универсальная рассылка сетевого пакета всем активным узлам (Gossip)
  public void broadcast(byte opCode, Object payload, PeerConnection except) {
    if (payload == null) return;
    NodePacket pkt = NodePacket.fromJson(opCode, 0, payload);
    if (pkt == null) return;
    for (PeerConnection conn : connections) {
      if (conn != except && conn.isConnected()) conn.send(pkt);
    }
  }

  // Обрабатывает входящий пакет сетевого протокола от пира
  public void handlePacket(PeerConnection conn, NodePacket pkt) {
    if (conn == null || pkt == null) return;
    byte op = pkt.opCode;
    if (op != OpCode.HANDSHAKE && op != OpCode.HANDSHAKE_ACK && conn.remoteNodeId == null) {
      conn.close();
      return;
    }
    if (isRateLimited(conn, op)) return;

    switch (op) {
      case OpCode.HANDSHAKE -> handleHandshake(conn, pkt, false);
      case OpCode.HANDSHAKE_ACK -> handleHandshake(conn, pkt, true);
      case OpCode.PING -> handlePing(conn, pkt);
      case OpCode.PONG -> handlePong(conn);
      case OpCode.GET_PEERS -> handleGetPeers(conn, pkt);
      case OpCode.PEERS_LIST -> handlePeersList(pkt);
      case OpCode.GET_BLOCKS -> handleGetBlocks(conn, pkt);
      case OpCode.BLOCKS_DATA -> handleBlocksData(conn, pkt);
      case OpCode.NEW_BLOCK_ANNOUNCE -> handleNewBlock(conn, pkt);
      case OpCode.NEW_TX_ANNOUNCE -> handleNewTx(conn, pkt);
      default -> log.debug("Unknown opcode: {}", op);
    }
  }

  // Проверяет статус синхронизации локальной цепи с максимальной высотой пиров
  public boolean isSynced() {
    return blockchainService.getLatestBlockHeader().height >= maxPeerHeight;
  }

  // Возвращает текущее количество активных подключений
  public int getConnectedPeersCount() {
    return connections.size();
  }

  private void startServer() {
    int port = nodeConfig.p2pPort;
    try {
      serverSocket = new ServerSocket(port);
      isRunning.set(true);
      serverExecutor = Executors.newFixedThreadPool(MAX_CONNECTIONS, daemonFactory("p2p-worker"));
      Thread acceptThread = new Thread(this::listen, "p2p-acceptor");
      acceptThread.setDaemon(true);
      acceptThread.start();
      log.info("P2P Binary Server listening on internal port {}", port);
    } catch (IOException err) {
      log.error("Failed to start P2P server on port {}: {}", port, err.getMessage());
    }
  }

  private void listen() {
    while (isRunning.get() && serverSocket != null && !serverSocket.isClosed()) {
      try {
        Socket socket = serverSocket.accept();
        if (!connectionSlots.tryAcquire()) {
          socket.close();
          log.warn("Rejected incoming P2P connection: limit of {} reached", MAX_CONNECTIONS);
          continue;
        }
        PeerConnection conn = new PeerConnection(socket, this);
        connections.add(conn);
        log.info("Registered incoming P2P peer connection: {}:{}", conn.getRemoteHost(), conn.getRemotePort());
        serverExecutor.execute(conn);
      } catch (IOException err) {
        if (isRunning.get()) log.warn("Error accepting P2P socket: {}", err.getMessage());
      }
    }
  }

  private void connectToSeedsAndKnownPeers() {
    String seeds = nodeConfig.peerSeeds;
    if (seeds != null && !seeds.trim().isEmpty()) {
      for (String seed : seeds.split(",")) {
        String trimmed = seed.trim();
        if (trimmed.isEmpty()) continue;
        String[] parts = trimmed.split(":");
        if (parts.length != 2 || !parts[1].trim().chars().allMatch(Character::isDigit)) continue;
        connectToPeer(parts[0].trim(), Integer.parseInt(parts[1].trim()));
      }
    }
    List<PeerInfo> peers = peerRepository.findAll();
    for (PeerInfo p : peers) {
      connectToPeer(p.host(), p.port());
    }
  }

  private HandshakeMessage buildHandshake(Block latest) {
    long now = System.currentTimeMillis();
    String pubKeyStr = CryptoUtil.encodePublicKey(nodeWalletService.publicKey);
    String dataToSign = nodeId + "|" + nodeConfig.chainId + "|" + latest.height + "|" + latest.hash + "|" + nodeConfig.p2pPort + "|" + now;
    String sig = CryptoUtil.sign(nodeWalletService.privateKey, dataToSign);
    return new HandshakeMessage(
      nodeId,
      nodeConfig.chainId,
      latest.height,
      latest.hash,
      nodeConfig.p2pPort,
      VERSION,
      now,
      pubKeyStr,
      sig
    );
  }

  private void sendHandshake(PeerConnection conn) {
    Block latest = blockchainService.getLatestBlockHeader();
    HandshakeMessage msg = buildHandshake(latest);
    NodePacket pkt = NodePacket.fromJson(OpCode.HANDSHAKE, 0, msg);
    if (pkt != null) conn.send(pkt);
  }

  private void sendHeartbeats() {
    NodePacket ping = new NodePacket(OpCode.PING, 0, new byte[0]);
    for (PeerConnection conn : connections) {
      conn.send(ping);
    }
  }

  private boolean isRateLimited(PeerConnection conn, byte op) {
    if (op == OpCode.PONG) return false;
    String key = conn.getRemoteHost() + ":" + conn.getRemotePort() + ":" + op;
    long now = System.currentTimeMillis();
    Long last = lastRequestTime.get(key);
    long minInterval = (op == OpCode.NEW_TX_ANNOUNCE || op == OpCode.BLOCKS_DATA || op == OpCode.GET_BLOCKS) ? 10L : MIN_REQUEST_INTERVAL_MS;
    if (last != null && (now - last) < minInterval) {
      log.warn("Rate limit exceeded for opcode {} from {}:{}", op, conn.getRemoteHost(), conn.getRemotePort());
      return true;
    }
    if (lastRequestTime.size() > 500) {
      pruneRateLimitMap(now);
    }
    lastRequestTime.put(key, now);
    return false;
  }

  private void pruneRateLimitMap(long now) {
    lastRequestTime.entrySet().removeIf(e -> (now - e.getValue()) > 60_000L);
  }

  private void handlePing(PeerConnection conn, NodePacket pkt) {
    conn.send(new NodePacket(OpCode.PONG, pkt.requestId, new byte[0]));
  }

  private void handlePong(PeerConnection conn) {
    if (conn.remoteNodeId == null || conn.remotePort <= 0) return;
    peerRepository.saveOrUpdate(new PeerInfo(conn.getRemoteHost(), conn.remotePort, System.currentTimeMillis()));
  }

  private void handleGetPeers(PeerConnection conn, NodePacket pkt) {
    List<PeerInfo> list = new ArrayList<>();
    for (PeerConnection c : connections) {
      if (c.remoteNodeId != null && c.remotePort > 0) {
        list.add(new PeerInfo(c.getRemoteHost(), c.remotePort, System.currentTimeMillis()));
      }
    }
    NodePacket p = NodePacket.fromJson(OpCode.PEERS_LIST, pkt.requestId, new PeersPayload(list));
    if (p != null) conn.send(p);
  }

  private void handlePeersList(NodePacket pkt) {
    PeersPayload payload = pkt.readJson(PeersPayload.class);
    if (payload == null || payload.peers() == null) return;
    int count = 0;
    for (PeerInfo p : payload.peers()) {
      if (count >= 20) break;
      if (p == null || p.port() <= 0 || p.port() > 65535 || p.host() == null) continue;
      peerRepository.saveOrUpdate(p);
      count++;
    }
  }

  private void handleGetBlocks(PeerConnection conn, NodePacket pkt) {
    GetBlocksRequest req = pkt.readJson(GetBlocksRequest.class);
    if (req == null) return;
    int limit = Math.min(100, Math.max(1, req.limit()));
    List<Block> blocks = blockchainService.getBlocksFrom(req.fromHeight(), limit);
    NodePacket p = NodePacket.fromJson(OpCode.BLOCKS_DATA, pkt.requestId, new BlocksPayload(blocks));
    if (p != null) conn.send(p);
  }

  private void handleBlocksData(PeerConnection conn, NodePacket pkt) {
    BlocksPayload payload = pkt.readJson(BlocksPayload.class);
    if (payload == null || payload.blocks() == null || payload.blocks().isEmpty()) return;

    List<Block> blocks = payload.blocks();
    Block localLatest = blockchainService.getLatestBlockHeader();
    boolean isAllApplied = applySequentialBlocks(blocks, localLatest);

    if (!isAllApplied) {
      boolean isReorgSuccess = blockchainService.reorganizeChain(blocks);
      if (isReorgSuccess) isAllApplied = true;
    }

    if (!isAllApplied) {
      long lowestReceived = blocks.get(0).height;
      if (lowestReceived > 1) {
        long stepBack = Math.max(1, lowestReceived - 20);
        GetBlocksRequest req = new GetBlocksRequest(stepBack, 100);
        NodePacket p = NodePacket.fromJson(OpCode.GET_BLOCKS, 0, req);
        if (p != null) conn.send(p);
      }
      return;
    }

    for (Block b : blocks) {
      maxPeerHeight = Math.max(maxPeerHeight, b.height);
    }
    if (blocks.size() == 100) {
      Block latest = blockchainService.getLatestBlockHeader();
      GetBlocksRequest nextReq = new GetBlocksRequest(latest.height + 1, 100);
      NodePacket p = NodePacket.fromJson(OpCode.GET_BLOCKS, 0, nextReq);
      if (p != null) conn.send(p);
    }
  }

  private boolean applySequentialBlocks(List<Block> blocks, Block localLatest) {
    if (blocks.get(0).height != localLatest.height + 1) return false;
    for (Block b : blocks) {
      if (!blockchainService.applyIncomingBlock(b)) return false;
    }
    return true;
  }

  private void handleNewBlock(PeerConnection conn, NodePacket pkt) {
    Block b = pkt.readJson(Block.class);
    if (b == null || b.hash == null) return;
    if (blockchainService.isBlockPresent(b.hash)) return;

    Block localLatest = blockchainService.getLatestBlockHeader();
    if (b.height == localLatest.height + 1 && b.previousHash.equals(localLatest.hash)) {
      boolean isApplied = blockchainService.applyIncomingBlock(b);
      if (isApplied) {
        maxPeerHeight = Math.max(maxPeerHeight, b.height);
        broadcast(OpCode.NEW_BLOCK_ANNOUNCE, b, conn);
      }
      return;
    }

    long reqFrom = b.height <= localLatest.height
      ? Math.max(0, b.height - 1)
      : (b.previousHash.equals(localLatest.hash) ? localLatest.height + 1 : Math.max(0, localLatest.height - 5));

    GetBlocksRequest req = new GetBlocksRequest(reqFrom, 100);
    NodePacket p = NodePacket.fromJson(OpCode.GET_BLOCKS, 0, req);
    if (p != null) conn.send(p);
  }

  private void handleNewTx(PeerConnection conn, NodePacket pkt) {
    Transaction tx = pkt.readJson(Transaction.class);
    if (tx == null) return;
    Result<Transaction> res = blockchainService.submitTransaction(tx, false);
    if (!res.isError()) broadcast(OpCode.NEW_TX_ANNOUNCE, tx, conn);
  }

  private void handleHandshake(PeerConnection conn, NodePacket pkt, boolean isAck) {
    HandshakeMessage msg = pkt.readJson(HandshakeMessage.class);
    long now = System.currentTimeMillis();
    if (!isValidHandshake(msg, new HandshakeContext(this.nodeId, nodeConfig.chainId, now))) {
      conn.close();
      return;
    }
    for (PeerConnection c : connections) {
      if (c != conn && msg.nodeId().equals(c.remoteNodeId) && c.isConnected()) {
        conn.close();
        return;
      }
    }

    maxPeerHeight = Math.max(maxPeerHeight, msg.height());
    conn.remoteNodeId = msg.nodeId();
    if (msg.p2pPort() > 0) {
      conn.remotePort = msg.p2pPort();
      peerRepository.saveOrUpdate(new PeerInfo(conn.getRemoteHost(), msg.p2pPort(), now));
    }

    if (!isAck) {
      Block latest = blockchainService.getLatestBlockHeader();
      HandshakeMessage ack = buildHandshake(latest);
      NodePacket ackPkt = NodePacket.fromJson(OpCode.HANDSHAKE_ACK, pkt.requestId, ack);
      if (ackPkt != null) conn.send(ackPkt);
      conn.send(new NodePacket(OpCode.GET_PEERS, 0, new byte[0]));
    } else {
      conn.send(new NodePacket(OpCode.GET_PEERS, 0, new byte[0]));
    }

    Block local = blockchainService.getLatestBlockHeader();
    if (msg.height() > local.height) {
      long fromHeight = (local.height > 0) ? Math.max(1, local.height - 5) : 1;
      GetBlocksRequest req = new GetBlocksRequest(fromHeight, 100);
      NodePacket p = NodePacket.fromJson(OpCode.GET_BLOCKS, 0, req);
      if (p != null) conn.send(p);
    }
  }

  public record HandshakeContext(String localNodeId, String chainId, long now) {}

  public record HandshakeMessage(
    String nodeId,
    String chainId,
    long height,
    String latestBlockHash,
    int p2pPort,
    String version,
    long timestamp,
    String publicKey,
    String signature
  ) implements Serializable {
    public String toSignableData() {
      return nodeId + "|" + chainId + "|" + height + "|" + latestBlockHash + "|" + p2pPort + "|" + timestamp;
    }
  }

  public record BlocksPayload(List<Block> blocks) {}
  public record GetBlocksRequest(long fromHeight, int limit) {}
  public record PeersPayload(List<PeerInfo> peers) {}
}
