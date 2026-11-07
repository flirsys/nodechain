package so.fox.nodechain;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import so.fox.nodechain.core.BlockchainService;
import so.fox.nodechain.core.NodeWalletService;
import so.fox.nodechain.crypto.CryptoUtil;

/**
 * Комплексные стресс-тесты, фаззинг и проверка надежности REST API со стороны клиента.
 * Проверяет реакцию сервера на несоответствие типов данных, нелогичное заполнение,
 * граничные значения, инъекции и попытки дестабилизации узла.
 */
@SpringBootTest(properties = {
  "server.port=0",
  "node.p2p.port=9996",
  "node.db.path=test_fuzz_api.db",
  "blockchain.auto-mine=false"
})
@AutoConfigureMockMvc
public class ClientApiFuzzAndRobustnessTest {

  private static final String TEST_DB_PATH = "test_fuzz_api.db";

  static {
    cleanDbFiles();
  }

  @BeforeAll
  @AfterAll
  public static void cleanup() {
    cleanDbFiles();
  }

  private static void cleanDbFiles() {
    new File(TEST_DB_PATH).delete();
    new File(TEST_DB_PATH + "-wal").delete();
    new File(TEST_DB_PATH + "-shm").delete();
  }

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private BlockchainService blockchainService;

  @Autowired
  private NodeWalletService nodeWalletService;

  @Autowired
  private ObjectMapper objectMapper;

  // =========================================================================
  // 1. GET /api/v1/network
  // =========================================================================

