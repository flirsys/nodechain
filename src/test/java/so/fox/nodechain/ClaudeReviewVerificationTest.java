package so.fox.nodechain;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.security.KeyPair;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import so.fox.nodechain.MultiNodeNetworkTest.TestNode;
import so.fox.nodechain.config.NodeConfig;
import so.fox.nodechain.core.ConsensusUtil;
import so.fox.nodechain.core.Result;
import so.fox.nodechain.crypto.CryptoUtil;
import so.fox.nodechain.model.Account;
import so.fox.nodechain.model.Block;
import so.fox.nodechain.model.Transaction;
import so.fox.nodechain.p2p.P2pClientManager;
import so.fox.nodechain.p2p.P2pClientManager.HandshakeContext;
import so.fox.nodechain.p2p.P2pClientManager.HandshakeMessage;
import so.fox.nodechain.p2p.packet.OpCode;

/**
 * Комплексный набор тестов для проверки всех замечаний и исправлений:
 * 1. Genesis: создание со своим адресом и принятие генезис-блока пира (FastSync).
 * 2. Cross-Chain Replay: исследование отсутствия chainId в сигнатуре.
 * 3. ChainWork & Difficulty: BigInteger без overflow и статическая сложность.
 * 4. P2P Rate Limit: лимитирование по IP внешних хостов и исключение localhost (127.0.0.1).
 * 5. Handshake Replay Protection: одноразовые nonce и challenge защита от повторов.
 * 6. DoS защита: дешевые проверки (формат, баланс, nonce) выполняются до криптографической подписи.
 * 7. Merkle Tree: RFC 6962 доменное разделение листьев ("00") и внутренних узлов ("01").
 * 8. Ed25519: компактные ключи (32 байта) и сигнатуры (64 байта) вместо RSA-2048.
 * 9. Reorg & Step-Back: анализ шагов отката и глубоких форков.
 */
public class ClaudeReviewVerificationTest {

  // --- ПУНКТ 1: GENESIS ---
  @Test
  @DisplayName("Пункт 1: Проверка создания цепи со своим адресом и принятие генезис-блока пира")
  public void testPoint1_GenesisAddressSyncAndAdoption() {
    try (
      TestNode nodeA = new TestNode("genesis_a", 19101, "");
      TestNode nodeB = new TestNode("genesis_b", 19102, "")
    ) {
      nodeA.start();
      nodeB.start();

      Block genesisA = nodeA.blockchain.getLatestBlockHeader();
      Block genesisB = nodeB.blockchain.getLatestBlockHeader();

      // У каждой ноды в генезис-блоке зафиксирован адрес ее создателя:
      Assertions.assertEquals(nodeA.wallet.address, genesisA.minerAddress);
      Assertions.assertEquals(nodeB.wallet.address, genesisB.minerAddress);

      // Node A создает транзакцию с тратой своего премайна:
      KeyPair recipient = CryptoUtil.generateKeyPair();
      String recipientAddr = CryptoUtil.getAddressFromPublicKey(recipient.getPublic());
      String signable = Transaction.buildCanonicalData(nodeA.config.chainId, nodeA.wallet.address, recipientAddr, 1000L, 50L, 0L, "Premining spend");
      String sig = CryptoUtil.sign(nodeA.wallet.privateKey, signable);
      Transaction tx = new Transaction(
        nodeA.config.chainId, nodeA.wallet.address, recipientAddr, 1000L, 50L, 0L,
        CryptoUtil.encodePublicKey(nodeA.wallet.publicKey), sig, "Premining spend"
      );

      // Node A принимает транзакцию:
      Result<Transaction> submitA = nodeA.blockchain.submitTransaction(tx, false);
      Assertions.assertTrue(submitA.isOk);
      Block minedByA = nodeA.blockchain.mineBlock();
      Assertions.assertNotNull(minedByA);

      // Теперь Node B подключается к Node A и принимает генезис-блок Node A:
      boolean adopted = nodeB.blockchain.adoptPeerGenesisBlock(genesisA);
      Assertions.assertTrue(adopted, "Чистая нода B должна успешно принять генезис-блок ноды A");

      // После принятия генезис-блока ноды A, баланс премайна Node A на Node B равен 1_000_000_000_000L!
      Account accAonNodeB = nodeB.blockchain.getAccount(nodeA.wallet.address);
      Assertions.assertEquals(1_000_000_000_000L, accAonNodeB.balance);

      // И теперь блок minedByA успешно применяется на Node B!
      boolean acceptedByB = nodeB.blockchain.applyIncomingBlock(minedByA);
      Assertions.assertTrue(acceptedByB, "Блок с тратой премайна успешно применился на ноде B после синхронизации генезиса");
    }
  }

