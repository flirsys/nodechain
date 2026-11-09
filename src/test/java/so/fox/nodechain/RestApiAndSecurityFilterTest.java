package so.fox.nodechain;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.security.KeyPair;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import so.fox.nodechain.core.BlockchainService;
import so.fox.nodechain.core.NodeWalletService;
import so.fox.nodechain.crypto.CryptoUtil;
import so.fox.nodechain.model.Transaction;

// Тесты безопасности REST API: CORS null, фильтры безопасности, фаззинг, защита от оверфлоу и кражи монет.
@SpringBootTest(properties = {
  "server.port=0",
  "node.p2p.port=9993",
  "node.db.path=test_rest_security.db",
  "blockchain.auto-mine=false"
})
@AutoConfigureMockMvc
public class RestApiAndSecurityFilterTest {

  private static final String DB_PATH = "test_rest_security.db";

  static {
    cleanDbFiles();
  }

  @BeforeAll
  @AfterAll
  public static void cleanup() {
    cleanDbFiles();
  }

  private static void cleanDbFiles() {
    new File(DB_PATH).delete();
    new File(DB_PATH + "-wal").delete();
    new File(DB_PATH + "-shm").delete();
  }

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private BlockchainService blockchainService;

  @Autowired
  private NodeWalletService nodeWalletService;

  @Autowired
  private ObjectMapper objectMapper;

  // Проверяет поддержку CORS origins 'null' (для локальных file:// страниц) и localhost.
  @Test
  public void testCorsNullAndLocalhostAllowed() throws Exception {
    mockMvc.perform(options("/api/v1/network")
        .header("Origin", "null")
        .header("Access-Control-Request-Method", "GET"))
      .andExpect(status().isOk())
      .andExpect(header().string("Access-Control-Allow-Origin", "null"));

    mockMvc.perform(options("/api/v1/network")
        .header("Origin", "http://localhost:8080")
        .header("Access-Control-Request-Method", "GET"))
      .andExpect(status().isOk())
      .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8080"));
  }

