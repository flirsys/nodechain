package so.fox.nodechain.core;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import so.fox.nodechain.config.NodeConfig;
import so.fox.nodechain.crypto.CryptoUtil;
import so.fox.nodechain.model.Account;
import so.fox.nodechain.model.Block;
import so.fox.nodechain.model.Transaction;
import so.fox.nodechain.p2p.P2pClientManager;
import so.fox.nodechain.p2p.packet.OpCode;
import so.fox.nodechain.repository.AccountRepository;
import so.fox.nodechain.repository.BlockRepository;
import so.fox.nodechain.repository.TransactionRepository;

// Основной сервис логики блокчейна: состояние аккаунтов, mempool, консенсус и майнинг.
@Service
public class BlockchainService {

  public static final int MAX_REORG_DEPTH = 3;

  private static final Logger log = LoggerFactory.getLogger(BlockchainService.class);

  private static final Comparator<Transaction> TX_ORDER = Comparator
    .comparingLong((Transaction t) -> t.nonce)
    .thenComparing(Comparator.comparingLong((Transaction t) -> t.fee).reversed())
    .thenComparingLong(t -> t.timestamp)
    .thenComparing(t -> t.hash != null ? t.hash : "");

  private static final Comparator<Transaction> EVICTION_ORDER = Comparator
    .comparingLong((Transaction t) -> t.fee)
    .thenComparing(Comparator.comparingLong((Transaction t) -> t.timestamp).reversed())
    .thenComparing(t -> t.hash != null ? t.hash : "");

  public static final long MIN_FEE_BUMP = 5L;

  // Оценивает рекомендуемую комиссию по уровню приоритета (low, normal, high).
  public static long getEstimatedFee(String priority) {
    if ("high".equalsIgnoreCase(priority)) return 100L;
    if ("low".equalsIgnoreCase(priority)) return 25L;
    return 50L;
  }

  private static boolean isBetterCandidate(Transaction next, SenderCandidate best) {
    if (best == null) return true;
    if (next.fee != best.tx.fee) return next.fee > best.tx.fee;
    if (next.timestamp != best.tx.timestamp) return next.timestamp < best.tx.timestamp;
    String h1 = next.hash != null ? next.hash : "";
    String h2 = best.tx.hash != null ? best.tx.hash : "";
    return h1.compareTo(h2) < 0;
  }

  // Детализированное состояние аккаунта с неподтвержденным балансом и nonce.
  public record AccountDetails(Account account, long unconfirmedBalance, long txCount, long nextPendingNonce) {}
  private record ApplyBlockContext(Block block, Long ancestorHeight, Set<String> confirmedHashes) {}
  private record SenderCandidate(Account sender, Transaction tx, long newBalance, long newNonce) {}
  private record ReorgContext(Block ancestor, Block newLatest, List<Block> rollback, List<Block> branch) {}

  private final NodeConfig nodeConfig;
  private final BlockRepository blockRepository;
  private final TransactionRepository transactionRepository;
  private final AccountRepository accountRepository;
  private final NodeWalletService nodeWalletService;
  private final TransactionTemplate txTemplate;
  private P2pClientManager p2pClientManager;

  private final Map<String, Transaction> mempool = new ConcurrentHashMap<>();
  private final AtomicReference<Block> cachedLatestBlock = new AtomicReference<>();
  private final Object chainLock = new Object();
  private final AtomicBoolean isMining = new AtomicBoolean(false);
  private ScheduledExecutorService minerExecutor;

  // Конструктор сервиса управления блокчейном.
  public BlockchainService(
    NodeConfig nodeConfig,
    BlockRepository blockRepository,
    TransactionRepository transactionRepository,
    AccountRepository accountRepository,
    NodeWalletService nodeWalletService,
    PlatformTransactionManager txManager,
    @Lazy P2pClientManager p2pClientManager
  ) {
    this.nodeConfig = nodeConfig;
    this.blockRepository = blockRepository;
    this.transactionRepository = transactionRepository;
    this.accountRepository = accountRepository;
    this.nodeWalletService = nodeWalletService;
    this.txTemplate = new TransactionTemplate(txManager);
    this.p2pClientManager = p2pClientManager;
  }

  // Устанавливает P2P менеджер при отложенной инициализации.
  public void setP2pClientManager(P2pClientManager p2pClientManager) {
    this.p2pClientManager = p2pClientManager;
  }

  // Инициализирует блокчейн: генезис-блок, мемпул и фоновый авто-майнер.
  @PostConstruct
  public synchronized void init() {
    if (blockRepository.count() == 0) createGenesisBlock();
    else if (getLatestBlockHeader().height == 0) allocateGenesisFundsIfEmpty();
    restorePendingMempool();
    Block latest = getLatestBlockHeader();
    cachedLatestBlock.set(latest);
    log.info("Initialized blockchain at height {}, mempool txs: {}", latest.height, mempool.size());
    if (!nodeConfig.isAutoMine) return;
    int interval = Math.max(1, nodeConfig.mineIntervalSeconds);
    if (minerExecutor != null) minerExecutor.shutdownNow();
    minerExecutor = Executors.newSingleThreadScheduledExecutor(ConsensusUtil.daemonFactory("blockchain-miner"));
    minerExecutor.scheduleWithFixedDelay(this::autoMineTick, interval, interval, TimeUnit.SECONDS);
    log.info("Auto-miner started with interval {}s", interval);
  }

  // Завершает работу фонового майнера при остановке сервиса.
  @PreDestroy
  public synchronized void stop() {
    if (minerExecutor != null) {
      minerExecutor.shutdownNow();
      minerExecutor = null;
    }
  }

  // Возвращает заголовок последнего блока цепочки.
  public Block getLatestBlockHeader() {
    Block cached = cachedLatestBlock.get();
    if (cached != null) return cached;
    Block latest = blockRepository.findLatestBlock().orElseGet(() -> Block.createGenesis(nodeConfig.chainId, resolveGenesisAddress(), nodeConfig.difficulty, 0L));
    cachedLatestBlock.set(latest);
    return latest;
  }