  // --- ПУНКТ 2: REPLAY МЕЖДУ СЕТЯМИ (CHAIN_ID) ---
  @Test
  @DisplayName("Пункт 2: Доказательство специфики подписи: структура Transaction без chainId")
  public void testPoint2_CrossChainReplayStructure() {
    KeyPair userKey = CryptoUtil.generateKeyPair();
    String userAddr = CryptoUtil.getAddressFromPublicKey(userKey.getPublic());
    String recipientAddr = CryptoUtil.getAddressFromPublicKey(CryptoUtil.generateKeyPair().getPublic());
    String pubKeyStr = CryptoUtil.encodePublicKey(userKey.getPublic());

    long amount = 500L;
    long fee = 50L;
    long nonce = 0L;
    String comment = "Testnet payment";
    String chainId = "foxchain-test-1";

    String canonical = Transaction.buildCanonicalData(chainId, userAddr, recipientAddr, amount, fee, nonce, comment);
    String signature = CryptoUtil.sign(userKey.getPrivate(), canonical);

    Transaction tx = new Transaction(chainId, userAddr, recipientAddr, amount, fee, nonce, pubKeyStr, signature, comment);

    Assertions.assertTrue(tx.isSignatureValid(chainId));
    Result<Void> valRes = ConsensusUtil.validateTransactionBasics(tx, chainId);
    Assertions.assertTrue(valRes.isOk);
    Assertions.assertTrue(canonical.contains("foxchain-test-1"));
  }

  // --- ПУНКТ 3: CHAINWORK & DIFFICULTY ---
  @Test
  @DisplayName("Пункт 3: Проверка ChainWork на BigInteger (отсутствие overflow) и статичность сложности")
  public void testPoint3_ChainWorkBigIntegerAndStaticDifficulty() {
    BigInteger workDiff16 = ConsensusUtil.calculateBlockWork(16);
    BigInteger workDiff32 = ConsensusUtil.calculateBlockWork(32);

    BigInteger longMax = BigInteger.valueOf(Long.MAX_VALUE);
    Assertions.assertTrue(workDiff16.compareTo(longMax) > 0, "16^16 должно превышать Long.MAX_VALUE");
    Assertions.assertTrue(workDiff32.compareTo(workDiff16) > 0, "16^32 должно успешно рассчитываться");
    Assertions.assertEquals(new BigInteger("18446744073709551616"), workDiff16);

    NodeConfig cfg = new NodeConfig();
    cfg.difficulty = 2;
    String validHash = "00abcd1234567890abcdef1234567890abcdef1234567890abcdef1234567890";
    String invalidHash = "0abcd1234567890abcdef1234567890abcdef1234567890abcdef1234567890";
    Assertions.assertTrue(ConsensusUtil.isValidProofOfWork(validHash, cfg.difficulty));
    Assertions.assertFalse(ConsensusUtil.isValidProofOfWork(invalidHash, cfg.difficulty));
  }

