/**
 * Централизованное хранилище состояния приложения (State Management).
 * Выполнено в виде строгой ES6-структуры без использования try/catch (Errors as Values).
 */
class AppState {
  static STORAGE_KEY_WALLETS = 'nodechain_wallets';
  static STORAGE_KEY_ACTIVE = 'nodechain_active_wallet';
  static STORAGE_KEY_RPC = 'nodechain_rpc_url';
  static STORAGE_KEY_DEV_WALLET = 'nodechain_dev_wallet';

  static isDevMode = typeof window !== 'undefined' && window.location.search.includes('dev=1');
  static currentTab = 'wallet';

  // Параметры подключения к ноде
  static rpcUrl = 'http://localhost:8080';
  static rpcInput = 'http://localhost:8080';
  static isOnline = false;
  static pollIntervalId = null;
  static network = {
    chainId: '-',
    height: 0,
    latestBlockHash: '-',
    peers: 0,
    isSynced: false,
    version: '-'
  };

  // Комиссии сети
  static fees = {
    low: 25,
    normal: 50,
    high: 100
  };

  // Список локальных кошельков
  static wallets = [];
  static activeWallet = null;
  static activeAccount = {
    balance: 0,
    unconfirmedBalance: 0,
    nonce: 0,
    txCount: 0,
    isLoading: false
  };

  // История транзакций активного кошелька
  static history = {
    total: 0,
    items: [],
    page: 0,
    limit: 10,
    isLoading: false
  };

  // Мемпул
  static mempool = {
    count: 0,
    items: [],
    isAutoRefresh: true,
    isLoading: false
  };

  // Эксплорер блоков и транзакций
  static explorer = {
    query: '',
    block: null,
    tx: null,
    isLoading: false,
    error: null
  };

  // Раздел разработчика (Dev & Faucet)
  static dev = {
    minerWallet: null,
    minerAccount: null,
    faucetAmount: 1,
    faucetRecipient: '',
    faucetFee: 50,
    isProcessing: false,
    rawRpc: {
      method: 'GET',
      path: '/api/v1/network',
      body: '',
      result: null,
      isLoading: false
    }
  };

  // Всплывающие уведомления (Toasts)
  static toast = {
    isVisible: false,
    message: '',
    type: 'info',
    timeoutId: null
  };

  /**
   * Безопасно разбирает JSON-строку, возвращая null при любой ошибке парсинга (W-11).
   */
  static parseJsonSafe(text) {
    if (!text || typeof text !== 'string') return null;
    const trimmed = text.trim();
    if (!trimmed || (trimmed[0] !== '{' && trimmed[0] !== '[')) return null;
    try {
      return JSON.parse(trimmed);
    } catch (err) {
      return null;
    }
  }

  /**
   * Разбирает входящий ввод кошелька (одиночные ключи или строки nodewallet.dat).
   */
  static parseWalletInput(text, optionalPub = '') {
    if (!text || typeof text !== 'string') return null;
    let priv = text.trim();
    let pub = (optionalPub || '').trim();
    let addr = '';
    const lines = priv.split('\n').map(l => l.trim()).filter(Boolean);
    if (lines.length >= 2) {
      priv = lines[0];
      pub = lines[1];
      if (lines.length >= 3) addr = lines[2];
    }
    if (!priv || !pub) return null;
    return { priv, pub, addr };
  }

  /**
   * Единая функция верификации и восстановления кошелька из сырой записи и KeyVault (C-06).
   */
  static async restoreWalletRecord(record, vaultId) {
    if (!record) return null;
    const id = vaultId || record.id;
    let cryptoPriv = record.cryptoPrivKey || null;
    let cryptoPub = record.cryptoPubKey || null;

    const vaultData = await KeyVault.get(id);
    if (vaultData) {
      if (vaultData.cryptoPrivKey) cryptoPriv = vaultData.cryptoPrivKey;
      if (vaultData.cryptoPubKey) cryptoPub = vaultData.cryptoPubKey;
    }

    if (!cryptoPriv || !cryptoPub || !CryptoUtil.isValidAddress(record.address)) return null;

    const derivedAddr = await CryptoUtil.getAddressFromPublicKey(cryptoPub);
    const exportedPub = await CryptoUtil.exportPublicKey(cryptoPub);
    if (!derivedAddr || !exportedPub || derivedAddr !== record.address.trim().toLowerCase()) return null;

    return {
      id: id,
      label: record.label || 'Кошелек',
      address: derivedAddr,
      publicKeyBase64: exportedPub,
      privateKeyBase64: '',
      cryptoPrivKey: cryptoPriv,
      cryptoPubKey: cryptoPub
    };
  }

