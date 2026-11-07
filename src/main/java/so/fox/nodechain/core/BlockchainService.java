package so.fox.nodechain.core;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
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

// Основной сервис логики блокчейна: состояние аккаунтов, mempool, консенсус и майнинг
@Service
public class BlockchainService {

  public static final int MAX_REORG_DEPTH = 100;

  private static final Logger log = LoggerFactory.getLogger(BlockchainService.class);

  private static final Comparator<Transaction> TX_ORDER = Comparator
    .comparingLong((Transaction t) -> t.nonce)
    .thenComparing(Comparator.comparingLong((Transaction t) -> t.fee).reversed())
    .thenComparingLong(t -> t.timestamp);

  // Оценивает рекомендуемую комиссию по уровню приоритета
  public static long getEstimatedFee(String priority) {
    if ("high".equalsIgnoreCase(priority)) return 100L;
    if ("low".equalsIgnoreCase(priority)) return 25L;
    return 50L;
  }

  private static ThreadFactory daemonFactory(String name) {
    return r -> {
      Thread t = new Thread(r, name);
      t.setDaemon(true);
      return t;
    };
  }

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

  // Устанавливает P2P менеджер при отложенной инициализации
  public void setP2pClientManager(P2pClientManager p2pClientManager) {
    this.p2pClientManager = p2pClientManager;
  }

