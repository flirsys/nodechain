package so.fox.nodechain.p2p;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.Serializable;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import so.fox.nodechain.config.NodeConfig;
import so.fox.nodechain.core.BlockchainService;
import so.fox.nodechain.core.ConsensusUtil;
import so.fox.nodechain.core.NodeWalletService;
import so.fox.nodechain.core.Result;
import so.fox.nodechain.crypto.CryptoUtil;
import so.fox.nodechain.model.Block;
import so.fox.nodechain.model.PeerInfo;
import so.fox.nodechain.model.Transaction;
import so.fox.nodechain.p2p.packet.NodePacket;
import so.fox.nodechain.p2p.packet.OpCode;
import so.fox.nodechain.repository.PeerRepository;

// P2P сетевой сервис узла: сетевые подключения, gossip-протокол и синхронизация цепи.
@Service
public class P2pClientManager {

  public static final String VERSION = "1.0.0";
  public static final int MAX_CONNECTIONS = 64;
  public static final int MAX_INBOUND_CONNECTIONS = 48;
  public static final int MAX_BLOCKS_PER_PACKET = 10;
  public static final int MAX_TXS_PER_SYNC_BLOCK = 500;
  private static final Logger log = LoggerFactory.getLogger(P2pClientManager.class);
  private static final long MIN_REQUEST_INTERVAL_MS = 100L;
  private static final Pattern HEX_64_PATTERN = Pattern.compile("^[0-9a-fA-F]{64}$");
  private static final Pattern LOOPBACK_V4_PATTERN =
    Pattern.compile("^127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$");
  private static final Pattern IP_LITERAL_PATTERN =
    Pattern.compile("^(\\d{1,3}\\.){3}\\d{1,3}$|^([0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}$");

