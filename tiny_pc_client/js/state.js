/**
 * Централизованное хранилище состояния приложения (State Management).
 */
const AppState = {

  STORAGE_KEY_WALLETS: 'nodechain_wallets',
  STORAGE_KEY_ACTIVE: 'nodechain_active_wallet',
  STORAGE_KEY_RPC: 'nodechain_rpc_url',
  STORAGE_KEY_DEV_WALLET: 'nodechain_dev_wallet',

  currentTab: 'wallet',

  // Параметры подключения к ноде
  rpcUrl: 'http://localhost:8080',
  isOnline: false,
  network: {
    chainId: '-',
    height: 0,
    latestBlockHash: '-',
    peers: 0,
    isSynced: false,
    version: '-'
  },

  // Комиссии сети
  fees: {
    low: 25,
    normal: 50,
    high: 100
  },

  // Список локальных кошельков
  wallets: [],
  activeWallet: null, // { id, label, address, publicKeyBase64, privateKeyBase64, cryptoPrivKey, cryptoPubKey }
  activeAccount: {
    balance: 0,
    unconfirmedBalance: 0,
    nonce: 0,
    txCount: 0,
    isLoading: false
  },

  // История транзакций активного кошелька
  history: {
    total: 0,
    items: [],
    page: 0,
    limit: 10,
    isLoading: false
  },

  // Мемпул
  mempool: {
    count: 0,
    items: [],
    isAutoRefresh: true,
    isLoading: false
  },

  // Эксплорер блоков и транзакций
  explorer: {
    query: '',
    block: null,
    tx: null,
    isLoading: false,
    error: null
  },

  // Раздел разработчика (Dev & Faucet)
  dev: {
    minerWallet: null, // { address, privateKeyBase64, publicKeyBase64, cryptoPrivKey }
    minerAccount: null, // { balance, nonce }
    faucetAmount: 1, // FOXT
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
  },

  // Всплывающие уведомления (Toasts)
  toast: {
    visible: false,
    message: '',
    type: 'info',
    timeoutId: null
  },

  /**
   * Инициализация состояния: загрузка из localStorage и первый опрос ноды.
   */
  async init() {
    const savedRpc = localStorage.getItem(this.STORAGE_KEY_RPC);
    if (savedRpc) {
      this.rpcUrl = savedRpc;
      NodeApi.setBaseUrl(savedRpc);
    }

    this.loadWallets();
    this.loadDevWallet();

    if (this.wallets.length === 0) {
      await this.createNewWallet('Основной кошелек');
    } else {
      const activeId = localStorage.getItem(this.STORAGE_KEY_ACTIVE);
      const found = this.wallets.find(w => w.id === activeId);
      await this.setActiveWallet(found || this.wallets[0]);
    }

    await this.refreshNetwork();
    await this.refreshFees();

    // Запуск фонового поллинга сети и мемпула
    setInterval(() => {
      this.refreshNetwork();
      if (this.mempool.isAutoRefresh && this.currentTab === 'mempool') {
        this.refreshMempool();
      }
    }, 4000);
  },

  /**
   * Показывает всплывающее уведомление.
   */
  notify(message, type = 'info') {
    if (this.toast.timeoutId) clearTimeout(this.toast.timeoutId);
    this.toast.message = message;
    this.toast.type = type;
    this.toast.visible = true;
    this.toast.timeoutId = setTimeout(() => {
      this.toast.visible = false;
      if (typeof m !== 'undefined') m.redraw();
    }, 4000);
    if (typeof m !== 'undefined') m.redraw();
  },

  /**
   * Обновляет базовый URL ноды и сохраняет в память.
   */
  setRpcUrl(url) {
    if (!url) return;
    this.rpcUrl = url.trim();
    NodeApi.setBaseUrl(this.rpcUrl);
    localStorage.setItem(this.STORAGE_KEY_RPC, this.rpcUrl);
    this.refreshNetwork();
  },

  /**
   * Загружает сохраненные кошельки из LocalStorage.
   */
  loadWallets() {
    try {
      const raw = localStorage.getItem(this.STORAGE_KEY_WALLETS);
      this.wallets = raw ? JSON.parse(raw) : [];
    } catch (err) {
      this.wallets = [];
    }
  },

  /**
   * Сохраняет кошельки в LocalStorage (без runtime CryptoKey объектов).
   */
  saveWallets() {
    const serializable = this.wallets.map(w => ({
      id: w.id,
      label: w.label,
      address: w.address,
      publicKeyBase64: w.publicKeyBase64,
      privateKeyBase64: w.privateKeyBase64
    }));
    localStorage.setItem(this.STORAGE_KEY_WALLETS, JSON.stringify(serializable));
  },

  /**
   * Создает новый Ed25519-кошелек и делает его активным.
   */
  async createNewWallet(label = 'Кошелек') {
    const kp = await CryptoUtil.generateKeyPair();
    if (!kp) {
      this.notify('Не удалось сгенерировать криптографические ключи', 'error');
      return null;
    }

    const wallet = {
      id: 'w_' + Date.now() + '_' + Math.random().toString(36).substr(2, 5),
      label: label,
      address: kp.address,
      publicKeyBase64: kp.publicKeyBase64,
      privateKeyBase64: kp.privateKeyBase64,
      cryptoPrivKey: kp.privateKey,
      cryptoPubKey: kp.publicKey
    };

    this.wallets.push(wallet);
    this.saveWallets();
    await this.setActiveWallet(wallet);
    this.notify(`Создан новый кошелек: ${wallet.address.substr(0, 10)}...`, 'success');
    return wallet;
  },

  /**
   * Импортирует кошелек по строке приватного ключа PKCS#8 Base64 или nodewallet.dat.
   */
  async importWallet(privB64, pubB64 = '', label = 'Импортированный') {
    if (!privB64) {
      this.notify('Укажите ключи кошелька', 'error');
      return null;
    }
    let cleanPriv = privB64.trim();
    let cleanPub = (pubB64 || '').trim();
    let addr = '';

    const lines = cleanPriv.split('\n').map(l => l.trim()).filter(Boolean);
    if (lines.length >= 2) {
      cleanPriv = lines[0];
      cleanPub = lines[1];
      if (lines.length >= 3) addr = lines[2];
    }

    if (!cleanPriv || !cleanPub) {
      this.notify('Требуются оба ключа (приватный PKCS#8 и открытый X.509) или 3 строки nodewallet.dat', 'error');
      return null;
    }

    const imported = await CryptoUtil.loadWalletFromData(cleanPriv, cleanPub, addr);
    if (!imported) {
      this.notify('Некорректные ключи Ed25519 (проверьте PKCS#8 и X.509 Base64)', 'error');
      return null;
    }

    const wallet = {
      id: 'w_' + Date.now(),
      label: label,
      address: imported.address,
      publicKeyBase64: imported.publicKeyBase64,
      privateKeyBase64: imported.privateKeyBase64,
      cryptoPrivKey: imported.privateKey,
      cryptoPubKey: imported.publicKey
    };

    this.wallets.push(wallet);
    this.saveWallets();
    await this.setActiveWallet(wallet);
    this.notify('Кошелек успешно импортирован', 'success');
    return wallet;
  },

  /**
   * Делает указанный кошелек активным и восстанавливает WebCrypto ключи.
   */
  async setActiveWallet(wallet) {
    if (!wallet) return;
    this.activeWallet = wallet;
    localStorage.setItem(this.STORAGE_KEY_ACTIVE, wallet.id);

    if ((!wallet.cryptoPrivKey || !wallet.cryptoPubKey) && wallet.privateKeyBase64 && wallet.publicKeyBase64) {
      const restored = await CryptoUtil.loadWalletFromData(wallet.privateKeyBase64, wallet.publicKeyBase64, wallet.address);
      if (restored) {
        wallet.cryptoPrivKey = restored.privateKey;
        wallet.cryptoPubKey = restored.publicKey;
        wallet.address = restored.address;
      }
    }

    await this.refreshAccount();
    await this.refreshHistory();
    if (typeof m !== 'undefined') m.redraw();
  },

  /**
   * Обновляет состояние сети и проверяет онлайн-статус.
   */
  async refreshNetwork() {
    const res = await NodeApi.getNetworkState();
    if (res.isOk && res.data) {
      this.isOnline = true;
      this.network = res.data;
    } else {
      this.isOnline = false;
    }
    if (typeof m !== 'undefined') m.redraw();
  },

  /**
   * Обновляет рекомендуемые комиссии сети.
   */
  async refreshFees() {
    const [low, norm, high] = await Promise.all([
      NodeApi.getEstimatedFee('low'),
      NodeApi.getEstimatedFee('normal'),
      NodeApi.getEstimatedFee('high')
    ]);
    if (low.isOk) this.fees.low = low.data.fee;
    if (norm.isOk) this.fees.normal = norm.data.fee;
    if (high.isOk) this.fees.high = high.data.fee;
  },

  /**
   * Обновляет баланс и nonce активного аккаунта.
   */
  async refreshAccount() {
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
      // Адрес еще не совершал транзакций
      this.activeAccount.balance = 0;
      this.activeAccount.unconfirmedBalance = 0;
      this.activeAccount.nonce = 0;
      this.activeAccount.txCount = 0;
    }
    if (typeof m !== 'undefined') m.redraw();
  },

  /**
   * Загружает историю транзакций активного кошелька с пагинацией.
   */
  async refreshHistory(page = 0) {
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
  },

  /**
   * Загружает транзакции из очереди Mempool.
   */
  async refreshMempool() {
    this.mempool.isLoading = true;
    const res = await NodeApi.getMempool();
    this.mempool.isLoading = false;

    if (res.isOk && res.data) {
      this.mempool.count = res.data.count;
      this.mempool.items = res.data.transactions || [];
    }
    if (typeof m !== 'undefined') m.redraw();
  },

  /**
   * Поиск блока или транзакции в эксплорере.
   */
  async searchExplorer(query) {
    const q = (query || '').trim();
    if (!q) return;
    this.explorer.query = q;
    this.explorer.isLoading = true;
    this.explorer.error = null;
    this.explorer.block = null;
    this.explorer.tx = null;

    // Сначала пробуем запросить как блок
    const blockRes = await NodeApi.getBlock(q);
    if (blockRes.isOk && blockRes.data) {
      this.explorer.block = blockRes.data;
      this.explorer.isLoading = false;
      if (typeof m !== 'undefined') m.redraw();
      return;
    }

    // Если не блок — пробуем запросить как транзакцию
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
  },

  /**
   * Загружает кошелек разработчика/майнера из LocalStorage.
   */
  async loadDevWallet() {
    try {
      const raw = localStorage.getItem(this.STORAGE_KEY_DEV_WALLET);
      if (!raw) return;
      const parsed = JSON.parse(raw);
      if (parsed.privateKeyBase64 && parsed.publicKeyBase64) {
        const imported = await CryptoUtil.loadWalletFromData(parsed.privateKeyBase64, parsed.publicKeyBase64, parsed.address);
        if (imported) {
          this.dev.minerWallet = {
            address: imported.address,
            privateKeyBase64: imported.privateKeyBase64,
            publicKeyBase64: imported.publicKeyBase64,
            cryptoPrivKey: imported.privateKey,
            cryptoPubKey: imported.publicKey
          };
          this.refreshMinerAccount();
        }
      }
    } catch (ignored) {}
  },

  /**
   * Сохраняет кошелек разработчика/майнера в LocalStorage.
   */
  saveDevWallet(privB64, pubB64 = '', addr = '') {
    const data = {
      privateKeyBase64: privB64.trim(),
      publicKeyBase64: pubB64.trim(),
      address: addr.trim()
    };
    localStorage.setItem(this.STORAGE_KEY_DEV_WALLET, JSON.stringify(data));
  },

  /**
   * Обновляет баланс кошелька майнера/разработчика.
   */
  async refreshMinerAccount() {
    if (!this.dev.minerWallet || !this.dev.minerWallet.address) return;
    const res = await NodeApi.getAddressState(this.dev.minerWallet.address);
    if (res.isOk && res.data) {
      this.dev.minerAccount = res.data;
    } else {
      this.dev.minerAccount = { balance: 0, nonce: 0, unconfirmedBalance: 0 };
    }
    if (typeof m !== 'undefined') m.redraw();
  },

  /**
   * Вычисляет следующий валидный nonce для адреса с учетом очереди мемпула.
   * Позволяет безопасно отправлять транзакции подряд (pipelining) до включения в блок.
   */
  async getNextNonce(address) {
    if (!address) return 0;
    const cleanAddr = address.trim().toLowerCase();

    let nextNonce = 0;
    const stateRes = await NodeApi.getAddressState(cleanAddr);
    if (stateRes.isOk && stateRes.data && typeof stateRes.data.nonce === 'number') {
      nextNonce = stateRes.data.nonce;
    }

    const memRes = await NodeApi.getMempool();
    if (!memRes.isOk || !memRes.data || !Array.isArray(memRes.data.transactions)) {
      return nextNonce;
    }

    for (const tx of memRes.data.transactions) {
      if (!tx || !tx.from) continue;
      if (tx.from.toLowerCase() === cleanAddr) {
        nextNonce = Math.max(nextNonce, (tx.nonce || 0) + 1);
      }
    }

    return nextNonce;
  }
};