  @Test
  @DisplayName("Network: штатный GET-запрос и реакция на мусорные параметры")
  public void testNetworkEndpointRobustness() throws Exception {
    mockMvc.perform(get("/api/v1/network"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.chainId").isString())
      .andExpect(jsonPath("$.height").isNumber())
      .andExpect(jsonPath("$.isSynced").isBoolean());

    // Мусорные параметры запроса не должны ломать эндпоинт
    mockMvc.perform(get("/api/v1/network?sql=DROP+TABLE+blocks&foo=bar&num=123"))
      .andExpect(status().isOk());

    // Неподдерживаемые HTTP-методы должны возвращать 405 Method Not Allowed
    mockMvc.perform(post("/api/v1/network"))
      .andExpect(status().isMethodNotAllowed());
    mockMvc.perform(delete("/api/v1/network"))
      .andExpect(status().isMethodNotAllowed());
  }

  // =========================================================================
  // 2. GET /api/v1/transactions/fee
  // =========================================================================

  @Test
  @DisplayName("Fee: проверка приоритетов, мусорных строк и SQLi в параметрах")
  public void testFeeEndpointFuzzing() throws Exception {
    // Штатные приоритеты
    mockMvc.perform(get("/api/v1/transactions/fee?priority=low"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.fee").value(25));

    mockMvc.perform(get("/api/v1/transactions/fee?priority=normal"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.fee").value(50));

    mockMvc.perform(get("/api/v1/transactions/fee?priority=high"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.fee").value(100));

    // Несуществующий или нелогичный приоритет -> дефолт 50
    mockMvc.perform(get("/api/v1/transactions/fee?priority=ultra_mega_fast"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.fee").value(50));

    // Попытка инъекции в параметр priority
    mockMvc.perform(get("/api/v1/transactions/fee?priority=' OR 1=1 --"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.fee").value(50));

    // Неподдерживаемый метод POST -> 405
    mockMvc.perform(post("/api/v1/transactions/fee"))
      .andExpect(status().isMethodNotAllowed());
  }

  // =========================================================================
  // 3. GET /api/v1/transactions/mempool
  // =========================================================================

  @Test
  @DisplayName("Mempool: структура ответа и методы")
  public void testMempoolEndpoint() throws Exception {
    mockMvc.perform(get("/api/v1/transactions/mempool"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.count").isNumber())
      .andExpect(jsonPath("$.transactions").isArray());

    mockMvc.perform(post("/api/v1/transactions/mempool"))
      .andExpect(status().isMethodNotAllowed());
  }

  // =========================================================================
  // 4. GET /api/v1/addresses/{address}
  // =========================================================================

  @Test
  @DisplayName("Address State: проверка валидации Fox-формата, инъекций и длинных строк")
  public void testAddressStateFuzzing() throws Exception {
    // 1. Штатный адрес ноды (генезис)
    mockMvc.perform(get("/api/v1/addresses/" + nodeWalletService.address))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.address").value(nodeWalletService.address.toLowerCase()))
      .andExpect(jsonPath("$.balance").isNumber());

    // 2. Валидный по формату адрес, но еще не зарегистрированный в сети -> 404
    KeyPair kp = CryptoUtil.generateRsaKeyPair();
    String freshAddr = CryptoUtil.getAddressFromPublicKey(kp.getPublic());
    mockMvc.perform(get("/api/v1/addresses/" + freshAddr))
      .andExpect(status().isNotFound())
      .andExpect(jsonPath("$.code").value(404));

    // 3. Некорректная длина адреса -> 400 Bad Request
    mockMvc.perform(get("/api/v1/addresses/f5ox12345"))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code").value(400));

    // 4. 64 символа, но без обязательных букв f, o, x -> 400 Bad Request
    String noFox = "1111111111111111111111111111111111111111111111111111111111111111";
    mockMvc.perform(get("/api/v1/addresses/" + noFox))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code").value(400));

    // 5. Не-hex символы в адресе -> 400 Bad Request
    String badHex = "foxzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz";
    mockMvc.perform(get("/api/v1/addresses/" + badHex))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code").value(400));

    // 6. SQL-инъекция в пути адреса -> 400
    mockMvc.perform(get("/api/v1/addresses/' OR '1'='1"))
      .andExpect(status().isBadRequest());

    // 7. Слишком длинная строка адреса (1000 символов) -> 400
    String hugeAddr = "fox" + "a".repeat(1000);
    mockMvc.perform(get("/api/v1/addresses/" + hugeAddr))
      .andExpect(status().isBadRequest());

    // 8. Неподдерживаемые методы -> 405
    mockMvc.perform(post("/api/v1/addresses/" + nodeWalletService.address))
      .andExpect(status().isMethodNotAllowed());
    mockMvc.perform(delete("/api/v1/addresses/" + nodeWalletService.address))
      .andExpect(status().isMethodNotAllowed());
  }

  // =========================================================================
  // 5. GET /api/v1/addresses/{address}/transactions
  // =========================================================================

  @Test
  @DisplayName("Address Transactions: пагинация, граничные лимиты и неверные типы данных")
  public void testAddressTransactionsPaginationFuzzing() throws Exception {
    String addr = nodeWalletService.address;

    // 1. Штатный запрос
    mockMvc.perform(get("/api/v1/addresses/" + addr + "/transactions"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.total").isNumber())
      .andExpect(jsonPath("$.txs").isArray());

    // 2. Отрицательный limit и offset (должны безопасно нормализоваться в Math.max)
    mockMvc.perform(get("/api/v1/addresses/" + addr + "/transactions?limit=-10&offset=-100"))
      .andExpect(status().isOk());

    // 3. Сверхбольшие limit и offset (должны безопасно ограничиться safeLimit=100, safeOffset=10000)
    mockMvc.perform(get("/api/v1/addresses/" + addr + "/transactions?limit=999999&offset=999999"))
      .andExpect(status().isOk());

    // 4. Ошибка типов (Type Confusion): передача строки вместо числа в limit -> 400 Bad Request
    mockMvc.perform(get("/api/v1/addresses/" + addr + "/transactions?limit=invalid_number"))
      .andExpect(status().isBadRequest());

    // 5. Ошибка типов: передача boolean в offset -> 400 Bad Request
    mockMvc.perform(get("/api/v1/addresses/" + addr + "/transactions?offset=true"))
      .andExpect(status().isBadRequest());

    // 6. Ошибка типов: число с плавающей точкой в limit -> 400 Bad Request
    mockMvc.perform(get("/api/v1/addresses/" + addr + "/transactions?limit=10.5"))
      .andExpect(status().isBadRequest());

    // 7. Числовое переполнение limit (больше Long.MAX_VALUE) -> 400 Bad Request
    mockMvc.perform(get("/api/v1/addresses/" + addr + "/transactions?limit=999999999999999999999999999999"))
      .andExpect(status().isBadRequest());
  }

  // =========================================================================
  // 6. GET /api/v1/blocks/{identifier}
  // =========================================================================

  @Test
  @DisplayName("Blocks: поиск по высоте, слову latest, отрицательные высоты и переполнения")
  public void testBlocksEndpointFuzzing() throws Exception {
    // 1. По ключевому слову "latest"
    mockMvc.perform(get("/api/v1/blocks/latest"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.height").isNumber())
      .andExpect(jsonPath("$.hash").isString());

    // 2. Генезис-блок по номеру #0
    mockMvc.perform(get("/api/v1/blocks/0"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.height").value(0));

    // 3. Несуществующая высота блока -> 404
    mockMvc.perform(get("/api/v1/blocks/999999999"))
      .andExpect(status().isNotFound())
      .andExpect(jsonPath("$.code").value(404));

    // 4. Отрицательная высота блока -> 404
    mockMvc.perform(get("/api/v1/blocks/-1"))
      .andExpect(status().isNotFound());

    // 5. Дробное число -> 404 (воспринимается как хеш, не находится)
    mockMvc.perform(get("/api/v1/blocks/1.5"))
      .andExpect(status().isNotFound());

    // 6. Сверхдлинная строка/переполнение -> 404
    mockMvc.perform(get("/api/v1/blocks/9999999999999999999999999999999999999999999999"))
      .andExpect(status().isNotFound());

    // 7. SQL-инъекция в идентификатор блока -> 404
    mockMvc.perform(get("/api/v1/blocks/0'+OR+'1'='1"))
      .andExpect(status().isNotFound());
  }

  // =========================================================================
  // 7. GET /api/v1/transactions/{txHash}
  // =========================================================================

  @Test
  @DisplayName("Transaction by Hash: несуществующие хеши, спецсимволы и инъекции")
  public void testTransactionByHashFuzzing() throws Exception {
    // 1. Несуществующий валидный хеш -> 404
    mockMvc.perform(get("/api/v1/transactions/0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"))
      .andExpect(status().isNotFound())
      .andExpect(jsonPath("$.code").value(404));

    // 2. Короткая мусорная строка -> 404
    mockMvc.perform(get("/api/v1/transactions/not_a_hash"))
      .andExpect(status().isNotFound());

    // 3. Попытка SQL-инъекции в хеш -> 404
    mockMvc.perform(get("/api/v1/transactions/' OR '1'='1"))
      .andExpect(status().isNotFound());

    // 4. XSS в хеше -> 404
    mockMvc.perform(get("/api/v1/transactions/<script>alert(1)</script>"))
      .andExpect(status().isNotFound());
  }

  // =========================================================================
  // 8. POST /api/v1/transactions/send — Проверка валидации и пропущенных полей
  // =========================================================================

  @Test
  @DisplayName("Send: пустые тела, отсутствующие обязательные поля и null")
  public void testSendTransactionMissingFields() throws Exception {
    // 1. Пустое тело запроса -> 409
    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.APPLICATION_JSON)
        .content(""))
      .andExpect(status().isConflict());

    // 2. Пустой JSON-объект {} -> 409
    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{}"))
      .andExpect(status().isConflict())
      .andExpect(jsonPath("$.code").value(409));

    // 3. Отсутствует поле 'from' -> 409
    Map<String, Object> map = getBaseTxMap();
    map.remove("from");
    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(map)))
      .andExpect(status().isConflict());

    // 4. Отсутствует поле 'to' -> 409
    map = getBaseTxMap();
    map.remove("to");
    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(map)))
      .andExpect(status().isConflict());

    // 5. Отсутствует поле 'amount' -> 409
    map = getBaseTxMap();
    map.remove("amount");
    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(map)))
      .andExpect(status().isConflict());

    // 6. Отсутствует поле 'signature' -> 409
    map = getBaseTxMap();
    map.remove("signature");
    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(map)))
      .andExpect(status().isConflict());

    // 7. Отсутствует поле 'publicKey' -> 409
    map = getBaseTxMap();
    map.remove("publicKey");
    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(map)))
      .andExpect(status().isConflict());
  }

  // =========================================================================
  // 9. POST /api/v1/transactions/send — Ошибки типов и десериализации (Type Confusion)
  // =========================================================================

  @Test
  @DisplayName("Send: синтаксис JSON, передача неверных типов данных и переполнение чисел")
  public void testSendTransactionTypeConfusion() throws Exception {
    // 1. Поломанный синтаксис JSON (битые скобки) -> 400 Bad Request
    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{ \"from\": \"test\", "))
      .andExpect(status().isBadRequest());

    // 2. JSON массив вместо объекта -> 400 Bad Request
    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.APPLICATION_JSON)
        .content("[1, 2, 3]"))
      .andExpect(status().isBadRequest());

    // 3. Строка вместо Long в поле amount -> 400 Bad Request
    String strAmount = """
      {
        "from": "f5ox000000000000000000000000000000000000000000000000000000000000",
        "to": "8fox000000000000000000000000000000000000000000000000000000000000",
        "amount": "one_million_coins",
        "fee": 50,
        "nonce": 0,
        "publicKey": "dummy",
        "signature": "dummy"
      }
      """;
    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.APPLICATION_JSON)
        .content(strAmount))
      .andExpect(status().isBadRequest());

    // 4. Boolean вместо Long в поле amount -> 400 Bad Request
    String boolAmount = """
      {
        "from": "f5ox000000000000000000000000000000000000000000000000000000000000",
        "to": "8fox000000000000000000000000000000000000000000000000000000000000",
        "amount": true,
        "fee": 50,
        "nonce": 0,
        "publicKey": "dummy",
        "signature": "dummy"
      }
      """;
    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.APPLICATION_JSON)
        .content(boolAmount))
      .andExpect(status().isBadRequest());

    // 5. Массив вместо Long в поле amount -> 400 Bad Request
    String arrayAmount = """
      {
        "from": "f5ox000000000000000000000000000000000000000000000000000000000000",
        "to": "8fox000000000000000000000000000000000000000000000000000000000000",
        "amount": [100, 200],
        "fee": 50,
        "nonce": 0,
        "publicKey": "dummy",
        "signature": "dummy"
      }
      """;
    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.APPLICATION_JSON)
        .content(arrayAmount))
      .andExpect(status().isBadRequest());

