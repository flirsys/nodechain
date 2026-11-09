package so.fox.nodechain.core;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import so.fox.nodechain.crypto.CryptoUtil;

// Сервис управления кошельком ноды майнера с криптографической верификацией ключей.
@Service
public class NodeWalletService {

  private static final Logger log = LoggerFactory.getLogger(NodeWalletService.class);

  private static void validateKeyPairAndAddress(PrivateKey priv, PublicKey pub, String addr, Path path) {
    if (priv == null || pub == null || !CryptoUtil.isValidAddress(addr)) throw new IllegalStateException("Некорректные данные кошелька: " + path.toAbsolutePath());
    String derived = CryptoUtil.getAddressFromPublicKey(pub);
    if (!addr.equalsIgnoreCase(derived)) throw new IllegalStateException("Адрес не соответствует открытому ключу кошелька: " + path.toAbsolutePath());
    String testPayload = "challenge-" + path.getFileName();
    String sig = CryptoUtil.sign(priv, testPayload);
    if (sig == null || !CryptoUtil.isSignatureValid(pub, testPayload, sig)) throw new IllegalStateException("Приватный ключ не соответствует открытому ключу: " + path.toAbsolutePath());
  }

  public final Path walletPath;
  public PublicKey publicKey;
  public PrivateKey privateKey;
  public String address;

  // Конструктор по умолчанию (путь nodewallet.dat в текущей директории).
  public NodeWalletService() {
    this(Path.of("nodewallet.dat"));
  }

  // Конструктор с явным указанием пути к файлу кошелька.
  public NodeWalletService(Path walletPath) {
    this.walletPath = walletPath != null ? walletPath : Path.of("nodewallet.dat");
  }

  // Инициализация ключей и адреса кошелька ноды.
  @PostConstruct
  public void init() {
    if (!Files.exists(walletPath)) generateAndSaveWallet(walletPath);
    else loadWalletFrom(walletPath);
    log.info("Node miner wallet address: {}", address);
  }

  // Загружает и криптографически верифицирует кошелек из указанного файла.
  public void loadWalletFrom(Path path) {
    try {
      List<String> lines = Files.readAllLines(path);
      if (lines.size() >= 3) {
        PrivateKey priv = CryptoUtil.parsePrivateKey(lines.get(0).trim());
        PublicKey pub = CryptoUtil.parsePublicKey(lines.get(1).trim());
        String addr = lines.get(2).trim().toLowerCase();
        validateKeyPairAndAddress(priv, pub, addr, path);
        this.privateKey = priv;
        this.publicKey = pub;
        this.address = addr;
        log.info("Loaded node wallet from: {}", path.toAbsolutePath());
        return;
      }
    } catch (IOException err) {
      log.warn("Failed to read node wallet file: {}", err.getMessage());
    }
    log.error("Node wallet file is corrupted: {}", path.toAbsolutePath());
    throw new IllegalStateException("Кошелек ноды поврежден: " + path.toAbsolutePath());
  }

  private void generateAndSaveWallet(Path path) {
    KeyPair kp = CryptoUtil.generateKeyPair();
    if (kp == null) throw new IllegalStateException("Не удалось сгенерировать ключи кошелька");
    this.publicKey = kp.getPublic();
    this.privateKey = kp.getPrivate();
    this.address = CryptoUtil.getAddressFromPublicKey(publicKey);
    if (!CryptoUtil.isValidAddress(this.address)) throw new IllegalStateException("Не удалось сформировать валидный адрес кошелька");
    String content = CryptoUtil.encodePrivateKey(privateKey) + System.lineSeparator()
      + CryptoUtil.encodePublicKey(publicKey) + System.lineSeparator()
      + address + System.lineSeparator();
    try {
      if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix") && !Files.exists(path)) {
        try {
          FileAttribute<?> permAttr = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
          Files.createFile(path, permAttr);
        } catch (Exception ignored) {}
      }
      Files.writeString(path, content);
      try {
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (posix != null) {
          posix.setPermissions(PosixFilePermissions.fromString("rw-------"));
        }
      } catch (Exception ignored) {}
      File file = path.toFile();
      file.setReadable(false, false);
      file.setReadable(true, true);
      file.setWritable(false, false);
      file.setWritable(true, true);
      log.info("Generated new node wallet: {}", path.toAbsolutePath());
    } catch (IOException err) {
      log.error("Failed to write node wallet: {}", err.getMessage());
      throw new IllegalStateException("Не удалось сохранить кошелек ноды: " + path.toAbsolutePath(), err);
    }
  }
}
