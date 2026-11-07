/**
 * API-клиент взаимодействия с узлом NodeChain (Static-First).
 * Инкапсулирует вызовы REST API v1 с нормализацией ошибок.
 */
const NodeApi = {

  baseUrl: 'http://localhost:8080',

  /**
   * Устанавливает базовый URL ноды блокчейна.
   */
  setBaseUrl(url) {
    if (!url) return;
    this.baseUrl = url.replace(/\/+$/, '');
  },

  /**
   * Универсальный HTTP запрос с возвратом типизированного объекта результата.
   */
  async request(method, path, body = null) {
    const url = `${this.baseUrl}${path.startsWith('/') ? path : '/' + path}`;
    const opts = {
      method: method.toUpperCase(),
      headers: {
        'Accept': 'application/json'
      }
    };

    if (body != null) {
      opts.headers['Content-Type'] = 'application/json';
      opts.body = JSON.stringify(body);
    }

    try {
      const resp = await fetch(url, opts);
      const isJson = (resp.headers.get('content-type') || '').includes('application/json');
      const data = isJson ? await resp.json() : await resp.text();

      if (!resp.ok) {
        const errMsg = (data && data.message) ? data.message : `Ошибка HTTP ${resp.status}`;
        return { isOk: false, status: resp.status, error: errMsg, data: null };
      }

      return { isOk: true, status: resp.status, error: null, data: data };
    } catch (err) {
      return { isOk: false, status: 0, error: 'Сетевая ошибка или нода недоступна', data: null };
    }
  },

  /**
   * Получение состояния сети и узла (высота, пиры, chainId).
   */
  async getNetworkState() {
    return await this.request('GET', '/api/v1/network');
  },

  /**
   * Получение состояния адреса (баланс, nonce, неподтвержденный баланс).
   */
  async getAddressState(address) {
    if (!address) return { isOk: false, error: 'Адрес не указан' };
    return await this.request('GET', `/api/v1/addresses/${encodeURIComponent(address)}`);
  },

  /**
   * Оценка рекомендуемой комиссии сети (low, normal, high).
   */
  async getEstimatedFee(priority = 'normal') {
    return await this.request('GET', `/api/v1/transactions/fee?priority=${encodeURIComponent(priority)}`);
  },

  /**
   * Получение текущих транзакций в очереди mempool.
   */
  async getMempool() {
    return await this.request('GET', '/api/v1/transactions/mempool');
  },

  /**
   * История транзакций адреса с постраничной пагинацией.
   */
  async getAddressTransactions(address, limit = 25, offset = 0) {
    if (!address) return { isOk: false, error: 'Адрес не указан' };
    return await this.request('GET', `/api/v1/addresses/${encodeURIComponent(address)}/transactions?limit=${limit}&offset=${offset}`);
  },

  /**
   * Отправка новой подписанной транзакции в сеть.
   */
  async sendTransaction(payload) {
    if (!payload) return { isOk: false, error: 'Данные транзакции отсутствуют' };
    return await this.request('POST', '/api/v1/transactions/send', payload);
  },

  /**
   * Просмотр транзакции по хешу.
   */
  async getTransaction(txHash) {
    if (!txHash) return { isOk: false, error: 'Хеш транзакции не указан' };
    return await this.request('GET', `/api/v1/transactions/${encodeURIComponent(txHash)}`);
  },

  /**
   * Получение блока по номеру, хешу или слову 'latest'.
   */
  async getBlock(identifier = 'latest') {
    if (!identifier) identifier = 'latest';
    return await this.request('GET', `/api/v1/blocks/${encodeURIComponent(identifier)}`);
  }
};

