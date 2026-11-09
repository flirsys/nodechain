package so.fox.nodechain.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
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
import so.fox.nodechain.model.Block;
import so.fox.nodechain.model.Transaction;
import so.fox.nodechain.p2p.P2pClientManager;

// REST API контроллер публичных запросов ноды блокчейна.
@RestController
// Клиент локальный у каждого пользователя: file:// даёт Origin null, порт заранее неизвестен
@CrossOrigin(origins = "null", originPatterns = { "http://localhost:[*]", "http://127.0.0.1:[*]" })
@RequestMapping("/api/v1")
public class ApiController {

  public record AddressStateResponse(String address, long balance, long unconfirmedBalance, long nonce, long txCount, Long nextPendingNonce) implements Serializable {}
  public record FeeResponse(long fee, String priority) implements Serializable {}
  public record MempoolResponse(int count, List<Transaction> transactions) implements Serializable {}
  public record AddressTransactionsResponse(long total, List<TxResponse> txs) implements Serializable {}
  public record SendTxRequest(String chainId, String from, String to, Long amount, Long fee, Long nonce, Long timestamp, String publicKey, String signature, String comment) implements Serializable {
  }
  public record SendTxResponse(String hash, String status, long addedAt, long nonce, long fee) implements Serializable {}
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record TxResponse(String hash, String from, String to, long amount, long fee, String comment, long timestamp, String status, Long blockHeight, String blockHash, Long confirmations) implements Serializable {}
  public record BlockResponse(long height, String hash, String previousHash, String merkleRoot, String minerAddress, long nonce, int txCount, long timestamp, List<String> transactions) implements Serializable {}
  public record NetworkStateResponse(String chainId, long height, String latestBlockHash, int peers, boolean isSynced, String version) implements Serializable {}
  public record ErrorResponse(int code, String message) implements Serializable {}

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

  private final BlockchainService blockchainService;
  private final NodeConfig nodeConfig;
  private final P2pClientManager p2pClientManager;

  public ApiController(BlockchainService blockchainService, NodeConfig nodeConfig, P2pClientManager p2pClientManager) {
    this.blockchainService = blockchainService;
    this.nodeConfig = nodeConfig;
    this.p2pClientManager = p2pClientManager;
  }

  // Возвращает текущее состояние адреса (баланс, неподтвержденный баланс, nonce, число транзакций).
  @GetMapping("/addresses/{address}")
  public ResponseEntity<?> getAddressState(@PathVariable String address) {
    Result<BlockchainService.AccountDetails> res = blockchainService.getAccountDetails(address);
    if (res.isError()) return toErrorResponse(res);
    BlockchainService.AccountDetails d = res.value;
    return ResponseEntity.ok(new AddressStateResponse(
      d.account().address, d.account().balance, d.unconfirmedBalance(), d.account().nonce, d.txCount(), d.nextPendingNonce()
    ));
  }

  // Оценивает рекомендуемую комиссию сети в зависимости от приоритета.
  @GetMapping("/transactions/fee")
  public ResponseEntity<FeeResponse> getFee(@RequestParam(name = "priority", defaultValue = "normal") String priority) {
    String norm = "high".equalsIgnoreCase(priority) ? "high" : ("low".equalsIgnoreCase(priority) ? "low" : "normal");
    return ResponseEntity.ok(new FeeResponse(BlockchainService.getEstimatedFee(norm), norm));
  }

  // Возвращает список текущих транзакций в mempool с постраничным ограничением.
  @GetMapping("/transactions/mempool")
  public ResponseEntity<MempoolResponse> getMempool(
    @RequestParam(name = "limit", defaultValue = "100") int limit,
    @RequestParam(name = "offset", defaultValue = "0") int offset
  ) {
    List<Transaction> all = blockchainService.getMempoolTransactions();
    int safeLimit = Math.min(100, Math.max(1, limit));
    int safeOffset = Math.min(10_000, Math.max(0, offset));
    int toIndex = Math.min(all.size(), safeOffset + safeLimit);
    List<Transaction> page = (safeOffset >= all.size())
      ? Collections.emptyList()
      : all.subList(safeOffset, toIndex);
    return ResponseEntity.ok(new MempoolResponse(all.size(), page));
  }