  // Ищет блок по номеру высоты, по криптографическому хешу или по ключевому слову "latest".
  public Result<Block> getBlockByIdentifier(String id) {
    if (id == null || id.trim().isEmpty()) return Result.fail(404, "Блок отсутствует");
    String trimmed = id.trim();
    if ("latest".equalsIgnoreCase(trimmed)) return toBlockResult(blockRepository.findLatestBlock());
    if (trimmed.matches("^\\d{1,18}$")) {
      return toBlockResult(blockRepository.findByHeight(Long.parseLong(trimmed)));
    }
    if (trimmed.matches("^[0-9a-fA-F]{64}$")) {
      return toBlockResult(blockRepository.findByHash(trimmed.toLowerCase()));
    }
    return Result.fail(404, "Блок отсутствует");
  }

  // Возвращает аккаунт по указанному адресу или пустой объект без сохранения в базе данных.
  public Account getAccount(String addr) {
    if (addr == null) return new Account("", 0, 0);
    String normalized = addr.toLowerCase();
    return accountRepository.findByAddress(normalized)
      .map(a -> new Account(a.address, a.balance, a.nonce))
      .orElseGet(() -> new Account(normalized, 0, 0));
  }

  // Сохраняет состояние аккаунта в базе данных.
  public void saveAccount(Account acc) {
    accountRepository.saveOrUpdate(acc);
  }

  // Возвращает состояние аккаунта или ошибку, если адрес невалиден или не найден.
  public Result<Account> getAccountState(String addr) {
    Result<AccountDetails> res = getAccountDetails(addr);
    return res.isError() ? Result.fail(res.status, res.error) : Result.ok(res.value.account());
  }

  // Возвращает полное состояние аккаунта за один проход без дублирования SQL-запросов.
  public Result<AccountDetails> getAccountDetails(String addr) {
    if (!CryptoUtil.isValidAddress(addr)) return Result.fail(400, "Некорректный формат адреса");
    String normalized = addr.toLowerCase();
    Optional<Account> opt = accountRepository.findByAddress(normalized);
    long txCount = transactionRepository.countByAddress(normalized);
    if (opt.isEmpty() && txCount == 0) {
      return Result.fail(404, "Адрес еще не совершал транзакций и не имеет баланса");
    }
    Account acc = opt.orElseGet(() -> new Account(normalized, 0, 0));
    long unconfirmed = calculateUnconfirmedBalance(acc);
    long nextPendingNonce = calculateNextPendingNonce(acc);
    return Result.ok(new AccountDetails(acc, unconfirmed, txCount, nextPendingNonce));
  }

  // Вычисляет неподтвержденный баланс с защитой от переполнения по строковому адресу.
  public long calculateUnconfirmedBalance(String addr) {
    if (addr == null) return 0L;
    return calculateUnconfirmedBalance(getAccount(addr));
  }

  // Вычисляет неподтвержденный баланс для существующего объекта аккаунта.
  public long calculateUnconfirmedBalance(Account acc) {
    if (acc == null || acc.address == null) return 0L;
    String normalized = acc.address.toLowerCase();
    long balance = acc.balance;
    for (Transaction tx : mempool.values()) {
      if (!tx.from.equalsIgnoreCase(normalized)) continue;
      try {
        long cost = Math.addExact(tx.amount, tx.fee);
        balance = Math.subtractExact(balance, cost);
      } catch (ArithmeticException err) {
        log.warn("Переполнение баланса в mempool для адреса {}, сброс в 0", normalized);
        return 0L;
      }
    }
    return Math.max(0L, balance);
  }

  // Вычисляет следующий ожидаемый nonce для существующего объекта аккаунта.
  public long calculateNextPendingNonce(Account acc) {
    if (acc == null || acc.address == null) return 0L;
    String normalized = acc.address.toLowerCase();
    long expectedNonce = acc.nonce;
    List<Transaction> senderTxs = mempool.values().stream()
      .filter(m -> m.from.equalsIgnoreCase(normalized))
      .sorted(Comparator.comparingLong(m -> m.nonce))
      .toList();
    for (Transaction m : senderTxs) {
      if (m.nonce < expectedNonce) continue;
      if (m.nonce != expectedNonce) break;
      expectedNonce++;
    }
    return expectedNonce;
  }

  // Проверяет наличие входящих транзакций в мемпуле для адреса получателя
  private boolean hasMempoolCredit(String address) {
    if (address == null || mempool.isEmpty()) return false;
    String norm = address.trim().toLowerCase();
    for (Transaction t : mempool.values()) {
      if (norm.equals(t.to)) return true;
    }
    return false;
  }

  // Принимает и валидирует входящую транзакцию для mempool.
  public Result<Transaction> submitTransaction(Transaction tx, boolean isBroadcast) {
    if (tx == null) return Result.fail(409, "Транзакция отклонена");
    if (tx.hash == null || tx.hash.isEmpty()) tx.hash = tx.calculateHash();
    if (mempool.containsKey(tx.hash.trim().toLowerCase())) return Result.fail(409, "Конфликт nonce");
    Result<Void> validation = ConsensusUtil.validateTransactionBasics(tx, nodeConfig.chainId);
    if (validation.isError()) return Result.fail(validation.status, validation.error);

    // Быстрый отсев транзакций с нулевых адресов без захвата chainLock (защита от DoS)
    Account preCheck = accountRepository.findByAddress(tx.from).orElse(null);
    long preBalance = preCheck != null ? preCheck.balance : 0L;
    if (preBalance <= 0 && !hasMempoolCredit(tx.from)) {
      return Result.fail(409, "Недостаточно средств");
    }

    synchronized (chainLock) {
      Account sender = getAccount(tx.from);
      if (tx.nonce != calculateNextPendingNonce(sender)) return Result.fail(409, "Конфликт nonce");
      long unconfirmed = calculateUnconfirmedBalance(sender);
      long required = Math.addExact(tx.amount, tx.fee);
      if (unconfirmed < required) return Result.fail(409, "Недостаточно средств");
      if (mempool.size() >= nodeConfig.maxMempoolSize) {
        Result<Void> evictRes = evictLowestFeeTx(tx);
        if (evictRes.isError()) return Result.fail(evictRes.status, evictRes.error);
      }
      if (isTxAlreadyConfirmed(tx.hash, null)) return Result.fail(409, "Конфликт nonce");
      tx.status = "pending";
      tx.blockHeight = null;
      tx.blockHash = null;
      tx.txIndex = 0;
      transactionRepository.save(tx);
      mempool.put(tx.hash, tx);
    }

    log.info("New pending transaction: {} (amount: {}, fee: {})", tx.hash, tx.amount, tx.fee);
    if (isBroadcast && p2pClientManager != null) p2pClientManager.broadcast(OpCode.NEW_TX_ANNOUNCE, tx, null);
    return Result.ok(tx);
  }