  /**
   * Инициализация состояния: загрузка из localStorage и первый опрос ноды.
   */
  static async init() {
    const savedRpc = localStorage.getItem(this.STORAGE_KEY_RPC);
    if (savedRpc) {
      this.rpcUrl = savedRpc;
      NodeApi.setBaseUrl(savedRpc);
    }
    this.rpcInput = this.rpcUrl;

    await this.loadWallets();
    // Перезаписываем хранилище: KeyVault.set не сохраняет privateKeyBase64 прошлых записей
    await this.saveWallets();
    if (this.isDevMode) {
      await this.loadDevWallet();
    } else {
      // Запись dev-кошелька прошлой версии тоже содержит открытый ключ
      await KeyVault.remove('__dev_miner_wallet__');
    }

    if (this.wallets.length === 0) {
      await this.createNewWallet('Основной кошелек');
    } else {
      const activeId = localStorage.getItem(this.STORAGE_KEY_ACTIVE);
      const found = this.wallets.find(w => w.id === activeId);
      await this.setActiveWallet(found || this.wallets[0]);
    }

    await this.refreshNetwork();
    await this.refreshFees();

    if (this.pollIntervalId) clearInterval(this.pollIntervalId);
    this.pollIntervalId = setInterval(() => {
      this.refreshNetwork();
      if (this.mempool.isAutoRefresh && this.currentTab === 'mempool') {
        this.refreshMempool();
      }
    }, 4000);
  }

  /**
   * Показывает всплывающее уведомление.
   */
  static notify(message, type = 'info') {
    if (this.toast.timeoutId) clearTimeout(this.toast.timeoutId);
    this.toast.message = message;
    this.toast.type = type;
    this.toast.isVisible = true;
    this.toast.timeoutId = setTimeout(() => {
      this.toast.isVisible = false;
      if (typeof m !== 'undefined') m.redraw();
    }, 4000);
    if (typeof m !== 'undefined') m.redraw();
  }

  /**
   * Обновляет базовый URL ноды и сохраняет в память со строгой валидацией протокола (R-15).
   */
  static setRpcUrl(url) {
    const normalized = UrlUtil.normalizeUrl(url);
    if (!normalized) return false;
    this.rpcUrl = normalized;
    this.rpcInput = normalized;
    NodeApi.setBaseUrl(normalized);
    localStorage.setItem(this.STORAGE_KEY_RPC, normalized);
    this.refreshNetwork();
    return true;
  }

  /**
   * Загружает сохраненные кошельки из LocalStorage и защищенного KeyVault без try/catch.
   */
  static async loadWallets() {
    const raw = localStorage.getItem(this.STORAGE_KEY_WALLETS);
    if (!raw) {
      this.wallets = [];
      return;
    }
    const list = this.parseJsonSafe(raw);
    if (!Array.isArray(list)) {
      this.wallets = [];
      return;
    }
    const validWallets = [];
    for (const w of list) {
      const restored = await this.restoreWalletRecord(w, w.id);
      if (restored) validWallets.push(restored);
    }
    this.wallets = validWallets;
  }

  /**
   * Сохраняет кошельки: открытые метаданные в LocalStorage, а CryptoKey в KeyVault (C-06).
   */
  static async saveWallets() {
    const serializable = [];
    for (const w of this.wallets) {
      if (w.cryptoPrivKey) {
        await KeyVault.set(w.id, {
          cryptoPrivKey: w.cryptoPrivKey,
          cryptoPubKey: w.cryptoPubKey || null
        });
      }
      serializable.push({
        id: w.id,
        label: w.label,
        address: w.address,
        publicKeyBase64: w.publicKeyBase64
      });
    }
    localStorage.setItem(this.STORAGE_KEY_WALLETS, JSON.stringify(serializable));
  }

