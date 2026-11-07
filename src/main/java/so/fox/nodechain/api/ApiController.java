package so.fox.nodechain.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import so.fox.nodechain.config.NodeConfig;
import so.fox.nodechain.core.BlockchainService;
import so.fox.nodechain.core.Result;
import so.fox.nodechain.model.Account;
import so.fox.nodechain.model.Block;
import so.fox.nodechain.model.Transaction;
import so.fox.nodechain.p2p.P2pClientManager;

// REST API контроллер публичных запросов ноды блокчейна
@RestController
@CrossOrigin(originPatterns = { "http://localhost:*", "http://127.0.0.1:*", "http://localhost", "http://127.0.0.1", "null" })
@RequestMapping("/api/v1")
public class ApiController {

  public record AddressStateResponse(String address, long balance, long unconfirmedBalance, long nonce, long txCount) implements Serializable {}
  public record FeeResponse(long fee, String priority) implements Serializable {}
  public record MempoolResponse(int count, List<Transaction> transactions) implements Serializable {}
  public record AddressTransactionsResponse(long total, List<TxResponse> txs) implements Serializable {}
  public record SendTxRequest(String chainId, String from, String to, Long amount, Long fee, Long nonce, String publicKey, String signature, String comment) implements Serializable {
    public SendTxRequest(String from, String to, Long amount, Long fee, Long nonce, String publicKey, String signature, String comment) {
      this("foxchain-main-1", from, to, amount, fee, nonce, publicKey, signature, comment);
    }
  }
  public record SendTxResponse(String hash, String status, long addedAt, long nonce, long fee) implements Serializable {}
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record TxResponse(String hash, String from, String to, long amount, long fee, String comment, long timestamp, String status, Long blockHeight, String blockHash, Long confirmations) implements Serializable {}
  public record BlockResponse(long height, String hash, String previousHash, String merkleRoot, String minerAddress, long nonce, int txCount, long timestamp, List<String> transactions) implements Serializable {}
  public record NetworkStateResponse(String chainId, long height, String latestBlockHash, int peers, boolean isSynced, String version) implements Serializable {}
  public record ErrorResponse(int code, String message) implements Serializable {}

  private final BlockchainService blockchainService;
  private final NodeConfig nodeConfig;
  private final P2pClientManager p2pClientManager;
  private final java.util.Map<String, Long> lastRestTxTime = new java.util.concurrent.ConcurrentHashMap<>();

  public ApiController(BlockchainService blockchainService, NodeConfig nodeConfig, P2pClientManager p2pClientManager) {
    this.blockchainService = blockchainService;
    this.nodeConfig = nodeConfig;
    this.p2pClientManager = p2pClientManager;
  }

  // Состояние адреса (баланс, неподтвержденный баланс, nonce, число транзакций)
  @GetMapping("/addresses/{address}")
  public ResponseEntity<?> getAddressState(@PathVariable String address) {
    Result<Account> res = blockchainService.getAccountState(address);
    if (res.isError()) return toErrorResponse(res);
    Account acc = res.value;
    long unconfirmed = blockchainService.calculateUnconfirmedBalance(address);
    long txCount = blockchainService.countAddressTransactions(address).orElse(0L);
    return ResponseEntity.ok(new AddressStateResponse(
      acc.address, acc.balance, unconfirmed, acc.nonce, txCount
    ));
  }

  // Оценка рекомендуемой комиссии сети
  @GetMapping("/transactions/fee")
  public ResponseEntity<FeeResponse> getFee(@RequestParam(name = "priority", defaultValue = "normal") String priority) {
    long fee = BlockchainService.getEstimatedFee(priority);
    return ResponseEntity.ok(new FeeResponse(fee, priority.toLowerCase()));
  }

  // Просмотр содержимого пула mempool
  @GetMapping("/transactions/mempool")
  public ResponseEntity<MempoolResponse> getMempool() {
    List<Transaction> txs = blockchainService.getMempoolTransactions();
    return ResponseEntity.ok(new MempoolResponse(txs.size(), txs));
  }

  // История транзакций адреса с постраничной пагинацией
  @GetMapping("/addresses/{address}/transactions")
  public ResponseEntity<?> getAddressTransactions(
    @PathVariable String address,
    @RequestParam(name = "limit", defaultValue = "25") int limit,
    @RequestParam(name = "offset", defaultValue = "0") int offset
  ) {
    Result<Long> totalRes = blockchainService.countAddressTransactions(address);
    if (totalRes.isError()) return toErrorResponse(totalRes);
    int safeLimit = Math.min(100, Math.max(1, limit));
    int safeOffset = Math.min(10_000, Math.max(0, offset));
    Result<List<Transaction>> txsRes = blockchainService.getAddressTransactions(address, safeLimit, safeOffset);
    if (txsRes.isError()) return toErrorResponse(txsRes);
    long curHeight = blockchainService.getLatestBlockHeader().height;
    List<TxResponse> items = new ArrayList<>();
    for (Transaction tx : txsRes.value) {
      items.add(mapToTxResponse(tx, curHeight, false));
    }
    return ResponseEntity.ok(new AddressTransactionsResponse(totalRes.value, items));
  }