  // Майнинг нового блока: выбор транзакций, вычисление PoW и сохранение блока.
  public Block mineBlock() {
    if (!isMining.compareAndSet(false, true)) {
      log.warn("Mining already in progress, skipping concurrent attempt");
      return null;
    }
    Block committedBlock = null;
    try {
      List<Transaction> validTxs;
      Block latest;
      String minerAddr;
      int targetDiff;

      synchronized (chainLock) {
        validTxs = selectValidTransactions();
        latest = getLatestBlockHeader();
        minerAddr = (nodeWalletService != null && nodeWalletService.address != null && !nodeWalletService.address.isBlank())
          ? nodeWalletService.address.trim().toLowerCase()
          : resolveGenesisAddress();
        if (!CryptoUtil.isValidAddress(minerAddr)) {
          log.error("Cannot mine block: invalid miner address");
          return null;
        }
        targetDiff = getNextBlockDifficulty(latest);
      }

      Block newBlock = new Block(latest.height + 1, nodeConfig.chainId, latest.hash, minerAddr, validTxs, targetDiff);
      if (newBlock.timestamp <= latest.timestamp) {
        newBlock.timestamp = latest.timestamp + 1;
        newBlock.hash = newBlock.calculateHash();
      }
      boolean isMined = newBlock.mineBlock(targetDiff);
      if (!isMined) {
        log.warn("Mining interrupted or aborted for block #{}", newBlock.height);
        return null;
      }

      synchronized (chainLock) {
        committedBlock = commitMinedBlock(newBlock);
      }
    } finally {
      isMining.set(false);
    }

    // Рассылку смайненного блока выполняем асинхронно вне лока глобального состояния
    if (committedBlock != null && p2pClientManager != null) {
      p2pClientManager.broadcast(OpCode.NEW_BLOCK_ANNOUNCE, committedBlock, null);
    }
    return committedBlock;
  }

  // Вычисляет сложность следующего блока на основе текущей вершины и интервала ретаргета.
  public int getNextBlockDifficulty(Block latest) {
    return getBranchBlockDifficulty(latest, Collections.emptyList(), null, 0);
  }

  // Возвращает медианное время последних 11 блоков относительно указанной вершины.
  public long getMedianTimePast(Block tip) {
    if (tip == null) return 0L;
    long from = Math.max(0L, tip.height - (ConsensusUtil.MTP_WINDOW - 1));
    List<Block> window = blockRepository.findBlocksBetween(from, tip.height);
    return ConsensusUtil.calculateMedianTimePast(window);
  }

  // Валидирует и транзакционно применяет входящий блок от пира к цепочке с проверкой MTP и chainId.
  public boolean applyIncomingBlock(Block b) {
    if (b == null || b.hash == null) return false;
    Block latest;
    synchronized (chainLock) {
      latest = getLatestBlockHeader();
    }
    long maxTime = System.currentTimeMillis() + ConsensusUtil.MAX_FUTURE_BLOCK_TIME_MS;
    int expectedDiff = getNextBlockDifficulty(latest);
    if (b.difficulty != expectedDiff) {
      log.warn("Rejected incoming block #{}: difficulty mismatch (expected {}, got {})", b.height, expectedDiff, b.difficulty);
      return false;
    }
    long mtp = getMedianTimePast(latest);
    if (!ConsensusUtil.isValidBlockHeader(latest, b, expectedDiff, maxTime, nodeConfig.chainId, mtp)) return false;

    String chk = nodeConfig.checkpoints.get(b.height);
    if (chk != null && !chk.equalsIgnoreCase(b.hash)) {
      log.warn("Rejected incoming block #{}: checkpoint mismatch (expected {}, got {})", b.height, chk, b.hash);
      return false;
    }

    Set<String> hashes = new HashSet<>();
    if (b.transactions != null) {
      for (Transaction tx : b.transactions) {
        if (tx != null && tx.hash != null) hashes.add(tx.hash.toLowerCase());
      }
    }
    Set<String> confirmed = transactionRepository.findConfirmedHashes(hashes);
    Map<String, Account> state = new HashMap<>();
    ApplyBlockContext ctx = new ApplyBlockContext(b, null, confirmed);
    if (!applyBlockToSimState(ctx, state)) return false;

    synchronized (chainLock) {
      if (!getLatestBlockHeader().hash.equals(latest.hash)) return false;
      boolean isCommitted = commitBlockState(b, state);
      if (!isCommitted) return false;
      log.info("Applied incoming block #{} ({})", b.height, b.hash);
      return true;
    }
  }