  /**
   * Создает новый Ed25519-кошелек и делает его активным.
   */
  static async createNewWallet(label = 'Кошелек') {
    const kp = await CryptoUtil.generateKeyPair();
    if (!kp) {
      this.notify('Не удалось сгенерировать криптографические ключи', 'error');
      return null;
    }

    const wallet = {
      id: 'w_' + Date.now() + '_' + Math.random().toString(36).slice(2, 7),
      label: label,
      address: kp.address,
      publicKeyBase64: kp.publicKeyBase64,
      privateKeyBase64: kp.privateKeyBase64,
      cryptoPrivKey: kp.privateKey,
      cryptoPubKey: kp.publicKey
    };

    this.wallets.push(wallet);
    await this.saveWallets();
    await this.setActiveWallet(wallet);
    // Ключ экспортируем сразу: после перезагрузки страницы он уже недоступен
    Format.downloadWalletFile(wallet);
    this.notify(`Создан новый кошелек: ${wallet.address.slice(0, 10)}...`, 'success');
    return wallet;
  }

  /**
   * Импортирует кошелек по строке приватного ключа PKCS#8 Base64 или nodewallet.dat.
   */
  static async importWallet(privInput, pubInput = '', label = 'Импортированный') {
    const parsed = this.parseWalletInput(privInput, pubInput);
    if (!parsed) {
      this.notify('Требуются оба ключа (приватный PKCS#8 и открытый X.509) или 3 строки nodewallet.dat', 'error');
      return null;
    }

    const imported = await CryptoUtil.loadWalletFromData(parsed.priv, parsed.pub, parsed.addr);
    if (!imported) {
      this.notify('Некорректные ключи Ed25519 (проверьте PKCS#8 и X.509 Base64)', 'error');
      return null;
    }

    const existing = this.wallets.find(w => w.address === imported.address);
    if (existing) {
      existing.label = label;
      await this.saveWallets();
      await this.setActiveWallet(existing);
      this.notify('Кошелек уже существует, метка обновлена', 'info');
      return existing;
    }

    const wallet = {
      id: 'w_' + Date.now() + '_' + Math.random().toString(36).slice(2, 7),
      label: label,
      address: imported.address,
      publicKeyBase64: imported.publicKeyBase64,
      privateKeyBase64: imported.privateKeyBase64,
      cryptoPrivKey: imported.privateKey,
      cryptoPubKey: imported.publicKey
    };

    this.wallets.push(wallet);
    await this.saveWallets();
    await this.setActiveWallet(wallet);
    // Ключ экспортируем сразу: после перезагрузки страницы он уже недоступен
    Format.downloadWalletFile(wallet);
    this.notify('Кошелек успешно импортирован', 'success');
    return wallet;
  }

  /**
   * Делает указанный кошелек активным.
   */
  static async setActiveWallet(wallet) {
    if (!wallet) return;
    this.activeWallet = wallet;
    localStorage.setItem(this.STORAGE_KEY_ACTIVE, wallet.id);
    await this.refreshAccount();
    await this.refreshHistory();
    if (typeof m !== 'undefined') m.redraw();
  }

  /**
   * Обновляет состояние сети и проверяет онлайн-статус.
   */
  static async refreshNetwork() {
    const res = await NodeApi.getNetworkState();
    if (res.isOk && res.data) {
      const isNewBlock = this.isOnline && this.network.height !== res.data.height;
      this.isOnline = true;
      this.network = res.data;
      if (isNewBlock) {
        this.refreshAccount();
        this.refreshHistory(this.history.page);
        if (this.isDevMode && this.dev.minerWallet) this.refreshMinerAccount();
      }
    } else {
      this.isOnline = false;
    }
    if (typeof m !== 'undefined') m.redraw();
  }

  /**
   * Обновляет рекомендуемые комиссии сети со строгой валидацией диапазона.
   */
  static async refreshFees() {
    const [low, norm, high] = await Promise.all([
      NodeApi.getEstimatedFee('low'),
      NodeApi.getEstimatedFee('normal'),
      NodeApi.getEstimatedFee('high')
    ]);
    if (low.isOk && low.data && Number.isSafeInteger(low.data.fee) && low.data.fee >= 1 && low.data.fee <= 100000000) {
      this.fees.low = low.data.fee;
    }
    if (norm.isOk && norm.data && Number.isSafeInteger(norm.data.fee) && norm.data.fee >= 1 && norm.data.fee <= 100000000) {
      this.fees.normal = norm.data.fee;
    }
    if (high.isOk && high.data && Number.isSafeInteger(high.data.fee) && high.data.fee >= 1 && high.data.fee <= 100000000) {
      this.fees.high = high.data.fee;
    }
    if (typeof m !== 'undefined') m.redraw();
  }