  // Возвращает историю транзакций адреса с постраничной пагинацией.
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
    if (totalRes.value == 0 || safeOffset >= totalRes.value) {
      return ResponseEntity.ok(new AddressTransactionsResponse(totalRes.value, Collections.emptyList()));
    }
    Result<List<Transaction>> txsRes = blockchainService.getAddressTransactions(address, safeLimit, safeOffset);
    if (txsRes.isError()) return toErrorResponse(txsRes);
    long curHeight = blockchainService.getLatestBlockHeader().height;
    List<TxResponse> items = new ArrayList<>();
    for (Transaction tx : txsRes.value) {
      items.add(mapToTxResponse(tx, curHeight, false));
    }
    return ResponseEntity.ok(new AddressTransactionsResponse(totalRes.value, items));
  }

  // Принимает и валидирует новую подписанную транзакцию для отправки в сеть.
  @PostMapping("/transactions/send")
  public ResponseEntity<?> sendTransaction(@RequestBody(required = false) SendTxRequest req) {
    if (req == null || req.from() == null || req.to() == null || req.amount() == null
        || req.nonce() == null || req.publicKey() == null || req.signature() == null) {
      return ResponseEntity.status(409).body(new ErrorResponse(409, "Транзакция отклонена"));
    }
    long fee = req.fee() != null ? req.fee() : BlockchainService.getEstimatedFee("normal");
    long ts = req.timestamp() != null ? req.timestamp() : System.currentTimeMillis();
    Transaction tx = new Transaction(
      req.chainId(), req.from(), req.to(), req.amount(), fee, req.nonce(), ts,
      req.publicKey(), req.signature(), req.comment() != null ? req.comment() : ""
    );
    Result<Transaction> res = blockchainService.submitTransaction(tx, true);
    if (res.isError()) return toErrorResponse(res);
    Transaction s = res.value;
    return ResponseEntity.ok(new SendTxResponse(s.hash, "pending", s.timestamp, s.nonce, s.fee));
  }

  // Возвращает подробную информацию о транзакции по её 64-символьному hex-хешу.
  @GetMapping("/transactions/{txHash:[a-fA-F0-9]{64}}")
  public ResponseEntity<?> getTransaction(@PathVariable String txHash) {
    Result<Transaction> res = blockchainService.getTransactionByHash(txHash);
    if (res.isError()) return toErrorResponse(res);
    long curHeight = blockchainService.getLatestBlockHeader().height;
    return ResponseEntity.ok(mapToTxResponse(res.value, curHeight, true));
  }

  // Возвращает блок по номеру высоты, хешу или псевдониму "latest".
  @GetMapping("/blocks/{identifier}")
  public ResponseEntity<?> getBlock(@PathVariable String identifier) {
    Result<Block> res = blockchainService.getBlockByIdentifier(identifier);
    if (res.isError()) return toErrorResponse(res);
    return ResponseEntity.ok(toBlockResponse(res.value));
  }

  // Возвращает текущее состояние сети и статус синхронизации узла.
  @GetMapping("/network")
  public ResponseEntity<NetworkStateResponse> getNetworkState() {
    Block latest = blockchainService.getLatestBlockHeader();
    int peers = p2pClientManager != null ? p2pClientManager.getConnectedPeersCount() : 0;
    boolean isSynced = p2pClientManager == null || p2pClientManager.isSynced();
    return ResponseEntity.ok(new NetworkStateResponse(
      nodeConfig.chainId, latest.height, latest.hash, peers, isSynced, P2pClientManager.VERSION
    ));
  }
}