  // Инициализирует блокчейн: генезис-блок, мемпул и фоновый авто-майнер
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
    minerExecutor = Executors.newSingleThreadScheduledExecutor(daemonFactory("blockchain-miner"));
    minerExecutor.scheduleWithFixedDelay(this::autoMineTick, interval, interval, TimeUnit.SECONDS);
    log.info("Auto-miner started with interval {}s", interval);
  }

  // Завершает работу фонового майнера при остановке сервиса
  @PreDestroy
  public void stop() {
    if (minerExecutor != null) minerExecutor.shutdownNow();
  }

  // Возвращает заголовок последнего блока цепочки
  public Block getLatestBlockHeader() {
    Block cached = cachedLatestBlock.get();
    if (cached != null) return cached;
    Block latest = blockRepository.findLatestBlock().orElseGet(() -> Block.createGenesis(resolveGenesisAddress()));
    cachedLatestBlock.set(latest);
    return latest;
  }

  // Ищет блок по номеру высоты, по криптографическому хешу или по ключевому слову "latest"
  public Result<Block> getBlockByIdentifier(String id) {
    if (id == null || id.trim().isEmpty()) return Result.fail(404, "Блок отсутствует");
    String trimmed = id.trim();
    if ("latest".equalsIgnoreCase(trimmed)) return toBlockResult(blockRepository.findLatestBlock());
    if (trimmed.length() <= 18 && trimmed.chars().allMatch(Character::isDigit)) {
      Optional<Block> opt = blockRepository.findByHeight(Long.parseLong(trimmed));
      if (opt.isPresent()) return toBlockResult(opt);
    }
    return toBlockResult(blockRepository.findByHash(trimmed));
  }

  // Возвращает аккаунт по указанному адресу или пустой объект без сохранения в базе данных
  public Account getAccount(String addr) {
    if (addr == null) return new Account("", 0, 0);
    String normalized = addr.toLowerCase();
    return accountRepository.findByAddress(normalized)
      .map(a -> new Account(a.address, a.balance, a.nonce))
      .orElseGet(() -> new Account(normalized, 0, 0));
  }

  // Сохраняет состояние аккаунта в базе данных
  public void saveAccount(Account acc) {
    accountRepository.saveOrUpdate(acc);
  }

  // Возвращает текущее состояние адреса и количество транзакций
  public Result<Account> getAccountState(String addr) {
    if (!CryptoUtil.isValidAddress(addr)) return Result.fail(400, "Некорректный формат адреса");
    String normalized = addr.toLowerCase();
    Optional<Account> opt = accountRepository.findByAddress(normalized);
    long cnt = transactionRepository.countByAddress(normalized);
    if (opt.isEmpty() && cnt == 0) return Result.fail(404, "Адрес еще не совершал транзакций и не имеет баланса");
    return Result.ok(opt.orElseGet(() -> new Account(normalized, 0, 0)));
  }

  // Вычисляет неподтвержденный баланс с защитой от переполнения
  public long calculateUnconfirmedBalance(String addr) {
    if (addr == null) return 0L;
    String normalized = addr.toLowerCase();
    Account acc = accountRepository.findByAddress(normalized).orElse(new Account(normalized, 0, 0));
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
    return balance;
  }

  // Принимает и валидирует входящую транзакцию для mempool
  public Result<Transaction> submitTransaction(Transaction tx, boolean isBroadcast) {
    if (tx.hash == null || tx.hash.isEmpty()) tx.hash = tx.calculateHash();
    Result<Void> validation = ConsensusUtil.validateTransactionBasics(tx);
    if (validation.isError()) return Result.fail(validation.status, validation.error);

    synchronized (chainLock) {
      boolean isNonceInMempool = mempool.values().stream()
        .anyMatch(m -> m.from.equalsIgnoreCase(tx.from) && m.nonce == tx.nonce);
      if (isNonceInMempool) return Result.fail(409, "Конфликт nonce");
      Account sender = getAccount(tx.from);
      long expectedNonce = sender.nonce;
      for (Transaction m : mempool.values()) {
        if (m.from.equalsIgnoreCase(tx.from)) {
          expectedNonce = Math.max(expectedNonce, m.nonce + 1);
        }
      }
      if (tx.nonce != expectedNonce) return Result.fail(409, "Конфликт nonce");
      long unconfirmed = calculateUnconfirmedBalance(tx.from);
      long required = tx.amount + tx.fee;
      if (unconfirmed < required) return Result.fail(409, "Недостаточно средств");
      if (mempool.size() >= nodeConfig.maxMempoolSize) {
        Result<Void> evictRes = evictLowestFeeTx(tx);
        if (evictRes.isError()) return Result.fail(evictRes.status, evictRes.error);
      }
      tx.status = "pending";
      transactionRepository.save(tx);
      mempool.put(tx.hash, tx);
    }

    log.info("New pending transaction: {} (amount: {}, fee: {})", tx.hash, tx.amount, tx.fee);
    if (isBroadcast && p2pClientManager != null) p2pClientManager.broadcast(OpCode.NEW_TX_ANNOUNCE, tx, null);
    return Result.ok(tx);
  }

  // Майнинг нового блока: выбор транзакций, вычисление PoW и сохранение блока
  public Block mineBlock() {
    if (!isMining.compareAndSet(false, true)) {
      log.warn("Mining already in progress, skipping concurrent attempt");
      return null;
    }
    try {
      List<Transaction> validTxs;
      Block latest;
      String minerAddr;

      synchronized (chainLock) {
        validTxs = selectValidTransactions();
        latest = getLatestBlockHeader();
        minerAddr = nodeWalletService.address;
      }

      Block newBlock = new Block(latest.height + 1, latest.hash, minerAddr, validTxs);
      boolean isMined = newBlock.mineBlock(nodeConfig.difficulty);
      if (!isMined) {
        log.warn("Mining interrupted or aborted for block #{}", newBlock.height);
        return null;
      }

      synchronized (chainLock) {
        return commitMinedBlock(newBlock, validTxs);
      }
    } finally {
      isMining.set(false);
    }
  }

  // Валидирует и транзакционно применяет входящий блок от пира к цепочке
  public boolean applyIncomingBlock(Block b) {
    if (b == null || b.hash == null) return false;
    synchronized (chainLock) {
      if (blockRepository.findByHash(b.hash).isPresent()) return true;
      Block latest = getLatestBlockHeader();
      long maxTime = System.currentTimeMillis() + 600_000L;
      if (!ConsensusUtil.isValidBlockHeader(latest, b, nodeConfig.difficulty, maxTime)) return false;
      Map<String, Account> state = new HashMap<>();
      ApplyBlockContext ctx = new ApplyBlockContext(b, false, null);
      if (!applyBlockToSimState(ctx, state)) return false;
      boolean isCommitted = commitBlockState(b, state);
      if (!isCommitted) return false;
      log.info("Applied incoming block #{} ({})", b.height, b.hash);
      return true;
    }
  }

  // Реорганизует цепочку блоков при наличии валидной ветки с большим chainwork
  public boolean reorganizeChain(List<Block> newChain) {
    if (newChain == null || newChain.isEmpty()) return false;
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
      List<Block> rollback = collectRollbackBlocks(currentLatest.height, ancestor.height);
      BigInteger curWork = ConsensusUtil.calculateChainWork(rollback, nodeConfig.difficulty);
      BigInteger branchWork = ConsensusUtil.calculateChainWork(branch, nodeConfig.difficulty);
      if (branchWork.compareTo(curWork) <= 0) return false;
      long maxTime = System.currentTimeMillis() + 600_000L;
      if (!validateBranch(branch, ancestor, maxTime)) return false;
      Map<String, Account> state = new HashMap<>();
      if (!simulateReorg(rollback, branch, state, ancestor.height)) return false;
      ReorgContext ctx = new ReorgContext(ancestor, newLatest, rollback, branch);
      applyReorgCommit(ctx, state);
      return true;
    }
  }

  // Проверяет наличие блока с указанным хешем
  public boolean isBlockPresent(String hash) {
    if (hash == null) return false;
    return blockRepository.findByHash(hash).isPresent();
  }

  // Возвращает список всех текущих транзакций в mempool
  public List<Transaction> getMempoolTransactions() {
    return new ArrayList<>(mempool.values());
  }

  // Возвращает транзакции адреса с пагинацией (регистронезависимо)
  public Result<List<Transaction>> getAddressTransactions(String addr, int limit, int offset) {
    if (!CryptoUtil.isValidAddress(addr)) return Result.fail(400, "Некорректный формат адреса");
    return Result.ok(transactionRepository.findByAddress(addr.toLowerCase(), limit, offset));
  }

  // Возвращает общее количество транзакций адреса (регистронезависимо)
  public Result<Long> countAddressTransactions(String addr) {
    if (!CryptoUtil.isValidAddress(addr)) return Result.fail(400, "Некорректный формат адреса");
    return Result.ok(transactionRepository.countByAddress(addr.toLowerCase()));
  }

  // Находит транзакцию по ее криптографическому хешу
  public Result<Transaction> getTransactionByHash(String hash) {
    if (hash == null) return Result.fail(404, "Транзакция отсутствует");
    return transactionRepository.findByHash(hash)
      .map(Result::ok)
      .orElseGet(() -> Result.fail(404, "Транзакция отсутствует"));
  }

  // Загружает непрерывный список блоков с транзакциями с указанной высоты
  public List<Block> getBlocksFrom(long fromHeight, int limit) {
    List<Block> blocks = blockRepository.findBlocksFrom(fromHeight, limit);
    for (Block b : blocks) {
      enrichBlockWithTransactions(b);
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
    Map<String, Long> expectedNonces = new HashMap<>();
    Map<String, Long> tempBalances = new HashMap<>();
    for (Transaction tx : pending) {
      if (mempool.size() >= nodeConfig.maxMempoolSize) {
        markTxFailed(tx);
        continue;
      }
      if (!isRestoredTxValid(tx, expectedNonces, tempBalances)) {
        markTxFailed(tx);
        continue;
      }
      mempool.put(tx.hash, tx);
    }
  }

  private boolean isRestoredTxValid(Transaction tx, Map<String, Long> expectedNonces, Map<String, Long> tempBalances) {
    Result<Void> valRes = ConsensusUtil.validateTransactionBasics(tx);
    if (valRes.isError()) return false;
    Account sender = getAccount(tx.from);
    long expNonce = expectedNonces.computeIfAbsent(tx.from, k -> sender.nonce);
    long curBal = tempBalances.computeIfAbsent(tx.from, k -> sender.balance);
    try {
      long cost = Math.addExact(tx.amount, tx.fee);
      if (tx.nonce != expNonce || curBal < cost) return false;
      expectedNonces.put(tx.from, expNonce + 1);
      tempBalances.put(tx.from, Math.subtractExact(curBal, cost));
      return true;
    } catch (ArithmeticException err) {
      return false;
    }
  }

  private void markTxFailed(Transaction tx) {
    tx.status = "failed";
    transactionRepository.save(tx);
  }

  private void autoMineTick() {
    try {
      if (!mempool.isEmpty()) mineBlock();
    } catch (Exception err) {
      log.error("Error during auto-mining tick", err);
    }
  }

  // Принимает генезис-блок удаленного пира при первой синхронизации чистой ноды
  public boolean adoptPeerGenesisBlock(Block remoteGenesis) {
    if (remoteGenesis == null || !CryptoUtil.isValidAddress(remoteGenesis.minerAddress)) return false;
    Block expected = Block.createGenesis(remoteGenesis.minerAddress);
    if (!expected.hash.equals(remoteGenesis.hash) || remoteGenesis.txCount != 0
        || (remoteGenesis.transactions != null && !remoteGenesis.transactions.isEmpty())) return false;
    synchronized (chainLock) {
      Block current = getLatestBlockHeader();
      if (current.height > 0 || !mempool.isEmpty()) return false;
      if (current.hash.equals(remoteGenesis.hash)) return true;
      if (nodeConfig.genesisAddress != null && !nodeConfig.genesisAddress.isBlank()) return false;

      log.info("Adopting peer genesis block #0: hash={}, miner={}", remoteGenesis.hash, remoteGenesis.minerAddress);
      txTemplate.execute(status -> {
        blockRepository.deleteBlocksGreaterThan(-1);
        blockRepository.save(remoteGenesis);
        if (nodeConfig.genesisAllocation > 0) {
          String oldLocalAddr = resolveGenesisAddress();
          if (!oldLocalAddr.isEmpty() && !oldLocalAddr.equalsIgnoreCase(remoteGenesis.minerAddress)) {
            accountRepository.findByAddress(oldLocalAddr).ifPresent(oldAcc -> {
              accountRepository.saveOrUpdate(new Account(oldLocalAddr, 0, 0));
            });
          }
          Account genesisAcc = new Account(remoteGenesis.minerAddress.toLowerCase(), nodeConfig.genesisAllocation, 0);
          accountRepository.saveOrUpdate(genesisAcc);
        }
        return true;
      });
      cachedLatestBlock.set(remoteGenesis);
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
    Block genesis = Block.createGenesis(minerAddr);
    blockRepository.save(genesis);
    cachedLatestBlock.set(genesis);
    log.info("Genesis block initialized: {} (miner: {})", genesis.hash, genesis.minerAddress);
    allocateGenesisFundsIfEmpty();
  }

  private void allocateGenesisFundsIfEmpty() {
    if (nodeConfig.genesisAllocation <= 0) return;
    String targetAddr = resolveGenesisAddress();
    if (targetAddr.isEmpty() || !CryptoUtil.isValidAddress(targetAddr)) return;
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

  private boolean applyBlockToSimState(ApplyBlockContext ctx, Map<String, Account> state) {
    Block b = ctx.block();
    List<Transaction> txs = b.transactions != null ? new ArrayList<>(b.transactions) : new ArrayList<>();
    if (!ctx.isRollback() && !ConsensusUtil.areTransactionsUnique(txs)) return false;
    if (ctx.isRollback()) Collections.reverse(txs);
    long totalFees = 0L;
    for (Transaction tx : txs) {
      if (!isTxStateTransitionValid(tx, ctx, state)) return false;
      try {
        totalFees = Math.addExact(totalFees, tx.fee);
      } catch (ArithmeticException err) {
        return false;
      }
    }
    return applyMinerReward(b, totalFees, ctx.isRollback(), state);
  }

  private boolean isTxStateTransitionValid(Transaction tx, ApplyBlockContext ctx, Map<String, Account> state) {
    if (!ctx.isRollback()) {
      Result<Void> valRes = ConsensusUtil.validateTransactionBasics(tx);
      if (valRes.isError()) return false;
      if (isTxAlreadyConfirmed(tx.hash, ctx.ancestorHeight())) return false;
    }
    Account sender = state.computeIfAbsent(tx.from, this::getAccount);
    Account recipient = state.computeIfAbsent(tx.to, this::getAccount);
    try {
      long cost = Math.addExact(tx.amount, tx.fee);
      if (ctx.isRollback()) {
        if (recipient.balance < tx.amount || sender.nonce < 1) return false;
        sender.addBalance(cost);
        sender.nonce--;
        recipient.subtractBalance(tx.amount);
        return true;
      }
      if (sender.balance < cost || sender.nonce != tx.nonce) return false;
      sender.subtractBalance(cost);
      sender.incrementNonce();
      recipient.addBalance(tx.amount);
      return true;
    } catch (ArithmeticException err) {
      return false;
    }
  }

  private boolean isTxAlreadyConfirmed(String hash, Long ancestorHeight) {
    Optional<Transaction> opt = transactionRepository.findByHash(hash);
    if (opt.isEmpty() || !"confirmed".equalsIgnoreCase(opt.get().status)) return false;
    Long blockHeight = opt.get().blockHeight;
    return blockHeight != null && (ancestorHeight == null || blockHeight <= ancestorHeight);
  }

  private boolean applyMinerReward(Block b, long totalFees, boolean isRollback, Map<String, Account> state) {
    if (b.minerAddress == null || b.minerAddress.isEmpty()) return true;
    try {
      long subsidy = ConsensusUtil.calculateBlockSubsidy(b.height, nodeConfig.miningReward, nodeConfig.halvingInterval);
      long reward = Math.addExact(subsidy, totalFees);
      String minerNorm = b.minerAddress.toLowerCase();
      Account miner = state.computeIfAbsent(minerNorm, this::getAccount);
      if (isRollback) {
        if (miner.balance < reward) return false;
        miner.subtractBalance(reward);
        return true;
      }
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
    List<String> toRemove = new ArrayList<>();
    for (Transaction tx : mempool.values()) {
      Optional<Account> opt = accountRepository.findByAddress(tx.from);
      if (opt.isEmpty() || tx.nonce < opt.get().nonce
          || (tx.nonce == opt.get().nonce && opt.get().balance < (tx.amount + tx.fee))) {
        toRemove.add(tx.hash);
        markTxFailed(tx);
      }
    }
    for (String hash : toRemove) {
      mempool.remove(hash);
    }
  }

  private Result<Void> evictLowestFeeTx(Transaction tx) {
    Transaction minTx = mempool.values().stream()
      .filter(t -> mempool.values().stream().noneMatch(o -> o.from.equals(t.from) && o.nonce > t.nonce))
      .min(Comparator.comparingLong(t -> t.fee))
      .orElse(null);
    long minRequiredFee = (minTx != null) ? minTx.fee + 5L : 1L;
    if (minTx == null || tx.fee < minRequiredFee) return Result.fail(429, "Mempool переполнен");
    mempool.remove(minTx.hash);
    markTxFailed(minTx);
    log.warn("Mempool full: evicted tx {} in favor of tx {}", minTx.hash, tx.hash);
    return Result.ok(null);
  }

  private List<Transaction> selectValidTransactions() {
    Map<String, List<Transaction>> bySender = groupMempoolBySender();
    Map<String, Long> balances = new HashMap<>();
    Map<String, Long> nonces = new HashMap<>();
    List<Transaction> valid = new ArrayList<>();
    boolean isProgress = true;
    while (isProgress && valid.size() < 500) {
      isProgress = false;
      SenderCandidate best = findBestSenderCandidate(bySender, balances, nonces);
      if (best == null) break;
      bySender.get(best.sender).remove(0);
      valid.add(best.tx);
      balances.put(best.sender, best.newBalance);
      nonces.put(best.sender, best.newNonce);
      isProgress = true;
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
    Map<String, Long> balances,
    Map<String, Long> nonces
  ) {
    String bestSender = null;
    Transaction bestTx = null;
    for (Map.Entry<String, List<Transaction>> entry : bySender.entrySet()) {
      List<Transaction> list = entry.getValue();
      if (list.isEmpty()) continue;
      Transaction next = list.get(0);
      Account sender = getAccount(next.from);
      long curBal = balances.getOrDefault(entry.getKey(), sender.balance);
      long curNonce = nonces.getOrDefault(entry.getKey(), sender.nonce);
      if (next.nonce < curNonce) {
        list.remove(0);
        mempool.remove(next.hash);
        markTxFailed(next);
        continue;
      }
      long req = next.amount + next.fee;
      if (next.nonce != curNonce || curBal < req) continue;
      if (bestTx == null || next.fee > bestTx.fee || (next.fee == bestTx.fee && next.timestamp < bestTx.timestamp)) {
        bestTx = next;
        bestSender = entry.getKey();
      }
    }
    if (bestTx == null) return null;
    long curBal = balances.getOrDefault(bestSender, getAccount(bestTx.from).balance);
    long curNonce = nonces.getOrDefault(bestSender, getAccount(bestTx.from).nonce);
    return new SenderCandidate(bestSender, bestTx, curBal - (bestTx.amount + bestTx.fee), curNonce + 1);
  }

  private Block commitMinedBlock(Block newBlock, List<Transaction> validTxs) {
    Block currentLatest = getLatestBlockHeader();
    if (!currentLatest.hash.equals(newBlock.previousHash)) {
      log.warn("Chain changed while mining block #{}, discarding", newBlock.height);
      return null;
    }
    if (!ConsensusUtil.isValidProofOfWork(newBlock.hash, nodeConfig.difficulty)
        || !newBlock.hash.equals(newBlock.calculateHash())) {
      log.error("Commit rejected: block #{} has invalid PoW or hash mismatch", newBlock.height);
      return null;
    }

    Map<String, Account> state = new HashMap<>();
    ApplyBlockContext ctx = new ApplyBlockContext(newBlock, false, null);
    if (!applyBlockToSimState(ctx, state)) {
      log.error("Failed to apply state transition for block #{}", newBlock.height);
      return null;
    }

    boolean isCommitted = commitBlockState(newBlock, state);
    if (!isCommitted) return null;

    log.info("Block mined successfully: height={}, hash={}, txs={}", newBlock.height, newBlock.hash, validTxs.size());
    if (p2pClientManager != null) p2pClientManager.broadcast(OpCode.NEW_BLOCK_ANNOUNCE, newBlock, null);
    return newBlock;
  }

  private int findForkIndex(List<Block> newChain) {
    int idx = 0;
    while (idx < newChain.size()) {
      Block candidate = newChain.get(idx);
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
      if (!ConsensusUtil.isValidBlockHeader(prev, b, nodeConfig.difficulty, maxTime)) return false;
    }
    return true;
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

  private boolean simulateReorg(
    List<Block> rollback,
    List<Block> branch,
    Map<String, Account> state,
    long ancestorHeight
  ) {
    for (Block oldB : rollback) {
      ApplyBlockContext ctx = new ApplyBlockContext(oldB, true, ancestorHeight);
      if (!applyBlockToSimState(ctx, state)) return false;
    }
    for (Block b : branch) {
      ApplyBlockContext ctx = new ApplyBlockContext(b, false, ancestorHeight);
      if (!applyBlockToSimState(ctx, state)) return false;
    }
    return true;
  }

  private void applyReorgCommit(ReorgContext ctx, Map<String, Account> state) {
    txTemplate.execute(status -> {
      for (Block oldB : ctx.rollback) {
        if (oldB.transactions == null) continue;
        for (Transaction tx : oldB.transactions) {
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
    for (Block oldB : ctx.rollback) {
      if (oldB.transactions == null) continue;
      for (Transaction tx : oldB.transactions) {
        mempool.put(tx.hash, tx);
      }
    }
    for (Block b : ctx.branch) {
      if (b.transactions != null) {
        for (Transaction tx : b.transactions) {
          mempool.remove(tx.hash);
        }
      }
    }
    cachedLatestBlock.set(ctx.newLatest);
    cleanupStaleMempool();
    log.info("Chain reorganization applied: rolled back to height {}, new latest height {}", ctx.ancestor.height, ctx.newLatest.height);
  }

  private record ApplyBlockContext(Block block, boolean isRollback, Long ancestorHeight) {}
  private record SenderCandidate(String sender, Transaction tx, long newBalance, long newNonce) {}
  private record ReorgContext(Block ancestor, Block newLatest, List<Block> rollback, List<Block> branch) {}
}