  // Реорганизует цепочку блоков при наличии валидной ветки с большим chainwork.
  public boolean reorganizeChain(List<Block> newChain) {
    if (newChain == null || newChain.isEmpty()) return false;
    for (Block blk : newChain) {
      if (blk == null || blk.hash == null || blk.previousHash == null) return false;
    }
    synchronized (chainLock) {
      int forkIdx = findForkIndex(newChain);
      if (forkIdx >= newChain.size()) return false;
      List<Block> branch = newChain.subList(forkIdx, newChain.size());
      Block first = branch.get(0);
      Optional<Block> ancestorOpt = blockRepository.findByHash(first.previousHash);
      if (ancestorOpt.isEmpty()) return false;
      Block ancestor = ancestorOpt.get();
      Block currentLatest = getLatestBlockHeader();
      long depth = currentLatest.height - ancestor.height;
      if (depth > MAX_REORG_DEPTH || depth < 0) {
        log.warn("Reorganization rejected: depth {} exceeds limit {}", depth, MAX_REORG_DEPTH);
        return false;
      }
      Block newLatest = branch.get(branch.size() - 1);
      long maxTime = System.currentTimeMillis() + ConsensusUtil.MAX_FUTURE_BLOCK_TIME_MS;
      if (!validateBranch(branch, ancestor, maxTime)) return false;

      List<Block> rollback = collectRollbackBlocks(currentLatest.height, ancestor.height);
      if (rollback.size() != depth) return false;
      BigInteger curWork = ConsensusUtil.calculateChainWork(rollback, nodeConfig.difficulty);
      BigInteger branchWork = ConsensusUtil.calculateChainWork(branch, nodeConfig.difficulty);
      if (branchWork.compareTo(curWork) <= 0) return false;
      Map<String, Account> state = new HashMap<>();
      if (!simulateReorg(rollback, branch, state, ancestor.height)) return false;
      ReorgContext ctx = new ReorgContext(ancestor, newLatest, rollback, branch);
      return applyReorgCommit(ctx, state);
    }
  }

  // Проверяет наличие блока с указанным хешем в цепочке.
  public boolean isBlockPresent(String hash) {
    if (hash == null || hash.isBlank()) return false;
    String norm = hash.trim().toLowerCase();
    Block latest = cachedLatestBlock.get();
    if (latest != null && norm.equals(latest.hash)) return true;
    return blockRepository.findByHash(norm).isPresent();
  }

  // Возвращает список всех текущих транзакций в mempool в детерминированном порядке.
  public List<Transaction> getMempoolTransactions() {
    return mempool.values().stream().sorted(TX_ORDER).toList();
  }

  // Возвращает транзакции адреса с пагинацией (регистронезависимо).
  public Result<List<Transaction>> getAddressTransactions(String addr, int limit, int offset) {
    if (!CryptoUtil.isValidAddress(addr)) return Result.fail(400, "Некорректный формат адреса");
    return Result.ok(transactionRepository.findByAddress(addr.toLowerCase(), limit, offset));
  }

  // Возвращает общее количество транзакций адреса (регистронезависимо).
  public Result<Long> countAddressTransactions(String addr) {
    if (!CryptoUtil.isValidAddress(addr)) return Result.fail(400, "Некорректный формат адреса");
    return Result.ok(transactionRepository.countByAddress(addr.toLowerCase()));
  }

  // Находит транзакцию по ее криптографическому хешу.
  public Result<Transaction> getTransactionByHash(String hash) {
    if (hash == null || !hash.trim().matches("^[0-9a-fA-F]{64}$")) return Result.fail(404, "Транзакция отсутствует");
    return transactionRepository.findByHash(hash.trim().toLowerCase())
      .map(Result::ok)
      .orElseGet(() -> Result.fail(404, "Транзакция отсутствует"));
  }

  // Загружает непрерывный список блоков с транзакциями с указанной высоты.
  public List<Block> getBlocksFrom(long fromHeight, int limit) {
    List<Block> blocks = blockRepository.findBlocksFrom(fromHeight, limit);
    if (blocks.isEmpty()) return blocks;
    long minH = blocks.get(0).height;
    long maxH = blocks.get(blocks.size() - 1).height;
    List<Transaction> allTxs = transactionRepository.findByBlockHeightBetween(minH, maxH);
    Map<Long, List<Transaction>> txsByBlock = new HashMap<>();
    for (Transaction tx : allTxs) {
      if (tx.blockHeight != null) {
        txsByBlock.computeIfAbsent(tx.blockHeight, k -> new ArrayList<>()).add(tx);
      }
    }
    for (Block b : blocks) {
      b.setTransactions(txsByBlock.getOrDefault(b.height, Collections.emptyList()));
    }
    return blocks;
  }

  private Result<Block> toBlockResult(Optional<Block> opt) {
    if (opt.isEmpty()) return Result.fail(404, "Блок отсутствует");
    Block b = opt.get();
    enrichBlockWithTransactions(b);
    return Result.ok(b);
  }

  private void restorePendingMempool() {
    List<Transaction> pending = transactionRepository.findPending();
    pending.sort(TX_ORDER);
    Map<String, Account> accounts = new HashMap<>();
    for (Transaction tx : pending) {
      if (mempool.size() >= nodeConfig.maxMempoolSize || !isRestoredTxValid(tx, accounts)) {
        markTxFailed(tx);
        continue;
      }
      if (tx.blockHeight != null || tx.blockHash != null || tx.txIndex != 0) {
        tx.blockHeight = null;
        tx.blockHash = null;
        tx.txIndex = 0;
        transactionRepository.save(tx);
      }
      mempool.put(tx.hash, tx);
    }
  }

  private boolean isRestoredTxValid(Transaction tx, Map<String, Account> accounts) {
    Result<Void> valRes = ConsensusUtil.validateTransactionBasics(tx, nodeConfig.chainId);
    if (valRes.isError()) return false;
    Account sender = accounts.computeIfAbsent(tx.from, this::getAccount);
    try {
      long cost = Math.addExact(tx.amount, tx.fee);
      if (tx.nonce != sender.nonce || sender.balance < cost) return false;
      sender.nonce++;
      sender.balance = Math.subtractExact(sender.balance, cost);
      return true;
    } catch (ArithmeticException err) {
      return false;
    }
  }

  private void markTxFailed(Transaction tx) {
    tx.status = "failed";
    tx.blockHeight = null;
    tx.blockHash = null;
    tx.txIndex = 0;
    transactionRepository.save(tx);
  }