  public record HandshakeContext(String localNodeId, String chainId, long now, Map<String, Long> seenNonces) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record HandshakeMessage(
    String nodeId,
    String chainId,
    long height,
    String latestBlockHash,
    int p2pPort,
    String version,
    long timestamp,
    String publicKey,
    String signature,
    String sessionNonce
  ) implements Serializable {
    // Формирует каноническую строку для подписи рукопожатия.
    public String toSignableData() {
      return nodeId + "|" + chainId + "|" + height + "|" + latestBlockHash + "|" + p2pPort + "|" + timestamp + "|" + sessionNonce;
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record BlocksPayload(List<Block> blocks) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record GetBlocksRequest(long fromHeight, int limit) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PeersPayload(List<PeerInfo> peers) {}

  // Валидирует криптографическое рукопожатие сетевого узла с обязательной цифровой подписью.
  public static boolean isValidHandshake(HandshakeMessage msg, HandshakeContext ctx) {
    if (msg == null || ctx == null || ctx.chainId() == null || !ctx.chainId().equals(msg.chainId())) return false;
    if (msg.nodeId() == null || msg.nodeId().isBlank() || msg.nodeId().equalsIgnoreCase(ctx.localNodeId())) return false;
    if (msg.version() == null || msg.version().isBlank() || msg.version().length() > 32 || msg.version().contains("|")) return false;
    if (msg.height() < 0 || msg.p2pPort() <= 0 || msg.p2pPort() > 65535) return false;
    if (msg.latestBlockHash() == null || !HEX_64_PATTERN.matcher(msg.latestBlockHash()).matches()) return false;
    if (msg.timestamp() <= 0 || ctx.now() < (msg.timestamp() - 60_000L) || ctx.now() > (msg.timestamp() + 120_000L)) return false;
    if (msg.sessionNonce() == null || msg.sessionNonce().isBlank() || msg.sessionNonce().length() > 64 || msg.sessionNonce().contains("|")) return false;
    if (msg.publicKey() == null || msg.publicKey().isBlank()) return false;
    if (msg.signature() == null || msg.signature().isBlank()) return false;

    PublicKey pubKey = CryptoUtil.parsePublicKey(msg.publicKey());
    if (pubKey == null) return false;

    String derivedAddr = CryptoUtil.getAddressFromPublicKey(pubKey);
    if (!msg.nodeId().equalsIgnoreCase(derivedAddr)) return false;

    if (!CryptoUtil.isSignatureValid(pubKey, msg.toSignableData(), msg.signature())) return false;
    if (ctx.seenNonces() == null) return false;

    if (ctx.seenNonces().size() >= 10_000) {
      long cutoff = ctx.now() - 180_000L;
      ctx.seenNonces().entrySet().removeIf(e -> e.getValue() < cutoff);
      if (ctx.seenNonces().size() >= 10_000) return false;
    }
    if (ctx.seenNonces().putIfAbsent(msg.sessionNonce(), ctx.now()) != null) return false;

    return true;
  }

  // Проверяет адрес и порт пира на безопасность от SSRF и сканирования приватных сетей.
  public static boolean isSafePeerAddress(String host, int port, NodeConfig cfg) {
    return resolveSafePeerAddress(host, port, cfg) != null;
  }

  // Разрешает и валидирует безопасный IP-адрес пира, возвращает null при угрозе SSRF.
  public static InetAddress resolveSafePeerAddress(String host, int port, NodeConfig cfg) {
    if (host == null || host.isBlank() || port < 1024 || port > 65535) return null;
    if (isSensitivePort(port, cfg)) return null;

    try {
      InetAddress addr = InetAddress.getByName(host.trim());
      boolean isLoopback = addr.isLoopbackAddress() || isLocalAddress(addr.getHostAddress());
      if (isLoopback) {
        if (cfg != null && (port == cfg.p2pPort || port == cfg.serverPort)) return null;
        return addr;
      }

      if (addr.isAnyLocalAddress()
          || addr.isLinkLocalAddress()
          || addr.isSiteLocalAddress()
          || addr.isMulticastAddress()
          || isBlockedIpv6(addr)
          || isPrivateSubnet(addr.getHostAddress())) {
        return null;
      }
      return addr;
    } catch (Exception err) {
      return null;
    }
  }

  // Проверяет, является ли IP локальным адресом хоста.
  public static boolean isLocalAddress(String host) {
    if (host == null) return false;
    String h = host.startsWith("::ffff:") ? host.substring(7) : host;
    return "127.0.0.1".equals(h) || "localhost".equalsIgnoreCase(h)
      || "0:0:0:0:0:0:0:1".equals(h) || "::1".equals(h) || LOOPBACK_V4_PATTERN.matcher(h).matches();
  }

  // Проверяет, входит ли IP в диапазон приватных подсетей (RFC 1918 / Loopback / CGNAT / IETF / Class E)
  private static boolean isPrivateSubnet(String ip) {
    if (ip == null) return false;
    String cleanIp = ip.startsWith("::ffff:") ? ip.substring(7) : ip;
    if (cleanIp.startsWith("::ffff:0:")) cleanIp = cleanIp.substring(9);
    if (cleanIp.startsWith("0.") || cleanIp.startsWith("10.") || cleanIp.startsWith("192.168.") || cleanIp.startsWith("127.")
        || cleanIp.startsWith("169.254.") || cleanIp.startsWith("fc") || cleanIp.startsWith("fd")
        || cleanIp.startsWith("fe80:") || cleanIp.equals("255.255.255.255")) {
      return true;
    }
    String[] parts = cleanIp.split("\\.");
    if (parts.length >= 2) {
      try {
        int first = Integer.parseInt(parts[0]);
        int second = Integer.parseInt(parts[1]);
        int third = parts.length >= 3 ? Integer.parseInt(parts[2]) : -1;
        if (first >= 240) return true; // 240.0.0.0/4 (Class E)
        if (first == 172 && second >= 16 && second <= 31) return true; // RFC 1918
        if (first == 100 && second >= 64 && second <= 127) return true; // RFC 6598 CGNAT
        if (first == 192 && second == 0 && (third == 0 || third == 2)) return true; // 192.0.0.0/24, 192.0.2.0/24
        if (first == 198 && (second == 18 || second == 19 || (second == 51 && third == 100))) return true; // 198.18.0.0/15, 198.51.100.0/24
        if (first == 203 && second == 0 && third == 113) return true; // 203.0.113.0/24
      } catch (NumberFormatException ignored) {}
    }
    return false;
  }

  // Проверяет адрес IPv6 на принадлежность опасным спец-диапазонам (IPv4-compatible, NAT64, ULA, 6to4, Teredo, Discard, Benchmarking, IPv4-translatable)
  private static boolean isBlockedIpv6(InetAddress addr) {
    if (!(addr instanceof Inet6Address)) return false;
    byte[] b = addr.getAddress();
    if (b == null || b.length != 16) return false;
    if ((b[0] & 0xFE) == (byte) 0xFC) return true; // ULA
    if ((b[0] & 0xFF) == 0x20 && (b[1] & 0xFF) == 0x02) return true; // 6to4
    // 2001::/23 (включая 2001:0000::/32 Teredo, 2001:0002::/48 Benchmarking, 2001:0020::/28 ORCHIDv2)
    if ((b[0] & 0xFF) == 0x20 && (b[1] & 0xFF) == 0x01 && ((b[2] & 0xFE) == 0x00 || (b[2] & 0xF0) == 0x00 || (b[2] == 0 && (b[3] & 0xF0) == 0x20))) return true;
    if ((b[0] & 0xFF) == 0x20 && (b[1] & 0xFF) == 0x01 && (b[2] & 0xFF) == 0x0D && (b[3] & 0xFF) == 0xB8) return true; // Documentation
    if (b[0] == 0 && b[1] == 0x64 && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0x9B) return true; // NAT64
    // 100::/64 (Discard-Only, RFC 6666)
    if ((b[0] & 0xFF) == 0x01 && b[1] == 0 && b[2] == 0 && b[3] == 0 && b[4] == 0 && b[5] == 0 && b[6] == 0 && b[7] == 0) return true;
    if (addr.isLoopbackAddress()) return false;
    // ::ffff:0:0/96 (IPv4-translatable SIIT, RFC 2765)
    boolean isTranslatablePrefix = true;
    for (int i = 0; i < 8; i++) {
      if (b[i] != 0) {
        isTranslatablePrefix = false;
        break;
      }
    }
    if (isTranslatablePrefix && b[8] == (byte) 0xFF && b[9] == (byte) 0xFF && b[10] == 0 && b[11] == 0) return true;
    // IPv4-compatible
    for (int i = 0; i < 10; i++) {
      if (b[i] != 0) return false;
    }
    return true;
  }

  // Проверяет, относится ли порт к системным или критическим сервисам
  private static boolean isSensitivePort(int port, NodeConfig cfg) {
    if (cfg != null && (port == cfg.p2pPort || port == cfg.serverPort)) return true;
    return port == 22 || port == 23 || port == 25 || port == 80 || port == 443
        || port == 3306 || port == 5432 || port == 6379 || port == 27017 || port == 11211 || port == 8080;
  }

  // Находит индекс блока в списке, совпадающего по высоте и хешу с локальной вершиной
  private static int findMatchingLocalBlockIndex(List<Block> blocks, Block localLatest) {
    if (blocks == null || localLatest == null) return -1;
    for (int i = 0; i < blocks.size(); i++) {
      Block b = blocks.get(i);
      if (b.height == localLatest.height && b.hash.equals(localLatest.hash)) return i;
    }
    return -1;
  }

  // Отвечает на PING отправкой PONG с высотой ноды и сохранением requestId
  private void handlePing(PeerConnection conn, NodePacket pkt) {
    // Высота принимается только от пира после рукопожатия и с разумным потолком
    if (conn.remoteNodeId != null && pkt.payload != null && pkt.payload.length >= 8) {
      long remoteH = ByteBuffer.wrap(pkt.payload).getLong();
      if (remoteH > 0 && remoteH < 1_000_000_000L) conn.remoteHeight = Math.max(conn.remoteHeight, remoteH);
    }
    long localH = blockchainService != null ? blockchainService.getLatestBlockHeader().height : 0L;
    byte[] resp = ByteBuffer.allocate(8).putLong(localH).array();
    conn.send(new NodePacket(OpCode.PONG, pkt.requestId, resp));
  }

  private final NodeConfig nodeConfig;
  private final PeerRepository peerRepository;
  private final BlockchainService blockchainService;
  private final NodeWalletService nodeWalletService;

  private final List<PeerConnection> connections = new CopyOnWriteArrayList<>();
  private final Set<PeerInfo> candidatePeers = ConcurrentHashMap.newKeySet();
  private final Semaphore connectionSlots = new Semaphore(MAX_CONNECTIONS);
  private final Map<String, Long> lastRequestTime = new ConcurrentHashMap<>();
  private final Map<String, Long> seenHandshakeNonces = new ConcurrentHashMap<>();
  private final Map<PeerConnection, Integer> peerStepBackCount = new ConcurrentHashMap<>();
  private final AtomicBoolean isRunning = new AtomicBoolean(false);
  private ExecutorService broadcastExecutor;

  private volatile String nodeId = UUID.randomUUID().toString();
  private ServerSocket serverSocket;
  private ExecutorService serverExecutor;
  private ScheduledExecutorService scheduler;

  // Конструктор менеджера P2P соединений узла.
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
    if (nodeWalletService != null && nodeWalletService.address != null && !nodeWalletService.address.isEmpty()) {
      this.nodeId = nodeWalletService.address;
    }
  }

  // Запускает TCP-сервер, циклы синхронизации и heartbeats.
  @PostConstruct
  public void start() {
    if (isRunning.get()) return;
    if (nodeWalletService != null && nodeWalletService.address != null && !nodeWalletService.address.isEmpty()) {
      this.nodeId = nodeWalletService.address;
    }
    if (broadcastExecutor == null || broadcastExecutor.isShutdown()) {
      broadcastExecutor = Executors.newFixedThreadPool(4, ConsensusUtil.daemonFactory("p2p-broadcast"));
    }
    startServer();

    if (scheduler != null) scheduler.shutdownNow();
    scheduler = Executors.newScheduledThreadPool(2, ConsensusUtil.daemonFactory("p2p-client-manager"));
    scheduler.schedule(this::connectToSeedsAndKnownPeers, 1, TimeUnit.SECONDS);
    scheduler.scheduleWithFixedDelay(this::connectToSeedsAndKnownPeers, 30, 30, TimeUnit.SECONDS);
    scheduler.scheduleWithFixedDelay(this::sendHeartbeats, 20, 20, TimeUnit.SECONDS);
  }

  // Останавливает сервер и закрывает все сетевые соединения.
  @PreDestroy
  public void stop() {
    isRunning.set(false);
    try {
      if (serverSocket != null) serverSocket.close();
    } catch (IOException ignored) {}
    if (serverExecutor != null) serverExecutor.shutdownNow();
    if (broadcastExecutor != null) {
      broadcastExecutor.shutdownNow();
      broadcastExecutor = null;
    }
    if (scheduler != null) scheduler.shutdownNow();
    for (PeerConnection conn : connections) {
      conn.close();
    }
    connections.clear();
    connectionSlots.drainPermits();
    connectionSlots.release(MAX_CONNECTIONS);
  }

  // Обрабатывает событие отключения пира и освобождает слот соединения.
  public void onDisconnected(PeerConnection conn) {
    if (conn == null) return;
    if (connections.remove(conn)) connectionSlots.release();
    peerStepBackCount.remove(conn);
    log.info("P2P peer disconnected: {}:{}", conn.getRemoteHost(), conn.getRemotePort());
  }

  // Инициирует исходящее TCP-соединение с удаленным пиром с атомарным резервированием слота.
  public boolean connectToPeer(String host, int port) {
    InetAddress addr = resolveSafePeerAddress(host, port, nodeConfig);
    if (addr == null) return false;
    String targetIp = addr.getHostAddress();
    int sameIpCount = 0;
    for (PeerConnection conn : connections) {
      if (!conn.isConnected()) continue;
      if (conn.getRemoteHost().equals(targetIp)) {
        if (conn.getRemotePort() == port) return false;
        sameIpCount++;
      }
    }
    if (!isLocalAddress(targetIp) && sameIpCount >= 2) return false;
    if (!connectionSlots.tryAcquire()) return false;

    Socket socket = new Socket();
    PeerConnection conn = null;
    try {
      socket.connect(new InetSocketAddress(addr, port), 5000);
      conn = new PeerConnection(socket, this, true);
      conn.remotePort = port;
      connections.add(conn);

      Thread t = new Thread(conn, "peer-" + host + ":" + port);
      t.setDaemon(true);
      t.start();
      sendHandshakePacket(conn, OpCode.HANDSHAKE, 0);
      return true;
    } catch (IOException err) {
      connectionSlots.release();
      if (peerRepository != null) peerRepository.delete(targetIp, port);
      candidatePeers.remove(new PeerInfo(targetIp, port, 0L));
      if (conn != null) conn.close();
      try {
        socket.close();
      } catch (IOException ignored) {}
      log.debug("Could not connect to peer {}:{}: {}", host, port, err.getMessage());
      return false;
    }
  }

  // Универсальная неблокирующая рассылка сетевого пакета всем активным узлам (Gossip).
  public void broadcast(byte opCode, Object payload, PeerConnection except) {
    if (payload == null) return;
    ExecutorService exec = this.broadcastExecutor;
    if (exec == null || exec.isShutdown()) return;
    try {
      exec.execute(() -> {
        NodePacket pkt = NodePacket.fromJson(opCode, 0, payload);
        if (pkt == null) return;
        for (PeerConnection conn : connections) {
          if (conn != except && conn.isConnected() && conn.remoteNodeId != null) conn.send(pkt);
        }
      });
    } catch (RejectedExecutionException ignored) {}
  }

  // Обрабатывает входящий пакет сетевого протокола от пира.
  public void handlePacket(PeerConnection conn, NodePacket pkt) {
    if (conn == null || pkt == null) return;
    byte op = pkt.opCode;
    if (!OpCode.isValid(op)) {
      conn.close();
      return;
    }
    if (op != OpCode.HANDSHAKE && op != OpCode.HANDSHAKE_ACK && conn.remoteNodeId == null) {
      conn.close();
      return;
    }
    if (isRateLimited(conn, op)) {
      if (conn.remoteNodeId == null) conn.close();
      return;
    }

    switch (op) {
      case OpCode.HANDSHAKE -> handleHandshake(conn, pkt, false);
      case OpCode.HANDSHAKE_ACK -> handleHandshake(conn, pkt, true);
      case OpCode.PING -> handlePing(conn, pkt);
      case OpCode.PONG -> handlePong(conn, pkt);
      case OpCode.GET_PEERS -> handleGetPeers(conn, pkt);
      case OpCode.PEERS_LIST -> handlePeersList(conn, pkt);
      case OpCode.GET_BLOCKS -> handleGetBlocks(conn, pkt);
      case OpCode.BLOCKS_DATA -> handleBlocksData(conn, pkt);
      case OpCode.NEW_BLOCK_ANNOUNCE -> handleNewBlock(conn, pkt);
      case OpCode.NEW_TX_ANNOUNCE -> handleNewTx(conn, pkt);
      default -> log.debug("Unknown opcode: {}", op);
    }
  }

  // Проверяет статус синхронизации локальной цепи с максимальной высотой пиров.
  public boolean isSynced() {
    long localHeight = blockchainService.getLatestBlockHeader().height;
    for (PeerConnection c : connections) {
      if (c.isConnected() && c.remoteNodeId != null && c.remoteHeight > localHeight) return false;
    }
    return true;
  }

  // Возвращает текущее количество активных подключений.
  public int getConnectedPeersCount() {
    return (int) connections.stream().filter(c -> c.isConnected() && c.remoteNodeId != null).count();
  }

  private void startServer() {
    int port = nodeConfig.p2pPort;
    try {
      serverSocket = new ServerSocket(port);
      isRunning.set(true);
      if (serverExecutor != null) serverExecutor.shutdownNow();
      serverExecutor = Executors.newFixedThreadPool(MAX_CONNECTIONS, ConsensusUtil.daemonFactory("p2p-worker"));
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
        String remoteIp = socket.getInetAddress() != null ? socket.getInetAddress().getHostAddress() : "";
        if (!isLocalAddress(remoteIp)) {
          long ipConns = connections.stream().filter(c -> c.isConnected() && c.getRemoteHost().equals(remoteIp)).count();
          if (ipConns >= 2) {
            socket.close();
            log.warn("Rejected incoming P2P connection from {}: limit per IP reached", remoteIp);
            continue;
          }
        }
        long inboundCount = connections.stream().filter(c -> c.isConnected() && !c.isOutbound).count();
        if (inboundCount >= MAX_INBOUND_CONNECTIONS) {
          socket.close();
          log.warn("Rejected incoming P2P connection: inbound quota of {} reached", MAX_INBOUND_CONNECTIONS);
          continue;
        }
        if (!connectionSlots.tryAcquire()) {
          socket.close();
          log.warn("Rejected incoming P2P connection: limit of {} reached", MAX_CONNECTIONS);
          continue;
        }
        PeerConnection conn = null;
        try {
          conn = new PeerConnection(socket, this, false);
          connections.add(conn);
          log.info("Registered incoming P2P peer connection: {}:{}", conn.getRemoteHost(), conn.getRemotePort());
          serverExecutor.execute(conn);
        } catch (Exception err) {
          if (conn != null) {
            connections.remove(conn);
            conn.close();
          } else {
            try {
              socket.close();
            } catch (IOException ignored) {}
          }
          connectionSlots.release();
          throw err instanceof IOException ioEx ? ioEx : new IOException(err);
        }
      } catch (IOException err) {
        if (isRunning.get()) log.warn("Error accepting P2P socket: {}", err.getMessage());
      }
    }
  }

