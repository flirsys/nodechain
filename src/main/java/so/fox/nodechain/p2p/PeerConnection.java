package so.fox.nodechain.p2p;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import so.fox.nodechain.p2p.packet.NodePacket;

// Управляет активным TCP-соединением с удаленным узлом P2P.
public class PeerConnection implements Runnable {

  private static final Logger log = LoggerFactory.getLogger(PeerConnection.class);

  public final Socket socket;
  public final P2pClientManager manager;
  public final boolean isOutbound;
  public final AtomicBoolean isRunning = new AtomicBoolean(true);
  public final long createdAt = System.currentTimeMillis();

  private final InputStream in;
  private final OutputStream out;
  private final BlockingQueue<NodePacket> sendQueue = new ArrayBlockingQueue<>(64);
  private final Thread writerThread;
  private final AtomicBoolean isWriterStarted = new AtomicBoolean(false);

  public volatile String remoteNodeId;
  public volatile int remotePort;
  public volatile long remoteHeight;
  public volatile long writeStartTime = 0L;

  // Не меньше максимального легального пакета синхронизации: 10 блоков * 500 tx
  public static final int MAX_TXS_PER_SECOND = 5000;
  private volatile long txBudgetWindowStart = 0L;
  private volatile int txsProcessedInWindow = 0;

  // Проверяет и списывает квоту транзакций для соединения пира (защита от DoS).
  public synchronized boolean consumeTxBudget(int count) {
    long now = System.currentTimeMillis();
    if (now - txBudgetWindowStart >= 1000L) {
      txBudgetWindowStart = now;
      txsProcessedInWindow = 0;
    }
    txsProcessedInWindow += count;
    return txsProcessedInWindow <= MAX_TXS_PER_SECOND;
  }

  // Создает соединение с пиром с явным указанием направления подключения (исходящее/входящее).
  public PeerConnection(Socket socket, P2pClientManager manager, boolean isOutbound) throws IOException {
    this.socket = socket;
    this.socket.setSoTimeout(5000);
    this.manager = manager;
    this.isOutbound = isOutbound;
    this.in = new BufferedInputStream(socket.getInputStream());
    this.out = new BufferedOutputStream(socket.getOutputStream());

    this.writerThread = new Thread(this::runWriter, "p2p-writer-" + socket.getRemoteSocketAddress());
    this.writerThread.setDaemon(true);
  }

  // Создает входящее соединение с пиром по умолчанию.
  public PeerConnection(Socket socket, P2pClientManager manager) throws IOException {
    this(socket, manager, false);
  }

  // Устанавливает увеличенный рабочий таймаут сокета после успешной авторизации пира.
  public void setAuthorizedTimeout(int ms) {
    try {
      if (!socket.isClosed()) {
        this.socket.setSoTimeout(ms);
      }
    } catch (IOException ignored) {}
  }

  // Запускает асинхронный поток записи при первой необходимости
  private void startWriterIfNeeded() {
    if (isRunning.get() && isWriterStarted.compareAndSet(false, true)) {
      writerThread.start();
    }
  }

  // Основной цикл чтения входящих сетевых пакетов из сокета.
  @Override
  public void run() {
    startWriterIfNeeded();
    try {
      while (isRunning.get() && !socket.isClosed()) {
        boolean isAuthenticated = (remoteNodeId != null);
        NodePacket pkt = NodePacket.readFrom(in, isAuthenticated);
        if (manager != null) manager.handlePacket(this, pkt);
      }
    } catch (IOException err) {
      if (isRunning.get()) log.debug("Peer disconnected: {}:{}", getRemoteHost(), getRemotePort());
    } catch (Throwable err) {
      if (isRunning.get()) log.warn("Unexpected error handling peer {}:{}: {}", getRemoteHost(), getRemotePort(), err.getMessage());
    } finally {
      close();
      if (manager != null) manager.onDisconnected(this);
    }
  }

  // Асинхронный цикл отправки пакетов из очереди для защиты от Zero-Window Tarpit
  private void runWriter() {
    try {
      while (isRunning.get() && !socket.isClosed()) {
        NodePacket pkt = sendQueue.poll(500, TimeUnit.MILLISECONDS);
        if (pkt == null) continue;
        writeStartTime = System.currentTimeMillis();
        try {
          pkt.writeTo(out);
        } finally {
          writeStartTime = 0L;
        }
      }
    } catch (InterruptedException ignored) {
    } catch (IOException err) {
      log.debug("Write failed to {}: {}", getRemoteHost(), err.getMessage());
      close();
    }
  }

  // Отправляет сетевой пакет удаленному пиру через неблокирующую очередь.
  public void send(NodePacket pkt) {
    if (!isRunning.get() || socket.isClosed() || pkt == null) return;
    startWriterIfNeeded();
    boolean isQueued = sendQueue.offer(pkt);
    if (!isQueued) {
      log.warn("Send queue full for {}:{}, closing stalled peer connection", getRemoteHost(), getRemotePort());
      close();
    }
  }

  // Закрывает TCP-сокет соединения.
  public void close() {
    if (isRunning.compareAndSet(true, false)) {
      sendQueue.clear();
      try {
        socket.close();
      } catch (IOException ignored) {}
      if (!isWriterStarted.compareAndSet(false, true)) {
        writerThread.interrupt();
      }
    }
  }

  // Возвращает IP-адрес удаленного пира.
  public String getRemoteHost() {
    return socket.getInetAddress().getHostAddress();
  }

  // Возвращает сетевой порт P2P удаленного пира.
  public int getRemotePort() {
    return remotePort > 0 ? remotePort : socket.getPort();
  }

  // Проверяет активность соединения с пиром.
  public boolean isConnected() {
    return isRunning.get() && socket.isConnected() && !socket.isClosed();
  }
}