    // 6. Числовой оверфлоу суммы (больше 64-бит Long.MAX_VALUE) -> 400 Bad Request
    String overflowAmount = """
      {
        "from": "f5ox000000000000000000000000000000000000000000000000000000000000",
        "to": "8fox000000000000000000000000000000000000000000000000000000000000",
        "amount": 999999999999999999999999999999999999999999999999999999,
        "fee": 50,
        "nonce": 0,
        "publicKey": "dummy",
        "signature": "dummy"
      }
      """;
    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.APPLICATION_JSON)
        .content(overflowAmount))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code").value(400))
      .andExpect(jsonPath("$.message").value("Некорректный формат данных в теле запроса"));

    // 7. Число с плавающей точкой в поле amount -> 400 Bad Request (защита от усечения float truncation)
    String floatAmount = """
      {
        "from": "f5ox000000000000000000000000000000000000000000000000000000000000",
        "to": "8fox000000000000000000000000000000000000000000000000000000000000",
        "amount": 100.95,
        "fee": 50,
        "nonce": 0,
        "publicKey": "dummy",
        "signature": "dummy"
      }
      """;
    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.APPLICATION_JSON)
        .content(floatAmount))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code").value(400))
      .andExpect(jsonPath("$.message").value("Некорректный формат данных в теле запроса"));
  }

  // =========================================================================
  // 10. POST /api/v1/transactions/send — Нелогичное заполнение и бизнес-правила
  // =========================================================================

  @Test
  @DisplayName("Send: нелогичные суммы (<=0), отрицательные комиссии, самоотправка и оверспенд")
  public void testSendTransactionIllogicalValues() throws Exception {
    KeyPair senderKp = CryptoUtil.generateRsaKeyPair();
    String senderAddr = CryptoUtil.getAddressFromPublicKey(senderKp.getPublic());
    String senderPubB64 = CryptoUtil.encodePublicKey(senderKp.getPublic());

    KeyPair recipientKp = CryptoUtil.generateRsaKeyPair();
    String recipientAddr = CryptoUtil.getAddressFromPublicKey(recipientKp.getPublic());

    // 1. Нулевая сумма перевода (amount = 0) -> 409
    String canonical0 = senderAddr + "|" + recipientAddr + "|0|50|0";
    String sig0 = CryptoUtil.sign(senderKp.getPrivate(), canonical0);
    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.APPLICATION_JSON)
        .content(createTxJson(senderAddr, recipientAddr, 0L, 50L, 0L, senderPubB64, sig0, null)))
      .andExpect(status().isConflict())
      .andExpect(jsonPath("$.message").value("Транзакция отклонена: некорректная сумма"));

    // 2. Отрицательная сумма (amount = -500) -> 409
    String canonicalNeg = senderAddr + "|" + recipientAddr + "|-500|50|0";
    String sigNeg = CryptoUtil.sign(senderKp.getPrivate(), canonicalNeg);
    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.APPLICATION_JSON)
        .content(createTxJson(senderAddr, recipientAddr, -500L, 50L, 0L, senderPubB64, sigNeg, null)))
      .andExpect(status().isConflict())
      .andExpect(jsonPath("$.message").value("Транзакция отклонена: некорректная сумма"));

    // 3. Отрицательная комиссия (fee = -10) -> 409
    String canonicalNegFee = senderAddr + "|" + recipientAddr + "|100|-10|0";
    String sigNegFee = CryptoUtil.sign(senderKp.getPrivate(), canonicalNegFee);
    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.APPLICATION_JSON)
        .content(createTxJson(senderAddr, recipientAddr, 100L, -10L, 0L, senderPubB64, sigNegFee, null)))
      .andExpect(status().isConflict())
      .andExpect(jsonPath("$.message").value("Транзакция отклонена: некорректная комиссия"));

    // 4. Отправка самому себе (from == to) -> 409
    String canonicalSelf = senderAddr + "|" + senderAddr + "|100|50|0";
    String sigSelf = CryptoUtil.sign(senderKp.getPrivate(), canonicalSelf);
    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.APPLICATION_JSON)
        .content(createTxJson(senderAddr, senderAddr, 100L, 50L, 0L, senderPubB64, sigSelf, null)))
      .andExpect(status().isConflict())
      .andExpect(jsonPath("$.message").value("Транзакция отклонена: отправка самому себе"));

    // 5. Некорректный формат адреса получателя -> 400 Bad Request
    String badTo = "invalid_recipient_address";
    String canonicalBadTo = senderAddr + "|" + badTo + "|100|50|0";
    String sigBadTo = CryptoUtil.sign(senderKp.getPrivate(), canonicalBadTo);
    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.APPLICATION_JSON)
        .content(createTxJson(senderAddr, badTo, 100L, 50L, 0L, senderPubB64, sigBadTo, null)))
      .andExpect(status().isBadRequest());

    // 6. Недостаточно средств (у нового аккаунта баланс 0, отправка 1000) -> 409
    String canonicalNoFunds = senderAddr + "|" + recipientAddr + "|1000|50|0";
    String sigNoFunds = CryptoUtil.sign(senderKp.getPrivate(), canonicalNoFunds);
    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.APPLICATION_JSON)
        .content(createTxJson(senderAddr, recipientAddr, 1000L, 50L, 0L, senderPubB64, sigNoFunds, null)))
      .andExpect(status().isConflict())
      .andExpect(jsonPath("$.message").value("Недостаточно средств"));
  }

  // =========================================================================
  // 11. POST /api/v1/transactions/send — Криптографические атаки и подделка
  // =========================================================================

  @Test
  @DisplayName("Send: подделка подписи, спуфинг открытого ключа, битый Base64 и реплей-атака")
  public void testSendTransactionCryptoAttacks() throws Exception {
    String nodeAddr = nodeWalletService.address;
    String nodePubB64 = CryptoUtil.encodePublicKey(nodeWalletService.publicKey);

    KeyPair recipientKp = CryptoUtil.generateRsaKeyPair();
    String recipientAddr = CryptoUtil.getAddressFromPublicKey(recipientKp.getPublic());

    // 1. Поддельная цифровая подпись (чужой приватный ключ) -> 400 Bad Request
    KeyPair hackerKp = CryptoUtil.generateRsaKeyPair();
    String fakeCanonical = nodeAddr + "|" + recipientAddr + "|1000|50|0";
    String fakeSig = CryptoUtil.sign(hackerKp.getPrivate(), fakeCanonical);
    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.APPLICATION_JSON)
        .content(createTxJson(nodeAddr, recipientAddr, 1000L, 50L, 0L, nodePubB64, fakeSig, null)))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.message").value("Подпись не верна"));

    // 2. Невалидный Base64 в поле подписи -> 400 Bad Request (сервер не падает)
    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.APPLICATION_JSON)
        .content(createTxJson(nodeAddr, recipientAddr, 1000L, 50L, 0L, nodePubB64, "NOT_A_VALID_BASE64!@#$%", null)))
      .andExpect(status().isBadRequest());

    // 3. Подмена отправителя (Identity Spoofing: адрес ноды, но открытый ключ хакера) -> 400
    String hackerPubB64 = CryptoUtil.encodePublicKey(hackerKp.getPublic());
    String hackerSig = CryptoUtil.sign(hackerKp.getPrivate(), fakeCanonical);
    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.APPLICATION_JSON)
        .content(createTxJson(nodeAddr, recipientAddr, 1000L, 50L, 0L, hackerPubB64, hackerSig, null)))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.message").value("Подпись не верна"));

    // 4. Слишком длинный комментарий (> 256 символов) -> 409
    String longComment = "A".repeat(300);
    String longCanonical = nodeAddr + "|" + recipientAddr + "|1000|50|0|" + longComment;
    String longSig = CryptoUtil.sign(nodeWalletService.privateKey, longCanonical);
    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.APPLICATION_JSON)
        .content(createTxJson(nodeAddr, recipientAddr, 1000L, 50L, 0L, nodePubB64, longSig, longComment)))
      .andExpect(status().isConflict())
      .andExpect(jsonPath("$.message").value("Транзакция отклонена: слишком длинный комментарий"));

    // 5. Успешная отправка валидной транзакции (Happy Path)
    long curNonce = blockchainService.getAccount(nodeAddr).nonce;
    String validCanonical = nodeAddr + "|" + recipientAddr + "|10000|50|" + curNonce + "|Valid Tx";
    String validSig = CryptoUtil.sign(nodeWalletService.privateKey, validCanonical);
    String validPayload = createTxJson(nodeAddr, recipientAddr, 10000L, 50L, curNonce, nodePubB64, validSig, "Valid Tx");

    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.APPLICATION_JSON)
        .content(validPayload))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.status").value("pending"))
      .andExpect(jsonPath("$.hash").isString());

    // 6. Атака повторного воспроизведения (Replay Attack) -> 409 Conflict: Конфликт nonce
    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.APPLICATION_JSON)
        .content(validPayload))
      .andExpect(status().isConflict())
      .andExpect(jsonPath("$.message").value("Конфликт nonce"));
  }

  // =========================================================================
  // 12. POST /api/v1/transactions/send — Заголовки Content-Type и методы
  // =========================================================================

  @Test
  @DisplayName("Send: проверка Content-Type и запрещенных HTTP методов")
  public void testSendTransactionHeadersAndMethods() throws Exception {
    // 1. Отправка c text/plain вместо application/json -> 415 Unsupported Media Type
    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.TEXT_PLAIN)
        .content("from=f5ox&to=8fox"))
      .andExpect(status().isUnsupportedMediaType());

    // 2. Отправка c application/xml -> 415
    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.APPLICATION_XML)
        .content("<tx><from>f5ox</from></tx>"))
      .andExpect(status().isUnsupportedMediaType());

    // 3. Вызов эндпоинта отправки через GET -> корректно 405 Method Not Allowed благодаря регулярке в {txHash}
    mockMvc.perform(get("/api/v1/transactions/send"))
      .andExpect(status().isMethodNotAllowed())
      .andExpect(jsonPath("$.code").value(405))
      .andExpect(jsonPath("$.message").value("Метод HTTP не поддерживается"));

    // 4. Вызов эндпоинта отправки через PUT -> 405 Method Not Allowed
    mockMvc.perform(put("/api/v1/transactions/send"))
      .andExpect(status().isMethodNotAllowed())
      .andExpect(jsonPath("$.code").value(405))
      .andExpect(jsonPath("$.message").value("Метод HTTP не поддерживается"));
  }

  @Test
  @DisplayName("Группа 13: Доказательство невозможности вызова HTTP 500 через GET/POST запросы")
  void testProofOfNoHttp500OnAnyClientRequest() throws Exception {
    String nodeAddr = nodeWalletService.address;

    // 1. Векторы через GET: Path, Query, Header манипуляции
    // 1.1. Экстремальные числа в пагинации (не должны вызывать 500)
    mockMvc.perform(get("/api/v1/addresses/" + nodeAddr + "/transactions")
        .param("limit", String.valueOf(Integer.MAX_VALUE))
        .param("offset", String.valueOf(Integer.MAX_VALUE)))
      .andExpect(status().isOk());

    mockMvc.perform(get("/api/v1/addresses/" + nodeAddr + "/transactions")
        .param("limit", String.valueOf(Integer.MIN_VALUE))
        .param("offset", String.valueOf(Integer.MIN_VALUE)))
      .andExpect(status().isOk());

    mockMvc.perform(get("/api/v1/addresses/" + nodeAddr + "/transactions")
        .param("limit", "999999999999999999999999999999"))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code").value(400));

    // 1.2. Повторяющиеся параметры запроса
    mockMvc.perform(get("/api/v1/addresses/" + nodeAddr + "/transactions")
        .param("limit", "10", "20"))
      .andExpect(status().isOk());

    // 1.3. Граничные и невалидные идентификаторы блоков
    String[] blockIds = {"-0", "+0", "NaN", "null", "undefined", "999999999999999999", "0x0", "   ", "latest"};
    for (String id : blockIds) {
      int st = mockMvc.perform(get("/api/v1/blocks/" + id)).andReturn().getResponse().getStatus();
      Assertions.assertNotEquals(500, st, "GET /blocks/" + id + " вернул статус 500!");
      Assertions.assertTrue(st == 200 || st == 404, "Ожидался 200 или 404 для id: " + id + ", получен: " + st);
    }

    // 1.4. Манипуляции с адресами (SQLi, path traversal, спецсимволы)
    String[] testAddresses = {
      "0", "null", "undefined", "' OR 1=1 --", "../", "f5ox", 
      "fxo43ccfbe742b954b1176f9d8078a7ee03209a6d356c2d5521f4138efd5a96z"
    };
    for (String a : testAddresses) {
      int st = mockMvc.perform(get("/api/v1/addresses/" + a)).andReturn().getResponse().getStatus();
      Assertions.assertNotEquals(500, st, "GET /addresses/" + a + " вернул 500!");
      Assertions.assertTrue(st == 400 || st == 404, "Ожидался 400 или 404 для адреса: " + a);
    }

    // 1.5. Приоритеты комиссии
    String[] priorities = {"high", "low", "normal", "URGENT", "", "123", "   ", "super_fast"};
    for (String p : priorities) {
      int st = mockMvc.perform(get("/api/v1/transactions/fee").param("priority", p))
        .andReturn().getResponse().getStatus();
      Assertions.assertEquals(200, st, "GET /transactions/fee?priority=" + p + " должен возвращать 200");
    }

    // 2. Векторы через POST: Payload, Type Confusion, Boundary Values
    // 2.1. Некорректные форматы тела: массивы, примитивы, битый синтаксис
    String[] badBodies = {
      "", "   ", "[]", "[1, 2, 3]", "12345", "\"string\"", "true",
      "{", "{bad_json", "{\"amount\": 10.5}", "{\"amount\": 1e10}",
      "{\"amount\": 999999999999999999999999999999999999999999999999}",
      "{\"from\": 12345, \"to\": true}",
      "{\"from\": \"fxo...\", \"nonce\": \"not_a_nonce\"}"
    };
    for (String body : badBodies) {
      int st = mockMvc.perform(post("/api/v1/transactions/send")
          .contentType(MediaType.APPLICATION_JSON)
          .content(body))
        .andReturn().getResponse().getStatus();
      Assertions.assertNotEquals(500, st, "POST /send с телом [" + body + "] вернул 500!");
      Assertions.assertTrue(st == 400 || st == 409, "Ожидался 400 или 409 для тела: " + body + ", получен: " + st);
    }

    // 2.2. Опасные значения полей в JSON (XSS, SQLi, NUL-байты, суррогаты)
    KeyPair attackerKeys = CryptoUtil.generateRsaKeyPair();
    String attackerPub = CryptoUtil.encodePublicKey(attackerKeys.getPublic());
    String attackerAddr = CryptoUtil.getAddressFromPublicKey(attackerKeys.getPublic());

    Map<String, Object> dangerousTx = getBaseTxMap();
    dangerousTx.put("from", attackerAddr);
    dangerousTx.put("comment", "Special chars: \u0000 <script>alert(1)</script> ' OR '1'='1 \uD83D\uDE00");
    dangerousTx.put("publicKey", attackerPub);
    dangerousTx.put("signature", "invalid_sig");

    int stDangerous = mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(dangerousTx)))
      .andReturn().getResponse().getStatus();
    Assertions.assertNotEquals(500, stDangerous, "POST с опасным комментарием и подписью вернул 500!");
    Assertions.assertEquals(400, stDangerous);

    // 3. Векторы через заголовки HTTP (Content-Type, Accept, Charset)
    mockMvc.perform(get("/api/v1/network").header("Accept", "invalid/type/;;"))
      .andExpect(status().isNotAcceptable());

    mockMvc.perform(get("/api/v1/network").header("Accept", "application/xml"))
      .andExpect(status().isNotAcceptable());

    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType("application/json; charset=UNKNOWN_XYZ")
        .content("{}"))
      .andExpect(status().isUnsupportedMediaType());

    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.TEXT_PLAIN)
        .content("plain text"))
      .andExpect(status().isUnsupportedMediaType());

    // 4. Проверка жизнеспособности сервера после всех атак: нода активна и отдает 200 OK
    mockMvc.perform(get("/api/v1/network"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.version").value("1.0.0"))
      .andExpect(jsonPath("$.chainId").value("foxchain-main-1"));
  }

  // =========================================================================
  // Хелперы формирования тестовых полезных нагрузок
  // =========================================================================

  private static Map<String, Object> getBaseTxMap() {
    Map<String, Object> map = new HashMap<>();
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
    Map<String, Object> m = new HashMap<>();
    m.put("from", from);
    m.put("to", to);
    m.put("amount", amount);
    m.put("fee", fee);
    m.put("nonce", nonce);
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