  // Проверяет состояние сети и устойчивость к мусорным параметрам запроса.
  @Test
  public void testNetworkEndpointSecurity() throws Exception {
    mockMvc.perform(get("/api/v1/network"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.chainId").value("foxchain-main-1"))
      .andExpect(jsonPath("$.height").isNumber())
      .andExpect(jsonPath("$.isSynced").isBoolean())
      .andExpect(jsonPath("$.version").value("1.0.0"));

    mockMvc.perform(get("/api/v1/network?malicious_param=DROP+TABLE"))
      .andExpect(status().isOk());

    mockMvc.perform(post("/api/v1/network"))
      .andExpect(status().isMethodNotAllowed());
  }

  // Проверяет расчет комиссии транзакции и фильтрацию SQLi в параметре приоритета.
  @Test
  public void testFeeEndpointPrioritiesAndFuzzing() throws Exception {
    mockMvc.perform(get("/api/v1/transactions/fee?priority=low"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.fee").value(25));

    mockMvc.perform(get("/api/v1/transactions/fee?priority=normal"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.fee").value(50));

    mockMvc.perform(get("/api/v1/transactions/fee?priority=high"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.fee").value(100));

    // Неизвестный приоритет или SQLi откатывается на нормальный дефолт 50
    mockMvc.perform(get("/api/v1/transactions/fee?priority=' OR 1=1 --"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.fee").value(50));
  }

  // Проверяет эндпоинт состояния пула неподтвержденных транзакций mempool.
  @Test
  public void testMempoolEndpointAccess() throws Exception {
    mockMvc.perform(get("/api/v1/transactions/mempool"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.count").isNumber())
      .andExpect(jsonPath("$.transactions").isArray());
  }

  // Проверяет валидацию формата адреса и защиту от path traversal / SQLi.
  @Test
  public void testAddressStateValidationAndFuzzing() throws Exception {
    // Штатный адрес ноды
    mockMvc.perform(get("/api/v1/addresses/" + nodeWalletService.address))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.address").value(nodeWalletService.address.toLowerCase()))
      .andExpect(jsonPath("$.balance").isNumber());

    // Корректный по формату адрес, но еще не зарегистрированный в цепи -> 404
    KeyPair kp = CryptoUtil.generateKeyPair();
    String freshAddr = CryptoUtil.getAddressFromPublicKey(kp.getPublic());
    mockMvc.perform(get("/api/v1/addresses/" + freshAddr))
      .andExpect(status().isNotFound())
      .andExpect(jsonPath("$.code").value(404));

    // Некорректный формат адреса -> 400 Bad Request
    mockMvc.perform(get("/api/v1/addresses/not-an-address"))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code").value(400));

    // Попытка SQLi в пути адреса -> 400
    mockMvc.perform(get("/api/v1/addresses/' OR 1=1 --"))
      .andExpect(status().isBadRequest());
  }

  // Проверяет безопасное ограничение пагинации истории транзакций адреса.
  @Test
  public void testAddressTransactionsPaginationLimits() throws Exception {
    String addr = nodeWalletService.address;

    mockMvc.perform(get("/api/v1/addresses/" + addr + "/transactions?limit=999999&offset=-10"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.total").isNumber())
      .andExpect(jsonPath("$.txs").isArray());

    // Передача нечислового значения в limit -> 400
    mockMvc.perform(get("/api/v1/addresses/" + addr + "/transactions?limit=abc"))
      .andExpect(status().isBadRequest());
  }

  // Проверяет получение блоков по высоте, хешу и идентификатору latest.
  @Test
  public void testBlocksEndpointQueries() throws Exception {
    mockMvc.perform(get("/api/v1/blocks/latest"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.height").isNumber())
      .andExpect(jsonPath("$.hash").isString());

    mockMvc.perform(get("/api/v1/blocks/0"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.height").value(0))
      .andExpect(jsonPath("$.previousHash").value("0"));

    mockMvc.perform(get("/api/v1/blocks/999999999"))
      .andExpect(status().isNotFound())
      .andExpect(jsonPath("$.code").value(404));

    mockMvc.perform(get("/api/v1/blocks/-1"))
      .andExpect(status().isNotFound());
  }

  // Проверяет поиск транзакции по хешу, нормализацию к нижнему регистру и защиту от инъекций.
  @Test
  public void testTransactionQueryByHash() throws Exception {
    // Несуществующий хеш -> 404
    mockMvc.perform(get("/api/v1/transactions/0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"))
      .andExpect(status().isNotFound())
      .andExpect(jsonPath("$.code").value(404));

    // Мусорная строка в качестве хеша -> 404
    mockMvc.perform(get("/api/v1/transactions/garbage_hash"))
      .andExpect(status().isNotFound());
  }

  // Проверяет обязательность всех полей при отправке транзакции через API (HTTP 409).
  @Test
  public void testSendTransactionMissingFieldsContract() throws Exception {
    // Пустое тело запроса
    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.APPLICATION_JSON)
        .content(""))
      .andExpect(status().isConflict());

    // Пустой JSON
    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{}"))
      .andExpect(status().isConflict())
      .andExpect(jsonPath("$.code").value(409));

    // Пропуск поля 'from'
    Map<String, Object> map = getBaseTxMap();
    map.remove("from");
    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(map)))
      .andExpect(status().isConflict());

    // Пропуск поля 'signature'
    map = getBaseTxMap();
    map.remove("signature");
    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(map)))
      .andExpect(status().isConflict());
  }

  // Проверяет защиту от путаницы типов (Type Confusion) и переполнения чисел при десериализации.
  @Test
  public void testSendTransactionTypeConfusionAndFloatRejection() throws Exception {
    // Число с плавающей точкой в поле amount -> 400 Bad Request
    String floatPayload = """
      {
        "from": "f5ox000000000000000000000000000000000000000000000000000000000000",
        "to": "8fox000000000000000000000000000000000000000000000000000000000000",
        "amount": 99.99,
        "fee": 50,
        "nonce": 0,
        "publicKey": "dummy",
        "signature": "dummy"
      }
      """;
    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.APPLICATION_JSON)
        .content(floatPayload))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code").value(400));

    // Строка вместо числа в поле amount -> 400 Bad Request
    String strPayload = """
      {
        "from": "f5ox000000000000000000000000000000000000000000000000000000000000",
        "to": "8fox000000000000000000000000000000000000000000000000000000000000",
        "amount": "one_million",
        "fee": 50,
        "nonce": 0,
        "publicKey": "dummy",
        "signature": "dummy"
      }
      """;
    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.APPLICATION_JSON)
        .content(strPayload))
      .andExpect(status().isBadRequest());

    // Переполнение 64-битного целого числа -> 400 Bad Request
    String overflowPayload = """
      {
        "from": "f5ox000000000000000000000000000000000000000000000000000000000000",
        "to": "8fox000000000000000000000000000000000000000000000000000000000000",
        "amount": 9999999999999999999999999999999999999999999999999999,
        "fee": 50,
        "nonce": 0,
        "publicKey": "dummy",
        "signature": "dummy"
      }
      """;
    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.APPLICATION_JSON)
        .content(overflowPayload))
      .andExpect(status().isBadRequest());
  }

  // Проверяет бизнес-валидацию транзакций: самоотправка, неположительная сумма, оверспенд.
  @Test
  public void testSendTransactionBusinessRuleViolations() throws Exception {
    KeyPair senderKp = CryptoUtil.generateKeyPair();
    String sender = CryptoUtil.getAddressFromPublicKey(senderKp.getPublic());
    String senderPub = CryptoUtil.encodePublicKey(senderKp.getPublic());

    KeyPair recipientKp = CryptoUtil.generateKeyPair();
    String recipient = CryptoUtil.getAddressFromPublicKey(recipientKp.getPublic());

    // Нулевая сумма перевода (amount <= 0) -> 409
    String canonical0 = Transaction.buildCanonicalData("foxchain-main-1", sender, recipient, 0L, 50L, 0L, 0L, null);
    String sig0 = CryptoUtil.sign(senderKp.getPrivate(), canonical0);
    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.APPLICATION_JSON)
        .content(createTxJson(sender, recipient, 0L, 50L, 0L, senderPub, sig0, null)))
      .andExpect(status().isConflict())
      .andExpect(jsonPath("$.message").value("Транзакция отклонена: некорректная сумма"));

    // Перевод самому себе (from == to) -> 409
    String canonicalSelf = Transaction.buildCanonicalData("foxchain-main-1", sender, sender, 100L, 50L, 0L, 0L, null);
    String sigSelf = CryptoUtil.sign(senderKp.getPrivate(), canonicalSelf);
    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.APPLICATION_JSON)
        .content(createTxJson(sender, sender, 100L, 50L, 0L, senderPub, sigSelf, null)))
      .andExpect(status().isConflict())
      .andExpect(jsonPath("$.message").value("Транзакция отклонена: отправка самому себе"));

    // Недостаточно средств на балансе -> 409
    long ts = System.currentTimeMillis();
    String canonicalNoFunds = Transaction.buildCanonicalData("foxchain-main-1", sender, recipient, 5000L, 50L, 0L, ts, null);
    String sigNoFunds = CryptoUtil.sign(senderKp.getPrivate(), canonicalNoFunds);
    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.APPLICATION_JSON)
        .content(createTxJson(sender, recipient, 5000L, 50L, 0L, ts, senderPub, sigNoFunds, null)))
      .andExpect(status().isConflict())
      .andExpect(jsonPath("$.message").value("Недостаточно средств"));
  }