  private void connectToSeedsAndKnownPeers() {
    try {
      String seeds = nodeConfig.peerSeeds;
      if (seeds != null && !seeds.trim().isEmpty()) {
        for (String seed : seeds.split(",")) {
          String[] parts = seed.trim().split(":");
          if (parts.length != 2) continue;
          String portStr = parts[1].trim();
          if (!portStr.matches("^\\d{1,5}$")) continue;
          connectToPeer(parts[0].trim(), Integer.parseInt(portStr));
        }
      }
      List<PeerInfo> peers = peerRepository != null ? peerRepository.findAll() : List.of();
      int attempted = 0;
      for (PeerInfo p : peers) {
        if (attempted >= 32) break;
        if (connectToPeer(p.host(), p.port())) {
          attempted++;
        }
      }
      connectToCandidates();
    } catch (Exception err) {
      log.warn("Error in peer discovery task: {}", err.getMessage());
    }
  }

  // Подключается к обнаруженным кандидатам из gossip-списка без сохранения в БД до рукопожатия.
  private void connectToCandidates() {
    if (candidatePeers.isEmpty()) return;
    int attempted = 0;
    for (PeerInfo p : candidatePeers) {
      if (attempted >= 16 || getConnectedPeersCount() >= MAX_CONNECTIONS) break;
      if (!candidatePeers.remove(p)) continue;
      if (connectToPeer(p.host(), p.port())) {
        attempted++;
      }
    }
  }