  /**
   * Обновляет баланс и nonce активного аккаунта.
   */
  static async refreshAccount() {
    if (!this.activeWallet || !this.activeWallet.address) return;
    this.activeAccount.isLoading = true;
    const res = await NodeApi.getAddressState(this.activeWallet.address);
    this.activeAccount.isLoading = false;

    if (res.isOk && res.data) {
      this.activeAccount.balance = res.data.balance;
      this.activeAccount.unconfirmedBalance = res.data.unconfirmedBalance;
      this.activeAccount.nonce = res.data.nonce;
      this.activeAccount.txCount = res.data.txCount;
    } else if (res.status === 404) {
      this.activeAccount.balance = 0;
      this.activeAccount.unconfirmedBalance = 0;
      this.activeAccount.nonce = 0;
      this.activeAccount.txCount = 0;
    }
    if (typeof m !== 'undefined') m.redraw();
  }

  /**
   * Загружает историю транзакций активного кошелька с пагинацией.
   */
  static async refreshHistory(page = 0) {
    if (!this.activeWallet || !this.activeWallet.address) return;
    this.history.isLoading = true;
    this.history.page = page;
    const offset = page * this.history.limit;

    const res = await NodeApi.getAddressTransactions(
      this.activeWallet.address,
      this.history.limit,
      offset
    );
    this.history.isLoading = false;

    if (res.isOk && res.data) {
      this.history.total = res.data.total;
      this.history.items = res.data.txs || [];
    } else {
      this.history.items = [];
      this.history.total = 0;
    }
    if (typeof m !== 'undefined') m.redraw();
  }

  /**
   * Загружает транзакции из очереди Mempool.
   */
  static async refreshMempool() {
    this.mempool.isLoading = true;
    const res = await NodeApi.getMempool();
    this.mempool.isLoading = false;

    if (res.isOk && res.data) {
      this.mempool.count = res.data.count;
      this.mempool.items = res.data.transactions || [];
    }
    if (typeof m !== 'undefined') m.redraw();
  }

  /**
   * Поиск блока или транзакции в эксплорере.
   */
  static async searchExplorer(query) {
    const q = (query || '').trim();
    if (!q) return;
    this.explorer.query = q;
    this.explorer.isLoading = true;
    this.explorer.error = null;
    this.explorer.block = null;
    this.explorer.tx = null;

    const blockRes = await NodeApi.getBlock(q);
    if (blockRes.isOk && blockRes.data) {
      this.explorer.block = blockRes.data;
      this.explorer.isLoading = false;
      if (typeof m !== 'undefined') m.redraw();
      return;
    }

    const txRes = await NodeApi.getTransaction(q);
    if (txRes.isOk && txRes.data) {
      this.explorer.tx = txRes.data;
      this.explorer.isLoading = false;
      if (typeof m !== 'undefined') m.redraw();
      return;
    }

    this.explorer.isLoading = false;
    this.explorer.error = 'Блок или транзакция не найдены по запросу: ' + q;
    if (typeof m !== 'undefined') m.redraw();
  }

  /**
   * Загружает кошелек разработчика/майнера из KeyVault и LocalStorage без try/catch.
   */
  static async loadDevWallet() {
    const raw = localStorage.getItem(this.STORAGE_KEY_DEV_WALLET);
    if (!raw) return;
    const parsed = this.parseJsonSafe(raw);
    if (!parsed) return;
    const restored = await this.restoreWalletRecord(parsed, '__dev_miner_wallet__');
    if (!restored) return;
    this.dev.minerWallet = restored;
    await this.refreshMinerAccount();
  }

  /**
   * Сохраняет кошелек разработчика/майнера в KeyVault (IndexedDB) без открытых ключей (C-06).
   */
  static async saveDevWallet(wallet) {
    if (!wallet || !wallet.cryptoPrivKey) return;
    await KeyVault.set('__dev_miner_wallet__', {
      cryptoPrivKey: wallet.cryptoPrivKey,
      cryptoPubKey: wallet.cryptoPubKey || null
    });
    localStorage.setItem(this.STORAGE_KEY_DEV_WALLET, JSON.stringify({
      publicKeyBase64: (wallet.publicKeyBase64 || '').trim(),
      address: (wallet.address || '').trim()
    }));
  }

