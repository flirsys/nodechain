package so.fox.nodechain.model;

import java.io.Serializable;

// Информация о сетевом пире узла
public record PeerInfo(
  String host,
  int port,
  long lastSeen
) implements Serializable {

  // Возвращает строковое представление хоста и порта пира
  @Override
  public String toString() {
    return host + ":" + port;
  }
}