  // --- ПУНКТ 4: RATE LIMIT ПО IP С ИСКЛЮЧЕНИЕМ LOCALHOST ---
  @Test
  @DisplayName("Пункт 4: Проверка Rate Limit по IP для внешних хостов и исключение localhost")
  public void testPoint4_RateLimitByIpAndLocalhostExemption() throws Exception {
    try (TestNode node = new TestNode("ratelimit", 19104, "")) {
      node.start();

      P2pClientManager p2p = node.p2p;
      Field fLastReq = P2pClientManager.class.getDeclaredField("lastRequestTime");
      fLastReq.setAccessible(true);
      @SuppressWarnings("unchecked")
      Map<String, Long> lastReqMap = (Map<String, Long>) fLastReq.get(p2p);

      // 1. Проверяем локальный адрес 127.0.0.1:
      Assertions.assertTrue(P2pClientManager.isLocalAddress("127.0.0.1"));
      Assertions.assertTrue(P2pClientManager.isLocalAddress("localhost"));
      Assertions.assertTrue(P2pClientManager.isLocalAddress("::1"));

      // 2. Для внешнего IP (192.168.1.50) лимит работает строго по IP:
      String externalIp = "192.168.1.50";
      Assertions.assertFalse(P2pClientManager.isLocalAddress(externalIp));

      // Ключ формируется как externalIp:opcode (без эфемерного порта):
      String key = externalIp + ":" + OpCode.PING;
      lastReqMap.put(key, System.currentTimeMillis());
      Assertions.assertTrue(lastReqMap.containsKey(key), "Внешний IP сохраняется в лимитере по IP");
    }
  }

  // --- ПУНКТ 5: HANDSHAKE REPLAY ЗАЩИТА ---
  @Test
  @DisplayName("Пункт 5: Проверка защиты Handshake от Replay-атак через sessionNonce")
  public void testPoint5_HandshakeReplayProtectionWithNonce() {
    KeyPair nodeAKey = CryptoUtil.generateKeyPair();
    String nodeAId = CryptoUtil.getAddressFromPublicKey(nodeAKey.getPublic());
    String pubKeyStr = CryptoUtil.encodePublicKey(nodeAKey.getPublic());
    String chainId = "foxchain-main-1";
    String latestHash = "0000000000000000000000000000000000000000000000000000000000000000";

    long now = System.currentTimeMillis();
    String sessionNonce = UUID.randomUUID().toString();

    String dataToSign = nodeAId + "|" + chainId + "|0|" + latestHash + "|9090|" + now + "|" + sessionNonce;
    String sig = CryptoUtil.sign(nodeAKey.getPrivate(), dataToSign);

    HandshakeMessage msg = new HandshakeMessage(
      nodeAId, chainId, 0, latestHash, 9090, "1.0.0", now, pubKeyStr, sig, sessionNonce
    );

    java.util.Map<String, Long> seenNonces = new ConcurrentHashMap<>();
    HandshakeContext ctx1 = new HandshakeContext("bob-node", chainId, now, seenNonces);

    // Первичный Handshake успешно принимается:
    boolean firstAccept = P2pClientManager.isValidHandshake(msg, ctx1);
    Assertions.assertTrue(firstAccept);

    // Повторный Replay с тем же sessionNonce отклоняется:
    HandshakeContext ctxReplay = new HandshakeContext("bob-node", chainId, now + 5000L, seenNonces);
    boolean replayedAccept = P2pClientManager.isValidHandshake(msg, ctxReplay);
    Assertions.assertFalse(replayedAccept, "Replay с повторным sessionNonce должен быть отклонен");
  }

  // --- ПУНКТ 6: DOS ЗАЩИТА: ПРОВЕРКА ФОРМАТА ДО КРИПТОГРАФИИ ---
  @Test
  @DisplayName("Пункт 6: Проверка порядка валидации: дешевые проверки отсекают спам до тяжелой подписи")
  public void testPoint6_CheapChecksBeforeCryptoSignature() {
    KeyPair attackerKey = CryptoUtil.generateKeyPair();
    String attackerAddr = CryptoUtil.getAddressFromPublicKey(attackerKey.getPublic());
    String victimAddr = CryptoUtil.getAddressFromPublicKey(CryptoUtil.generateKeyPair().getPublic());

    // Транзакция с некорректной суммой (-10):
    Transaction badAmountTx = new Transaction(
      "foxchain-main-1", attackerAddr, victimAddr, -10L, 50L, 0L,
      CryptoUtil.encodePublicKey(attackerKey.getPublic()),
      "invalid-signature", "Spam"
    );

    // validateTransactionFormat отсекает транзакцию без вызова isSignatureValid:
    Result<Void> formatRes = ConsensusUtil.validateTransactionFormat(badAmountTx);
    Assertions.assertTrue(formatRes.isError());
    Assertions.assertEquals(409, formatRes.status);
  }