  /**
   * Обновляет баланс кошелька майнера/разработчика.
   */
  static async refreshMinerAccount() {
    if (!this.dev.minerWallet || !this.dev.minerWallet.address) return;
    const res = await NodeApi.getAddressState(this.dev.minerWallet.address);
    if (res.isOk && res.data) {
      this.dev.minerAccount = res.data;
    } else {
      this.dev.minerAccount = { balance: 0, nonce: 0, unconfirmedBalance: 0 };
    }
    if (typeof m !== 'undefined') m.redraw();
  }

  /**
   * Вычисляет следующий валидный nonce для адреса со строгой валидацией числа.
   */
  static async getNextNonce(address) {
    if (!address) return null;
    const cleanAddr = address.trim().toLowerCase();

    const stateRes = await NodeApi.getAddressState(cleanAddr);
    if (stateRes.isOk && stateRes.data) {
      const nextP = stateRes.data.nextPendingNonce;
      if (Number.isSafeInteger(nextP) && nextP >= 0) return nextP;
      const n = stateRes.data.nonce;
      if (Number.isSafeInteger(n) && n >= 0) return n;
    } else if (stateRes.status === 404) {
      return 0;
    }
    return null;
  }

  /**
   * Унифицированная сборка, подпись и отправка транзакции в сеть с поддержкой явного nonce.
   */
  static async buildAndSendTx(wallet, to, amountAtoms, feeAtoms, comment = '', explicitNonce = null) {
    if (!wallet || !wallet.cryptoPrivKey) {
      return { isOk: false, error: 'Приватный ключ кошелька не готов' };
    }
    const cleanTo = (to || '').trim().toLowerCase();
    if (!CryptoUtil.isValidAddress(cleanTo)) {
      return { isOk: false, error: 'Некорректный Fox-адрес получателя (64 символа с f,o,x)' };
    }
    const cleanFrom = wallet.address.toLowerCase();
    if (cleanTo === cleanFrom) {
      return { isOk: false, error: 'Нельзя отправить средства самому себе' };
    }
    if (!Number.isSafeInteger(amountAtoms) || amountAtoms <= 0) {
      return { isOk: false, error: 'Некорректная сумма перевода' };
    }
    if (!Number.isSafeInteger(feeAtoms) || feeAtoms < 1 || feeAtoms > 100000000) {
      return { isOk: false, error: 'Некорректная комиссия' };
    }
    const cleanComment = (comment || '').trim();
    if (cleanComment.length > 256) {
      return { isOk: false, error: 'Комментарий превышает 256 символов' };
    }
    const rawChain = (this.network && this.network.chainId && this.network.chainId !== '-')
      ? this.network.chainId : 'foxchain-main-1';
    const chainId = (rawChain || '').trim();
    if (!chainId || chainId.length > 64 || chainId.includes('|')) {
      return { isOk: false, error: 'Некорректный идентификатор сети chainId' };
    }

    let nonce = explicitNonce;
    if (nonce == null) {
      nonce = await this.getNextNonce(cleanFrom);
    }
    if (nonce == null || !Number.isSafeInteger(nonce) || nonce < 0) {
      return { isOk: false, error: 'Не удалось определить nonce адреса из сети' };
    }

    const ts = Date.now();
    const canonical = CryptoUtil.buildCanonicalString(cleanFrom, cleanTo, amountAtoms, feeAtoms, nonce, ts, cleanComment, chainId);
    if (!canonical) {
      return { isOk: false, error: 'Ошибка формирования канонической строки транзакции' };
    }
    const signature = await CryptoUtil.sign(wallet.cryptoPrivKey, canonical);
    if (!signature) {
      return { isOk: false, error: 'Ошибка формирования цифровой подписи Ed25519' };
    }

    const payload = {
      chainId: chainId,
      from: cleanFrom,
      to: cleanTo,
      amount: amountAtoms,
      fee: feeAtoms,
      nonce: nonce,
      timestamp: ts,
      publicKey: wallet.publicKeyBase64,
      signature: signature,
      comment: cleanComment || null
    };

    return await NodeApi.sendTransaction(payload);
  }
}