  // Проверяет защиту от криптографических атак: поддельная подпись, спуфинг ключа и реплей.
  @Test
  public void testSendTransactionCryptoAttacksAndReplayDefense() throws Exception {
    String nodeAddr = nodeWalletService.address;
    String nodePub = CryptoUtil.encodePublicKey(nodeWalletService.publicKey);

    KeyPair recipientKp = CryptoUtil.generateKeyPair();
    String recipient = CryptoUtil.getAddressFromPublicKey(recipientKp.getPublic());

    // Поддельная подпись чужим приватным ключом -> 400 Bad Request
    KeyPair hackerKp = CryptoUtil.generateKeyPair();
    String fakeCanonical = Transaction.buildCanonicalData("foxchain-main-1", nodeAddr, recipient, 500L, 50L, 0L, 0L, null);
    String fakeSig = CryptoUtil.sign(hackerKp.getPrivate(), fakeCanonical);
    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.APPLICATION_JSON)
        .content(createTxJson(nodeAddr, recipient, 500L, 50L, 0L, nodePub, fakeSig, null)))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.message").value("Подпись не верна"));

    // Спуфинг публичного ключа (адрес отправителя не соответствует открытому ключу) -> 400
    String hackerPub = CryptoUtil.encodePublicKey(hackerKp.getPublic());
    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.APPLICATION_JSON)
        .content(createTxJson(nodeAddr, recipient, 500L, 50L, 0L, hackerPub, fakeSig, null)))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.message").value("Подпись не верна"));

    // Успешная отправка валидной транзакции (Happy Path)
    long curNonce = blockchainService.getAccount(nodeAddr).nonce;
    long ts = System.currentTimeMillis();
    String validCanonical = Transaction.buildCanonicalData("foxchain-main-1", nodeAddr, recipient, 1000L, 50L, curNonce, ts, "Valid API Tx");
    String validSig = CryptoUtil.sign(nodeWalletService.privateKey, validCanonical);
    String validPayload = createTxJson(nodeAddr, recipient, 1000L, 50L, curNonce, ts, nodePub, validSig, "Valid API Tx");

    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.APPLICATION_JSON)
        .content(validPayload))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.status").value("pending"))
      .andExpect(jsonPath("$.hash").isString());

    // Повторное воспроизведение (Replay Attack) -> 409 Conflict: Конфликт nonce
    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.APPLICATION_JSON)
        .content(validPayload))
      .andExpect(status().isConflict())
      .andExpect(jsonPath("$.message").value("Конфликт nonce"));
  }

  // Проверяет строгую валидацию заголовка Content-Type и запрещенных HTTP-методов.
  @Test
  public void testContentTypeAndHttpMethodsEnforcement() throws Exception {
    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.TEXT_PLAIN)
        .content("from=abc&to=def"))
      .andExpect(status().isUnsupportedMediaType());

    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.APPLICATION_XML)
        .content("<tx></tx>"))
      .andExpect(status().isUnsupportedMediaType());

    mockMvc.perform(get("/api/v1/transactions/send"))
      .andExpect(status().isMethodNotAllowed());

    mockMvc.perform(put("/api/v1/transactions/send"))
      .andExpect(status().isMethodNotAllowed());

    mockMvc.perform(delete("/api/v1/transactions/send"))
      .andExpect(status().isMethodNotAllowed());
  }

  // Проверяет полное отсутствие внутренних 500 Internal Server Error при произвольном фаззинге.
  @Test
  public void testImmunityAgainstHttp500OnClientFuzzing() throws Exception {
    String nodeAddr = nodeWalletService.address;

    // Манипуляция идентификаторами блоков
    String[] testBlockIds = {"-0", "+0", "null", "undefined", "NaN", "0x0", "   ", "latest"};
    for (String id : testBlockIds) {
      int st = mockMvc.perform(get("/api/v1/blocks/" + id)).andReturn().getResponse().getStatus();
      Assertions.assertNotEquals(500, st, "GET /blocks/" + id + " не должен возвращать 500");
    }

    // Манипуляция адресами
    String[] testAddresses = {"0", "null", "' OR 1=1 --", "../", "fox_invalid_length"};
    for (String a : testAddresses) {
      int st = mockMvc.perform(get("/api/v1/addresses/" + a)).andReturn().getResponse().getStatus();
      Assertions.assertNotEquals(500, st, "GET /addresses/" + a + " не должен возвращать 500");
    }

    // Мусорные тела запросов
    String[] badBodies = {"", "   ", "[]", "123", "true", "{bad_json", "{\"amount\": 10.5}"};
    for (String body : badBodies) {
      int st = mockMvc.perform(post("/api/v1/transactions/send")
          .contentType(MediaType.APPLICATION_JSON)
          .content(body))
        .andReturn().getResponse().getStatus();
      Assertions.assertNotEquals(500, st, "POST /send с телом [" + body + "] не должен возвращать 500");
    }
  }

  private static Map<String, Object> getBaseTxMap() {
    Map<String, Object> map = new HashMap<>();
    map.put("chainId", "foxchain-main-1");
    map.put("from", "f5ox000000000000000000000000000000000000000000000000000000000000");
    map.put("to", "8fox000000000000000000000000000000000000000000000000000000000000");
    map.put("amount", 1000L);
    map.put("fee", 50L);
    map.put("nonce", 0L);
    map.put("publicKey", "dummy_pubkey");
    map.put("signature", "dummy_sig");
    return map;
  }

  private String createTxJson(String from, String to, long amount, long fee, long nonce,
                              String pubKey, String sig, String comment) {
    return createTxJson(from, to, amount, fee, nonce, System.currentTimeMillis(), pubKey, sig, comment);
  }

  private String createTxJson(String from, String to, long amount, long fee, long nonce, long timestamp,
                              String pubKey, String sig, String comment) {
    Map<String, Object> m = new HashMap<>();
    m.put("chainId", "foxchain-main-1");
    m.put("from", from);
    m.put("to", to);
    m.put("amount", amount);
    m.put("fee", fee);
    m.put("nonce", nonce);
    m.put("timestamp", timestamp);
    m.put("publicKey", pubKey);
    m.put("signature", sig);
    if (comment != null) m.put("comment", comment);
    try {
      return objectMapper.writeValueAsString(m);
    } catch (Exception e) {
      return "{}";
    }
  }
}