  // Отправка новой подписанной транзакции в сеть
  @PostMapping("/transactions/send")
  public ResponseEntity<?> sendTransaction(
    @RequestBody(required = false) SendTxRequest req,
    jakarta.servlet.http.HttpServletRequest httpReq
  ) {
    if (httpReq != null) {
      String ip = httpReq.getRemoteAddr();
      if (!P2pClientManager.isLocalAddress(ip)) {
        long now = System.currentTimeMillis();
        if (lastRestTxTime.size() > 1000) {
          lastRestTxTime.entrySet().removeIf(e -> (now - e.getValue()) > 60_000L);
          if (lastRestTxTime.size() > 5000) lastRestTxTime.clear();
        }
        Long last = lastRestTxTime.get(ip);
        if (last != null && (now - last) < 50L) {
          return ResponseEntity.status(429).body(new ErrorResponse(429, "Слишком много запросов (Rate limit)"));
        }
        lastRestTxTime.put(ip, now);
      }
    }
    if (req == null || req.from() == null || req.to() == null || req.amount() == null
        || req.nonce() == null || req.publicKey() == null || req.signature() == null) {
      return ResponseEntity.status(409).body(new ErrorResponse(409, "Транзакция отклонена"));
    }

    String chainId = req.chainId();
    if (chainId == null || chainId.isBlank() || !nodeConfig.chainId.equals(chainId)) {
      return ResponseEntity.status(409).body(new ErrorResponse(409, "Транзакция отклонена: неверный chainId"));
    }
    long fee = req.fee() != null ? req.fee() : BlockchainService.getEstimatedFee("normal");
    String comment = req.comment() != null ? req.comment() : "";
    Transaction tx = new Transaction(
      chainId,
      req.from(),
      req.to(),
      req.amount(),
      fee,
      req.nonce(),
      req.publicKey(),
      req.signature(),
      comment
    );

    Result<Transaction> res = blockchainService.submitTransaction(tx, true);
    if (res.isError()) return toErrorResponse(res);

    Transaction submitted = res.value;
    return ResponseEntity.ok(new SendTxResponse(
      submitted.hash, "pending", submitted.timestamp, submitted.nonce, submitted.fee
    ));
  }

  // Просмотр подробной информации о транзакции по 64-символьному hex-хешу
  @GetMapping("/transactions/{txHash:[a-fA-F0-9]{64}}")
  public ResponseEntity<?> getTransaction(@PathVariable String txHash) {
    Result<Transaction> res = blockchainService.getTransactionByHash(txHash);
    if (res.isError()) return toErrorResponse(res);
    long curHeight = blockchainService.getLatestBlockHeader().height;
    return ResponseEntity.ok(mapToTxResponse(res.value, curHeight, true));
  }

  // Получение блока по номеру, хешу или слову "latest"
  @GetMapping("/blocks/{identifier}")
  public ResponseEntity<?> getBlock(@PathVariable String identifier) {
    Result<Block> res = blockchainService.getBlockByIdentifier(identifier);
    if (res.isError()) return toErrorResponse(res);
    return ResponseEntity.ok(toBlockResponse(res.value));
  }

  // Состояние сети и статус синхронизации узла
  @GetMapping("/network")
  public ResponseEntity<NetworkStateResponse> getNetworkState() {
    Block latest = blockchainService.getLatestBlockHeader();
    int peers = p2pClientManager.getConnectedPeersCount();
    boolean isSynced = p2pClientManager.isSynced();
    return ResponseEntity.ok(new NetworkStateResponse(
      nodeConfig.chainId, latest.height, latest.hash, peers, isSynced, "1.0.0"
    ));
  }

  private static TxResponse mapToTxResponse(Transaction tx, long curHeight, boolean isIncludeBlockHash) {
    Long confirmations = calculateConfirmations(tx.status, tx.blockHeight, curHeight);
    String cleanComment = (tx.comment != null && !tx.comment.isBlank()) ? tx.comment : null;
    return new TxResponse(
      tx.hash, tx.from, tx.to, tx.amount, tx.fee, cleanComment, tx.timestamp, tx.status,
      tx.blockHeight, isIncludeBlockHash ? tx.blockHash : null, confirmations
    );
  }

  private static Long calculateConfirmations(String status, Long blockHeight, long curHeight) {
    if ("confirmed".equalsIgnoreCase(status) && blockHeight != null) return Math.max(1, curHeight - blockHeight + 1);
    return 0L;
  }

  private static BlockResponse toBlockResponse(Block b) {
    List<String> hashes = new ArrayList<>();
    if (b.transactions != null) {
      for (Transaction tx : b.transactions) {
        hashes.add(tx.hash);
      }
    }
    return new BlockResponse(b.height, b.hash, b.previousHash, b.merkleRoot, b.minerAddress, b.nonce, b.txCount, b.timestamp, hashes);
  }

  private static ResponseEntity<ErrorResponse> toErrorResponse(Result<?> res) {
    return ResponseEntity.status(res.status).body(new ErrorResponse(res.status, res.error));
  }
}