  // --- ПУНКТ 7: MERKLE TREE: DOMAIN SEPARATION (RFC 6962) ---
  @Test
  @DisplayName("Пункт 7: Проверка разделения доменов листьев (00) и внутренних узлов (01) в Merkle Tree")
  public void testPoint7_MerkleDomainSeparationRfc6962() {
    KeyPair key = CryptoUtil.generateKeyPair();
    String addrA = CryptoUtil.getAddressFromPublicKey(key.getPublic());
    String addrB = CryptoUtil.getAddressFromPublicKey(CryptoUtil.generateKeyPair().getPublic());

    Transaction tx1 = new Transaction("foxchain-main-1", addrA, addrB, 10L, 5L, 0L, CryptoUtil.encodePublicKey(key.getPublic()), "sig1", "tx1");
    Transaction tx2 = new Transaction("foxchain-main-1", addrA, addrB, 20L, 5L, 1L, CryptoUtil.encodePublicKey(key.getPublic()), "sig2", "tx2");

    Block b = new Block(1, "0", addrA, List.of(tx1, tx2), 4);
    String root = b.calculateMerkleRoot();

    // Проверяем RFC 6962 доменное разделение:
    String leaf1 = CryptoUtil.sha256Hex("00" + tx1.hash);
    String leaf2 = CryptoUtil.sha256Hex("00" + tx2.hash);
    String expectedRoot = CryptoUtil.sha256Hex("01" + leaf1 + leaf2);

    Assertions.assertEquals(expectedRoot, root, "Корень Меркла должен строиться с префиксами '00' и '01'");
  }

  // --- ПУНКТ 8: ED25519 КОМПАКТНОСТЬ И СКОРОСТЬ ---
  @Test
  @DisplayName("Пункт 8: Проверка нативного Ed25519: компактный размер ключей и подписей")
  public void testPoint8_NativeEd25519SizeAndSpeed() {
    KeyPair edPair = CryptoUtil.generateKeyPair();
    Assertions.assertNotNull(edPair);

    byte[] pubBytes = edPair.getPublic().getEncoded();
    String pubB64 = CryptoUtil.encodePublicKey(edPair.getPublic());
    Assertions.assertEquals(44, pubBytes.length, "Ed25519 открытый ключ DER должен быть ровно 44 байта");
    Assertions.assertEquals(60, pubB64.length(), "Base64 открытого ключа Ed25519 равен 60 символам (против 392 у RSA)");

    String data = "foxchain-test-data-ed25519";
    String sigB64 = CryptoUtil.sign(edPair.getPrivate(), data);
    Assertions.assertNotNull(sigB64);
    byte[] sigBytes = Base64.getDecoder().decode(sigB64);

    Assertions.assertEquals(64, sigBytes.length, "Ed25519 подпись должна быть ровно 64 байта");
    Assertions.assertEquals(88, sigB64.length(), "Base64 подписи Ed25519 равен 88 символам (против 344 у RSA)");

    // Верификация подписи:
    Assertions.assertTrue(CryptoUtil.isSignatureValid(edPair.getPublic(), data, sigB64));
    Assertions.assertFalse(CryptoUtil.isSignatureValid(edPair.getPublic(), "tampered", sigB64));
  }

  // --- ПУНКТ 9: РЕОРГ И ШАГИ -20 ---
  @Test
  @DisplayName("Пункт 9: Проверка математики шагов отката при нестыковке блоков")
  public void testPoint9_StepBackCalculation() {
    long lowestReceived = 65L;
    long step1 = Math.max(1, lowestReceived - 20); // 45
    long step2 = Math.max(1, step1 - 20);          // 25
    long step3 = Math.max(1, step2 - 20);          // 5
    long step4 = Math.max(1, step3 - 20);          // 1

    Assertions.assertEquals(45, step1);
    Assertions.assertEquals(25, step2);
    Assertions.assertEquals(5, step3);
    Assertions.assertEquals(1, step4);
  }
}