  private void autoMineTick() {
    try {
      if (!mempool.isEmpty()) mineBlock();
    } catch (Exception err) {
      log.error("Error during auto-mining tick", err);
    }
  }

  // Принимает генезис-блок удаленного пира при первой синхронизации чистой ноды.
  public boolean adoptPeerGenesisBlock(Block remoteGenesis) {
    return remoteGenesis != null && adoptPeerGenesisChain(List.of(remoteGenesis));
  }

  // Принимает цепочку блоков от удаленного пира при первой синхронизации чистой ноды с валидацией PoW.
  public boolean adoptPeerGenesisChain(List<Block> blocks) {
    if (blocks == null || blocks.isEmpty()) return false;
    Block remoteGenesis = blocks.get(0);
    if (remoteGenesis == null || remoteGenesis.height != 0 || !CryptoUtil.isValidAddress(remoteGenesis.minerAddress)) return false;
    if (nodeConfig.genesisAddress == null || nodeConfig.genesisAddress.isBlank()) {
      log.warn("Rejected peer genesis chain: local genesisAddress is not configured");
      return false;
    }
    if (!remoteGenesis.minerAddress.equalsIgnoreCase(nodeConfig.genesisAddress.trim())) {
      log.warn("Rejected peer genesis chain: minerAddress {} does not match configured genesisAddress {}",
          remoteGenesis.minerAddress, nodeConfig.genesisAddress);
      return false;
    }
    if (remoteGenesis.difficulty != nodeConfig.difficulty || remoteGenesis.txCount != 0 || remoteGenesis.nonce != 0) return false;
    if (remoteGenesis.transactions != null && !remoteGenesis.transactions.isEmpty()) return false;

    synchronized (chainLock) {
      Block current = getLatestBlockHeader();
      if (current.height > 0 || (blocks.size() == 1 && !mempool.isEmpty())) return false;
      if (remoteGenesis.timestamp != 0L) return false;
      Block expected = Block.createGenesis(nodeConfig.chainId, remoteGenesis.minerAddress, nodeConfig.difficulty, 0L);
      if (!expected.hash.equals(remoteGenesis.hash) || !expected.hash.equals(remoteGenesis.calculateHash())) return false;
      if (blocks.size() == 1 && current.hash.equals(expected.hash) && blockRepository.count() > 0) return true;

      Map<Long, String> checkpoints = nodeConfig.checkpoints;
      for (Block el : blocks) {
        String chk = checkpoints.get(el.height);
        if (chk != null && !chk.equalsIgnoreCase(el.hash)) {
          log.warn("Rejected peer genesis chain: checkpoint mismatch at height {} (expected {}, got {})", el.height, chk, el.hash);
          return false;
        }
      }

      long maxTime = System.currentTimeMillis() + ConsensusUtil.MAX_FUTURE_BLOCK_TIME_MS;
      List<Block> branch = blocks.subList(1, blocks.size());
      if (!validateBranch(branch, expected, maxTime)) return false;

      Map<String, Account> state = new HashMap<>();
      if (nodeConfig.genesisAllocation > 0) {
        state.put(expected.minerAddress, new Account(expected.minerAddress, nodeConfig.genesisAllocation, 0));
      }
      for (Block b : branch) {
        if (!applyBlockToSimState(new ApplyBlockContext(b, -1L, null), state)) return false;
      }

      List<Transaction> previousPending = new ArrayList<>(mempool.values());

      Boolean isOk = txTemplate.execute(status -> {
        blockRepository.deleteBlocksGreaterThan(-1);
        accountRepository.deleteAll();
        transactionRepository.deleteAll();
        blockRepository.save(expected);
        state.values().forEach(accountRepository::saveOrUpdate);
        branch.forEach(this::persistConfirmedBlock);
        return true;
      });
      if (!Boolean.TRUE.equals(isOk)) return false;

      mempool.clear();
      cachedLatestBlock.set(blocks.size() == 1 ? expected : blocks.get(blocks.size() - 1));

      Set<String> branchTxHashes = new HashSet<>();
      for (Block b : branch) {
        if (b.transactions != null) {
          for (Transaction tx : b.transactions) {
            if (tx.hash != null) branchTxHashes.add(tx.hash);
          }
        }
      }

      for (Transaction tx : previousPending) {
        if (tx == null || tx.hash == null || branchTxHashes.contains(tx.hash)) continue;
        if (ConsensusUtil.validateTransactionBasics(tx, nodeConfig.chainId).isError()) continue;
        Account sender = getAccount(tx.from);
        if (sender == null) continue;
        tx.status = "pending";
        tx.blockHeight = null;
        tx.blockHash = null;
        tx.txIndex = 0;
        transactionRepository.save(tx);
        mempool.put(tx.hash, tx);
      }
      cleanupStaleMempool();

      log.info("Adopted peer genesis and chain up to height {}", cachedLatestBlock.get().height);
      return true;
    }
  }

  private String resolveGenesisAddress() {
    if (nodeConfig.genesisAddress != null && !nodeConfig.genesisAddress.isBlank()) {
      return nodeConfig.genesisAddress.trim().toLowerCase();
    }
    return (nodeWalletService != null && nodeWalletService.address != null)
      ? nodeWalletService.address.toLowerCase() : "";
  }

  private void createGenesisBlock() {
    String minerAddr = resolveGenesisAddress();
    Block genesis = Block.createGenesis(nodeConfig.chainId, minerAddr, nodeConfig.difficulty, 0L);
    blockRepository.save(genesis);
    cachedLatestBlock.set(genesis);
    log.info("Genesis block initialized: {} (miner: {}, chainId: {})", genesis.hash, genesis.minerAddress, nodeConfig.chainId);
    allocateGenesisFundsIfEmpty();
  }

