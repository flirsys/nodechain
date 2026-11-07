package so.fox.nodechain.p2p;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import so.fox.nodechain.p2p.packet.NodePacket;

// Управляет активным TCP-соединением с удаленным узлом P2P
public class PeerConnection implements Runnable {

  private static final Logger log = LoggerFactory.getLogger(PeerConnection.class);

  public final Socket socket;
  public final P2pClientManager manager;
  public final boolean isOutbound;
  public final AtomicBoolean isRunning = new AtomicBoolean(true);

  private final InputStream in;
  private final OutputStream out;

  public String remoteNodeId;
  public int remotePort;
  public volatile long remoteHeight;

  // Создает соединение с пиром с явным указанием направления подключения (исходящее/входящее)
  public PeerConnection(Socket socket, P2pClientManager manager, boolean isOutbound) throws IOException {
    this.socket = socket;
    this.socket.setSoTimeout(45000);
    this.manager = manager;
    this.isOutbound = isOutbound;
    this.in = new BufferedInputStream(socket.getInputStream());
    this.out = new BufferedOutputStream(socket.getOutputStream());
  }

  // Создает входящее соединение с пиром по умолчанию
  public PeerConnection(Socket socket, P2pClientManager manager) throws IOException {
    this(socket, manager, false);
  }

  // Основной цикл чтения входящих сетевых пакетов из сокета
  @Override
  public void run() {
    try {
      while (isRunning.get() && !socket.isClosed()) {
        NodePacket pkt = NodePacket.readFrom(in);
        manager.handlePacket(this, pkt);
      }
    } catch (IOException err) {
      if (isRunning.get()) log.debug("Peer disconnected: {}:{}", getRemoteHost(), getRemotePort());
    } catch (Throwable err) {
      if (isRunning.get()) log.warn("Unexpected error handling peer {}:{}: {}", getRemoteHost(), getRemotePort(), err.getMessage());
    } finally {
      close();
      manager.onDisconnected(this);
    }
  }

  // Отправляет сетевой пакет удаленному пиру
  public synchronized void send(NodePacket pkt) {
    if (!isRunning.get() || socket.isClosed() || pkt == null) return;
    try {
      pkt.writeTo(out);
    } catch (IOException err) {
      log.debug("Failed to send packet to {}: {}", getRemoteHost(), err.getMessage());
      close();
    }
  }

  // Закрывает TCP-сокет соединения
  public void close() {
    if (isRunning.compareAndSet(true, false)) {
      try {
        socket.close();
      } catch (IOException ignored) {}
    }
  }

  // Возвращает IP-адрес удаленного пира
  public String getRemoteHost() {
    return socket.getInetAddress().getHostAddress();
  }

  // Возвращает сетевой порт P2P удаленного пира
  public int getRemotePort() {
    return remotePort > 0 ? remotePort : socket.getPort();
  }

  // Проверяет активность соединения с пиром
  public boolean isConnected() {
    return isRunning.get() && socket.isConnected() && !socket.isClosed();
  }
}
