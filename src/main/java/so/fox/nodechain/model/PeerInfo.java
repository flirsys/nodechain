package so.fox.nodechain.model;

import java.io.Serializable;

// Информация о сетевом пире узла (хост, порт, время последней активности).
public record PeerInfo(
  String host,
  int port,
  long lastSeen
) implements Serializable {

  // Возвращает строковое представление хоста и порта пира в формате host:port.
  @Override
  public String toString() {
    return host + ":" + port;
  }
}