  private void allocateGenesisFundsIfEmpty() {
    if (nodeConfig.genesisAllocation <= 0) return;
    String targetAddr = resolveGenesisAddress();
    if (targetAddr.isEmpty()) return;
    Optional<Account> existing = accountRepository.findByAddress(targetAddr);
    if (existing.isPresent()) return;

    Account genesisAccount = new Account(targetAddr, nodeConfig.genesisAllocation, 0);
    accountRepository.saveOrUpdate(genesisAccount);
    log.info("Genesis allocation: credited {} atomic units ({} FOXT) to node address {}",
      nodeConfig.genesisAllocation,
      nodeConfig.genesisAllocation / 100_000_000.0,
      targetAddr);
  }

  private void enrichBlockWithTransactions(Block b) {
    if (b == null) return;
    b.setTransactions(transactionRepository.findByBlockHeight(b.height));
  }

  private Account getSimAccount(String addr, Long ancestorHeight, Map<String, Account> state) {
    String norm = addr != null ? addr.toLowerCase() : "";
    return state.computeIfAbsent(norm, k ->
      (ancestorHeight != null && ancestorHeight < 0L) ? new Account(k, 0, 0) : getAccount(k)
    );
  }

  private boolean applyBlockToSimState(ApplyBlockContext ctx, Map<String, Account> state) {
    Block b = ctx.block();
    List<Transaction> txs = b.transactions != null ? new ArrayList<>(b.transactions) : new ArrayList<>();
    if (!ConsensusUtil.isTransactionsUnique(txs)) return false;
    long totalFees = 0L;
    for (Transaction tx : txs) {
      if (!isTxStateTransitionValid(tx, ctx, state)) return false;
      try {
        totalFees = Math.addExact(totalFees, tx.fee);
      } catch (ArithmeticException err) {
        return false;
      }
    }
    return applyMinerReward(b, totalFees, ctx, state);
  }

  private boolean isTxStateTransitionValid(Transaction tx, ApplyBlockContext ctx, Map<String, Account> state) {
    Result<Void> valRes = ConsensusUtil.validateTransactionBasics(tx, nodeConfig.chainId);
    if (valRes.isError()) return false;
    if (isTxAlreadyConfirmed(tx.hash, ctx)) return false;
    Account sender = getSimAccount(tx.from, ctx.ancestorHeight(), state);
    Account recipient = getSimAccount(tx.to, ctx.ancestorHeight(), state);
    try {
      long cost = Math.addExact(tx.amount, tx.fee);
      if (sender.balance < cost || sender.nonce != tx.nonce) return false;
      sender.subtractBalance(cost);
      sender.incrementNonce();
      recipient.addBalance(tx.amount);
      return true;
    } catch (ArithmeticException err) {
      return false;
    }
  }

  private boolean isTxAlreadyConfirmed(String hash, ApplyBlockContext ctx) {
    if (ctx != null) {
      if (ctx.ancestorHeight() != null && ctx.ancestorHeight() < 0L) return false;
      if (ctx.confirmedHashes() != null && ctx.ancestorHeight() == null) {
        return ctx.confirmedHashes().contains(hash.toLowerCase());
      }
    }
    Optional<Transaction> opt = transactionRepository.findByHash(hash);
    if (opt.isEmpty() || !"confirmed".equalsIgnoreCase(opt.get().status)) return false;
    Long blockHeight = opt.get().blockHeight;
    Long ancestorHeight = ctx != null ? ctx.ancestorHeight() : null;
    return blockHeight != null && (ancestorHeight == null || blockHeight <= ancestorHeight);
  }

  private boolean applyMinerReward(Block b, long totalFees, ApplyBlockContext ctx, Map<String, Account> state) {
    if (b.minerAddress == null || b.minerAddress.isEmpty()) return true;
    try {
      long subsidy = ConsensusUtil.calculateBlockSubsidy(b.height, nodeConfig.miningReward, nodeConfig.halvingInterval);
      long reward = Math.addExact(subsidy, totalFees);
      Account miner = getSimAccount(b.minerAddress, ctx.ancestorHeight(), state);
      miner.addBalance(reward);
      return true;
    } catch (ArithmeticException err) {
      return false;
    }
  }

  private void persistConfirmedBlock(Block b) {
    if (b.transactions != null) {
      for (int i = 0; i < b.transactions.size(); i++) {
        Transaction tx = b.transactions.get(i);
        tx.status = "confirmed";
        tx.blockHeight = b.height;
        tx.blockHash = b.hash;
        tx.txIndex = i;
        transactionRepository.save(tx);
      }
    }
    blockRepository.save(b);
  }

  private boolean commitBlockState(Block b, Map<String, Account> state) {
    Boolean isSuccess = txTemplate.execute(status -> {
      for (Account acc : state.values()) {
        accountRepository.saveOrUpdate(acc);
      }
      persistConfirmedBlock(b);
      return true;
    });
    if (Boolean.TRUE.equals(isSuccess)) {
      cachedLatestBlock.set(b);
      if (b.transactions != null) {
        for (Transaction tx : b.transactions) {
          mempool.remove(tx.hash);
        }
      }
      cleanupStaleMempool();
      return true;
    }
    return false;
  }

  private void cleanupStaleMempool() {
    Map<String, List<Transaction>> bySender = groupMempoolBySender();
    for (Map.Entry<String, List<Transaction>> entry : bySender.entrySet()) {
      Account acc = getAccount(entry.getKey());
      long expectedNonce = acc.nonce;
      long availableBalance = acc.balance;
      for (Transaction tx : entry.getValue()) {
        if (tx.amount <= 0 || tx.fee < 0 || tx.amount > Long.MAX_VALUE - tx.fee) {
          mempool.remove(tx.hash);
          markTxFailed(tx);
          continue;
        }
        long required = tx.amount + tx.fee;
        if (tx.nonce != expectedNonce || availableBalance < required) {
          mempool.remove(tx.hash);
          markTxFailed(tx);
          continue;
        }
        expectedNonce++;
        availableBalance -= required;
      }
    }
  }