  private HandshakeMessage buildHandshake(Block latest) {
    if (latest == null || nodeWalletService == null || nodeWalletService.publicKey == null || nodeWalletService.privateKey == null) return null;
    long now = System.currentTimeMillis();
    String pubKeyStr = CryptoUtil.encodePublicKey(nodeWalletService.publicKey);
    String sessionNonce = UUID.randomUUID().toString();
    String dataToSign = nodeId + "|" + nodeConfig.chainId + "|" + latest.height + "|" + latest.hash + "|" + nodeConfig.p2pPort + "|" + now + "|" + sessionNonce;
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
      sig,
      sessionNonce
    );
  }

  private void sendHandshakePacket(PeerConnection conn, byte opCode, int requestId) {
    if (blockchainService == null) return;
    Block latest = blockchainService.getLatestBlockHeader();
    HandshakeMessage msg = buildHandshake(latest);
    if (msg == null) return;
    NodePacket pkt = NodePacket.fromJson(opCode, requestId, msg);
    if (pkt != null) conn.send(pkt);
  }

  private void sendHeartbeats() {
    long now = System.currentTimeMillis();
    pruneRateLimitMap(now);
    long localH = blockchainService != null ? blockchainService.getLatestBlockHeader().height : 0L;
    byte[] pingPayload = ByteBuffer.allocate(8).putLong(localH).array();
    NodePacket ping = new NodePacket(OpCode.PING, 0, pingPayload);
    for (PeerConnection conn : connections) {
      if (conn.remoteNodeId == null) {
        if (now - conn.createdAt > 5_000L) conn.close();
        continue;
      }
      if (conn.writeStartTime > 0L && (now - conn.writeStartTime > 15_000L)) {
        log.warn("Peer connection {} write stalled (>15s), closing", conn.getRemoteHost());
        conn.close();
        continue;
      }
      conn.send(ping);
      if (conn.remoteHeight > localH) {
        long reqFrom = Math.max(localH > 0 ? 1 : 0, localH - 5);
        NodePacket p = NodePacket.fromJson(OpCode.GET_BLOCKS, 0, new GetBlocksRequest(reqFrom, 20));
        if (p != null) conn.send(p);
      }
    }
  }

  private boolean isRateLimited(PeerConnection conn, byte op) {
    if (op == OpCode.PONG) return false;
    String host = conn.getRemoteHost();
    if (isLocalAddress(host)) return false;

    long now = System.currentTimeMillis();
    long minInterval = (op == OpCode.NEW_TX_ANNOUNCE || op == OpCode.BLOCKS_DATA || op == OpCode.GET_BLOCKS) ? 10L : MIN_REQUEST_INTERVAL_MS;
    String key = host + ":" + op;

    if (lastRequestTime.size() >= 500) {
      pruneRateLimitMap(now);
      if (lastRequestTime.size() >= 500 && !lastRequestTime.containsKey(key)) {
        log.warn("Rate limit table full, rejecting opcode {} from {}", op, host);
        return true;
      }
    }

    AtomicBoolean isLimited = new AtomicBoolean(false);
    lastRequestTime.compute(key, (k, last) -> {
      if (last != null && (now - last) < minInterval) {
        isLimited.set(true);
        return last;
      }
      return now;
    });

    if (isLimited.get()) {
      log.warn("Rate limit exceeded for opcode {} from {}", op, host);
      return true;
    }
    return false;
  }

  private void pruneRateLimitMap(long now) {
    lastRequestTime.entrySet().removeIf(e -> (now - e.getValue()) > 60_000L);
    seenHandshakeNonces.entrySet().removeIf(e -> (now - e.getValue()) > 180_000L);
  }

  private void handlePong(PeerConnection conn, NodePacket pkt) {
    if (conn.remoteNodeId == null || conn.remotePort <= 0) return;
    // Высота принимается только от пира после рукопожатия и с разумным потолком
    if (conn.remoteNodeId != null && pkt.payload != null && pkt.payload.length >= 8) {
      long remoteH = ByteBuffer.wrap(pkt.payload).getLong();
      if (remoteH > 0 && remoteH < 1_000_000_000L) conn.remoteHeight = Math.max(conn.remoteHeight, remoteH);
    }
    if (peerRepository != null) {
      peerRepository.saveOrUpdate(new PeerInfo(conn.getRemoteHost(), conn.remotePort, System.currentTimeMillis()));
    }
  }

  private void handleGetPeers(PeerConnection conn, NodePacket pkt) {
    List<PeerInfo> list = new ArrayList<>();
    for (PeerConnection c : connections) {
      if (c.isConnected() && c.remoteNodeId != null && c.remotePort > 0) {
        list.add(new PeerInfo(c.getRemoteHost(), c.remotePort, System.currentTimeMillis()));
      }
    }
    NodePacket p = NodePacket.fromJson(OpCode.PEERS_LIST, pkt.requestId, new PeersPayload(list));
    if (p != null) conn.send(p);
  }

  private void handlePeersList(PeerConnection conn, NodePacket pkt) {
    if (peerRepository == null) return;
    PeersPayload payload = pkt.readJson(PeersPayload.class);
    if (payload == null || payload.peers() == null) return;
    if (candidatePeers.size() >= 256) return;
    boolean isRemotePeerLocal = isLocalAddress(conn.getRemoteHost());
    int count = 0;
    for (PeerInfo p : payload.peers()) {
      if (count >= 20 || candidatePeers.size() >= 256) break;
      if (p == null || p.host() == null) continue;
      String h = p.host().trim();
      if (!IP_LITERAL_PATTERN.matcher(h).matches()) continue;
      InetAddress addr = resolveSafePeerAddress(h, p.port(), nodeConfig);
      if (addr == null) continue;
      boolean isLoopback = addr.isLoopbackAddress() || isLocalAddress(addr.getHostAddress());
      if (isLoopback && !isRemotePeerLocal) continue;
      PeerInfo candidate = new PeerInfo(addr.getHostAddress(), p.port(), 0L);
      if (candidatePeers.add(candidate)) {
        count++;
      }
    }
    if (count > 0 && scheduler != null && !scheduler.isShutdown()) {
      scheduler.execute(this::connectToCandidates);
    }
  }

  private void handleGetBlocks(PeerConnection conn, NodePacket pkt) {
    GetBlocksRequest req = pkt.readJson(GetBlocksRequest.class);
    if (req == null) return;
    int limit = Math.min(MAX_BLOCKS_PER_PACKET, Math.max(1, req.limit()));
    List<Block> blocks = blockchainService.getBlocksFrom(Math.max(0L, req.fromHeight()), limit);
    if (blocks.isEmpty()) return;
    NodePacket p = NodePacket.fromJson(OpCode.BLOCKS_DATA, pkt.requestId, new BlocksPayload(blocks));
    if (p != null) conn.send(p);
  }

  private void requestNextBlocksIfFullBatch(PeerConnection conn, int batchSize) {
    if (batchSize == MAX_BLOCKS_PER_PACKET) {
      long nextHeight = blockchainService.getLatestBlockHeader().height + 1;
      NodePacket p = NodePacket.fromJson(OpCode.GET_BLOCKS, 0, new GetBlocksRequest(nextHeight, MAX_BLOCKS_PER_PACKET));
      if (p != null) conn.send(p);
    }
  }

  private void handleBlocksData(PeerConnection conn, NodePacket pkt) {
    BlocksPayload payload = pkt.readJson(BlocksPayload.class);
    if (payload == null || payload.blocks() == null || payload.blocks().isEmpty()) return;
    if (payload.blocks().size() > MAX_BLOCKS_PER_PACKET) {
      log.warn("Peer {} sent oversized block batch ({}), closing", conn.getRemoteHost(), payload.blocks().size());
      conn.close();
      return;
    }
    int totalTxs = 0;
    for (Block blk : payload.blocks()) {
      if (blk == null || blk.hash == null || blk.previousHash == null) return;
      if (blk.transactions != null) {
        if (blk.transactions.size() > MAX_TXS_PER_SYNC_BLOCK) {
          log.warn("Peer {} sent block with too many transactions ({}), closing", conn.getRemoteHost(), blk.transactions.size());
          conn.close();
          return;
        }
        totalTxs += blk.transactions.size();
      }
    }
    if (!conn.consumeTxBudget(totalTxs)) {
      log.warn("Peer {} exceeded transaction budget ({} txs), closing", conn.getRemoteHost(), totalTxs);
      conn.close();
      return;
    }

    List<Block> blocks = payload.blocks();
    Block localLatest = blockchainService.getLatestBlockHeader();

    int matchIdx = findMatchingLocalBlockIndex(blocks, localLatest);
    if (matchIdx >= 0) {
      if (matchIdx + 1 >= blocks.size()) {
        conn.remoteHeight = Math.max(conn.remoteHeight, localLatest.height);
        requestNextBlocksIfFullBatch(conn, payload.blocks().size());
        return;
      }
      blocks = blocks.subList(matchIdx + 1, blocks.size());
    } else if (localLatest.height == 0 && blocks.get(0).height == 0) {
      if (blocks.size() <= 1 || !blockchainService.adoptPeerGenesisChain(blocks)) {
        log.warn("Rejected peer genesis chain from {}:{}", conn.getRemoteHost(), conn.getRemotePort());
        return;
      }
      Block latest = blockchainService.getLatestBlockHeader();
      conn.remoteHeight = Math.max(conn.remoteHeight, latest.height);
      requestNextBlocksIfFullBatch(conn, payload.blocks().size());
      return;
    }

    boolean isDirectExtension = matchIdx >= 0
      || (blocks.get(0).height == localLatest.height + 1 && blocks.get(0).previousHash.equals(localLatest.hash));

    if (isDirectExtension) {
      if (!applySequentialBlocks(blocks, localLatest)) return;
      Block lastApplied = blocks.get(blocks.size() - 1);
      broadcast(OpCode.NEW_BLOCK_ANNOUNCE, lastApplied, conn);
    } else if (!blockchainService.reorganizeChain(blocks)) {
      if (blocks.get(0).height > 1 && !blockchainService.isBlockPresent(blocks.get(0).previousHash)) {
        handleSyncStepBack(conn, blocks);
      }
      return;
    } else {
      Block lastApplied = blocks.get(blocks.size() - 1);
      broadcast(OpCode.NEW_BLOCK_ANNOUNCE, lastApplied, conn);
    }

    peerStepBackCount.remove(conn);
    for (Block b : blocks) conn.remoteHeight = Math.max(conn.remoteHeight, b.height);
    requestNextBlocksIfFullBatch(conn, payload.blocks().size());
  }

  private void handleSyncStepBack(PeerConnection conn, List<Block> blocks) {
    int stepBacks = peerStepBackCount.merge(conn, 1, Integer::sum);
    if (stepBacks > 10) {
      log.warn("Peer {} exceeded step-back limit, closing connection", conn.getRemoteHost());
      conn.close();
      peerStepBackCount.remove(conn);
      return;
    }
    long lowestReceived = blocks.get(0).height;
    if (lowestReceived > 1) {
      long stepBack = Math.max(1, lowestReceived - 20);
      NodePacket p = NodePacket.fromJson(OpCode.GET_BLOCKS, 0, new GetBlocksRequest(stepBack, 100));
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
    if (!ConsensusUtil.isValidAnnouncedBlock(b, nodeConfig)) return;
    if (blockchainService.isBlockPresent(b.hash)) return;
    Block localLatest = blockchainService.getLatestBlockHeader();
    if (b.height == localLatest.height + 1 && b.previousHash.equals(localLatest.hash)) {
      boolean isApplied = blockchainService.applyIncomingBlock(b);
      if (isApplied) {
        conn.remoteHeight = Math.max(conn.remoteHeight, b.height);
        broadcast(OpCode.NEW_BLOCK_ANNOUNCE, b, conn);
      }
      return;
    }
    if (localLatest.height - b.height >= BlockchainService.MAX_REORG_DEPTH) return;
    long minHeight = localLatest.height > 0 ? 1L : 0L;
    long reqFrom = b.height <= localLatest.height
      ? Math.max(minHeight, b.height - 1)
      : Math.max(minHeight, localLatest.height - 5);
    GetBlocksRequest req = new GetBlocksRequest(reqFrom, 100);
    NodePacket p = NodePacket.fromJson(OpCode.GET_BLOCKS, 0, req);
    if (p != null) conn.send(p);
  }

  private void handleNewTx(PeerConnection conn, NodePacket pkt) {
    if (!conn.consumeTxBudget(1)) {
      log.warn("Peer {} exceeded transaction budget on announce, closing", conn.getRemoteHost());
      conn.close();
      return;
    }
    Transaction tx = pkt.readJson(Transaction.class);
    if (tx == null) return;
    Result<Transaction> res = blockchainService.submitTransaction(tx, false);
    if (!res.isError()) broadcast(OpCode.NEW_TX_ANNOUNCE, tx, conn);
  }

  private void handleHandshake(PeerConnection conn, NodePacket pkt, boolean isAck) {
    if (conn.remoteNodeId != null || conn.isOutbound != isAck) {
      conn.close();
      return;
    }
    HandshakeMessage msg = pkt.readJson(HandshakeMessage.class);
    long now = System.currentTimeMillis();
    if (!isValidHandshake(msg, new HandshakeContext(this.nodeId, nodeConfig.chainId, now, this.seenHandshakeNonces))) {
      conn.close();
      return;
    }
    if (!isSafePeerAddress(conn.getRemoteHost(), msg.p2pPort(), nodeConfig)) {
      conn.close();
      return;
    }
    if (conn.isOutbound && conn.remotePort > 0 && msg.p2pPort() != conn.remotePort) {
      conn.close();
      return;
    }
    for (PeerConnection c : connections) {
      if (c != conn && msg.nodeId().equalsIgnoreCase(c.remoteNodeId) && c.isConnected()) {
        boolean isThisSmaller = this.nodeId.compareToIgnoreCase(msg.nodeId()) < 0;
        boolean isKeepCurrent = isThisSmaller ? conn.isOutbound : !conn.isOutbound;
        if (isKeepCurrent) {
          c.close();
        } else {
          conn.close();
          return;
        }
      }
    }

    Block local = blockchainService.getLatestBlockHeader();
    conn.remoteHeight = msg.height();
    conn.remoteNodeId = msg.nodeId().toLowerCase();
    conn.remotePort = msg.p2pPort();
    conn.setAuthorizedTimeout(30000);
    candidatePeers.remove(new PeerInfo(conn.getRemoteHost(), msg.p2pPort(), 0L));
    if (peerRepository != null) {
      peerRepository.saveOrUpdate(new PeerInfo(conn.getRemoteHost(), msg.p2pPort(), now));
    }

    if (!isAck) {
      sendHandshakePacket(conn, OpCode.HANDSHAKE_ACK, pkt.requestId);
    }
    conn.send(new NodePacket(OpCode.GET_PEERS, 0, new byte[0]));

    if (msg.height() > local.height) {
      long fromHeight = (local.height > 0) ? Math.max(1, local.height - 5) : 0;
      GetBlocksRequest req = new GetBlocksRequest(fromHeight, 100);
      NodePacket p = NodePacket.fromJson(OpCode.GET_BLOCKS, 0, req);
      if (p != null) conn.send(p);
    }
  }
}