  private Result<Void> evictLowestFeeTx(Transaction tx) {
    Map<String, Transaction> tailBySender = new HashMap<>();
    for (Transaction el : mempool.values()) {
      if (el.from.equalsIgnoreCase(tx.from)) continue;
      tailBySender.merge(el.from.toLowerCase(), el, (a, b) -> b.nonce > a.nonce ? b : a);
    }
    Transaction minTx = tailBySender.values().stream()
      .min(EVICTION_ORDER)
      .orElse(null);
    if (minTx == null || minTx.fee > Long.MAX_VALUE - MIN_FEE_BUMP || tx.fee < minTx.fee + MIN_FEE_BUMP) {
      return Result.fail(429, "Mempool переполнен");
    }
    mempool.remove(minTx.hash);
    markTxFailed(minTx);
    log.warn("Mempool full: evicted tx {} in favor of tx {}", minTx.hash, tx.hash);
    return Result.ok(null);
  }

  private List<Transaction> selectValidTransactions() {
    Map<String, List<Transaction>> bySender = groupMempoolBySender();
    Map<String, Account> accounts = new HashMap<>();
    List<Transaction> valid = new ArrayList<>();
    while (valid.size() < ConsensusUtil.MAX_BLOCK_TXS) {
      SenderCandidate best = findBestSenderCandidate(bySender, accounts);
      if (best == null) break;
      bySender.get(best.sender.address).remove(0);
      valid.add(new Transaction(best.tx));
      best.sender.balance = best.newBalance;
      best.sender.nonce = best.newNonce;
    }
    return valid;
  }

  private Map<String, List<Transaction>> groupMempoolBySender() {
    Map<String, List<Transaction>> bySender = new HashMap<>();
    for (Transaction tx : mempool.values()) {
      bySender.computeIfAbsent(tx.from.toLowerCase(), k -> new ArrayList<>()).add(tx);
    }
    for (List<Transaction> list : bySender.values()) {
      list.sort(TX_ORDER);
    }
    return bySender;
  }

  private SenderCandidate findBestSenderCandidate(
    Map<String, List<Transaction>> bySender,
    Map<String, Account> accounts
  ) {
    SenderCandidate best = null;
    for (Map.Entry<String, List<Transaction>> entry : bySender.entrySet()) {
      List<Transaction> list = entry.getValue();
      Account sender = accounts.computeIfAbsent(entry.getKey(), this::getAccount);
      while (!list.isEmpty() && list.get(0).nonce < sender.nonce) {
        Transaction stale = list.remove(0);
        mempool.remove(stale.hash);
        markTxFailed(stale);
      }
      if (list.isEmpty()) continue;
      Transaction next = list.get(0);
      long req = next.amount + next.fee;
      if (next.nonce != sender.nonce || sender.balance < req) continue;
      if (isBetterCandidate(next, best)) {
        best = new SenderCandidate(sender, next, sender.balance - req, sender.nonce + 1);
      }
    }
    return best;
  }

  private Block commitMinedBlock(Block newBlock) {
    if (!getLatestBlockHeader().hash.equals(newBlock.previousHash)) {
      log.warn("Chain changed while mining block #{}, discarding", newBlock.height);
      return null;
    }
    if (!applyIncomingBlock(newBlock)) {
      log.error("Failed to commit mined block #{}", newBlock.height);
      return null;
    }
    log.info("Block mined successfully: height={}, hash={}, difficulty={}, txs={}",
      newBlock.height, newBlock.hash, newBlock.difficulty, newBlock.txCount);
    return newBlock;
  }

  private int findForkIndex(List<Block> newChain) {
    int idx = 0;
    while (idx < newChain.size()) {
      Block candidate = newChain.get(idx);
      if (candidate == null) break;
      Optional<Block> local = blockRepository.findByHeight(candidate.height);
      if (local.isPresent() && local.get().hash.equals(candidate.hash)) {
        idx++;
      } else break;
    }
    return idx;
  }

  private boolean validateBranch(List<Block> branch, Block ancestor, long maxTime) {
    for (int i = 0; i < branch.size(); i++) {
      Block b = branch.get(i);
      Block prev = (i == 0) ? ancestor : branch.get(i - 1);
      int expectedDiff = getBranchBlockDifficulty(prev, branch, ancestor, i);
      if (b.difficulty != expectedDiff) {
        log.warn("Branch rejected: block #{} difficulty mismatch (expected {}, got {})", b.height, expectedDiff, b.difficulty);
        return false;
      }
      String chk = nodeConfig.checkpoints.get(b.height);
      if (chk != null && !chk.equalsIgnoreCase(b.hash)) {
        log.warn("Branch rejected: checkpoint mismatch at height {} (expected {}, got {})", b.height, chk, b.hash);
        return false;
      }
      long mtp = getMedianTimePastForBranch(branch, ancestor, i);
      if (!ConsensusUtil.isValidBlockHeader(prev, b, expectedDiff, maxTime, nodeConfig.chainId, mtp)) return false;
    }
    return true;
  }

  private long getMedianTimePastForBranch(List<Block> branch, Block ancestor, int branchIdx) {
    List<Block> window = new ArrayList<>();
    for (int j = branchIdx - 1; j >= 0 && window.size() < ConsensusUtil.MTP_WINDOW; j--) {
      window.add(branch.get(j));
    }
    if (ancestor != null && window.size() < ConsensusUtil.MTP_WINDOW) {
      long remaining = ConsensusUtil.MTP_WINDOW - window.size();
      long from = Math.max(0L, ancestor.height - (remaining - 1));
      List<Block> dbBlocks = blockRepository.findBlocksBetween(from, ancestor.height);
      window.addAll(dbBlocks);
    }
    return ConsensusUtil.calculateMedianTimePast(window);
  }

  // MTP блока: ищем его позицию в ветке, иначе берём из БД
  private long getMedianTimePastAt(Block target, List<Block> branch, Block ancestor, int branchIdx) {
    if (target == null) return 0L;
    if (branch != null && !branch.isEmpty()) {
      for (int j = 0; j < branchIdx && j < branch.size(); j++) {
        if (branch.get(j).height == target.height) return getMedianTimePastForBranch(branch, ancestor, j + 1);
      }
    }
    return getMedianTimePast(target);
  }

  private int getBranchBlockDifficulty(Block prev, List<Block> branch, Block ancestor, int branchIdx) {
    if (prev == null || prev.height == 0) return nodeConfig.difficulty;
    int interval = nodeConfig.difficultyAdjustmentInterval;
    long nextHeight = prev.height + 1;
    if (interval <= 0 || nextHeight % interval != 0) {
      return prev.difficulty > 0 ? prev.difficulty : nodeConfig.difficulty;
    }
    long anchorHeight = Math.max(0, nextHeight - interval);
    Block anchor = findBlockInBranchOrDb(anchorHeight, branch, ancestor, branchIdx);
    if (anchor != null && anchor.timestamp == 0L) anchor = findBlockInBranchOrDb(1L, branch, ancestor, branchIdx);
    // Сложность считаем по MTP якоря и вершины одной и той же ветки
    long mtpAnchor = getMedianTimePastAt(anchor, branch, ancestor, branchIdx);
    long mtpPrev = getMedianTimePastAt(prev, branch, ancestor, branchIdx);
    return ConsensusUtil.calculateNextDifficulty(prev, anchor, nodeConfig, mtpAnchor, mtpPrev);
  }

  private Block findBlockInBranchOrDb(long height, List<Block> branch, Block ancestor, int branchIdx) {
    if (ancestor != null && ancestor.height == height) return ancestor;
    for (int i = 0; i < branchIdx; i++) {
      Block b = branch.get(i);
      if (b.height == height) return b;
    }
    return blockRepository.findByHeight(height).orElse(null);
  }

  private List<Block> collectRollbackBlocks(long fromHeight, long toHeight) {
    List<Block> list = new ArrayList<>();
    for (long h = fromHeight; h > toHeight; h--) {
      Optional<Block> opt = blockRepository.findByHeight(h);
      if (opt.isEmpty()) return Collections.emptyList();
      Block b = opt.get();
      enrichBlockWithTransactions(b);
      list.add(b);
    }
    return list;
  }

  // Откатывает блок чистой инверсией его дельт без проверки промежуточных балансов.
  private boolean rollbackBlockToSimState(Block b, Map<String, Account> state) {
    long totalFees = 0L;
    List<Transaction> txs = new ArrayList<>(b.transactions != null ? b.transactions : List.of());
    Collections.reverse(txs);
    for (Transaction el : txs) {
      Account sender = getSimAccount(el.from, null, state);
      Account recipient = getSimAccount(el.to, null, state);
      sender.addBalance(el.amount + el.fee);
      sender.nonce--;
      recipient.adjustBalanceUnchecked(-el.amount);
      totalFees += el.fee;
    }
    long reward = ConsensusUtil.calculateBlockSubsidy(b.height, nodeConfig.miningReward, nodeConfig.halvingInterval) + totalFees;
    getSimAccount(b.minerAddress, null, state).adjustBalanceUnchecked(-reward);
    return true;
  }

  private boolean simulateReorg(
    List<Block> rollback,
    List<Block> branch,
    Map<String, Account> state,
    long ancestorHeight
  ) {
    for (Block oldB : rollback) {
      if (!rollbackBlockToSimState(oldB, state)) return false;
    }
    for (Block b : branch) {
      ApplyBlockContext ctx = new ApplyBlockContext(b, ancestorHeight, null);
      if (!applyBlockToSimState(ctx, state)) return false;
    }
    for (Account acc : state.values()) {
      if (acc.balance < 0 || acc.nonce < 0) return false;
    }
    return true;
  }

  private boolean applyReorgCommit(ReorgContext ctx, Map<String, Account> state) {
    Set<String> confirmedInBranch = new HashSet<>();
    for (Block b : ctx.branch) {
      if (b.transactions != null) {
        for (Transaction tx : b.transactions) {
          confirmedInBranch.add(tx.hash);
        }
      }
    }
    Boolean isOk = txTemplate.execute(status -> {
      for (Block oldB : ctx.rollback) {
        if (oldB.transactions == null) continue;
        for (Transaction tx : oldB.transactions) {
          if (confirmedInBranch.contains(tx.hash)) continue;
          tx.status = "pending";
          tx.blockHeight = null;
          tx.blockHash = null;
          tx.txIndex = 0;
          transactionRepository.save(tx);
        }
      }
      blockRepository.deleteBlocksGreaterThan(ctx.ancestor.height);
      for (Account acc : state.values()) {
        accountRepository.saveOrUpdate(acc);
      }
      for (Block b : ctx.branch) {
        persistConfirmedBlock(b);
      }
      return true;
    });
    if (!Boolean.TRUE.equals(isOk)) return false;

    confirmedInBranch.forEach(mempool::remove);
    for (int i = ctx.rollback.size() - 1; i >= 0; i--) {
      Block oldB = ctx.rollback.get(i);
      if (oldB.transactions == null) continue;
      for (Transaction tx : oldB.transactions) {
        if (confirmedInBranch.contains(tx.hash)) continue;
        if (mempool.containsKey(tx.hash)) {
          mempool.put(tx.hash, tx);
          continue;
        }
        boolean isConflict = mempool.values().stream()
          .anyMatch(mTx -> mTx.from.equalsIgnoreCase(tx.from) && mTx.nonce == tx.nonce);
        if (isConflict || mempool.size() >= nodeConfig.maxMempoolSize) {
          markTxFailed(tx);
          continue;
        }
        mempool.put(tx.hash, tx);
      }
    }
    cachedLatestBlock.set(ctx.newLatest);
    cleanupStaleMempool();
    log.info("Chain reorganization applied: rolled back to height {}, new latest height {}", ctx.ancestor.height, ctx.newLatest.height);
    return true;
  }
}
