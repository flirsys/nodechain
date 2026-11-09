/**
 * API-клиент взаимодействия с узлом NodeChain (Static-First).
 * Инкапсулирует вызовы REST API v1 с нормализацией ошибок без использования исключений.
 */
class NodeApiClient {
  static #baseUrl = 'http://localhost:8080';

  /**
   * Возвращает текущий базовый URL.
   */
  static getBaseUrl() {
    return this.#baseUrl;
  }

  /**
   * Устанавливает базовый URL ноды блокчейна со строгой валидацией протокола.
   */
  static setBaseUrl(url) {
    const normalized = UrlUtil.normalizeUrl(url);
    if (!normalized) return;
    this.#baseUrl = normalized;
  }

  /**
   * Универсальный HTTP запрос с возвратом типизированного объекта результата без try/catch.
   */
  static async request(method, path, body = null) {
    const url = `${this.#baseUrl}${path.startsWith('/') ? path : '/' + path}`;
    const opts = {
      method: (method || 'GET').toUpperCase(),
      headers: {
        'Accept': 'application/json'
      }
    };

    if (body != null) {
      opts.headers['Content-Type'] = 'application/json';
      opts.body = JSON.stringify(body);
    }

    const resp = await fetch(url, opts).catch(() => null);
    if (!resp) {
      return { isOk: false, status: 0, error: 'Сетевая ошибка или нода недоступна', data: null };
    }

    const contentType = resp.headers.get('content-type') || '';
    const isJson = contentType.includes('application/json');
    const data = isJson
      ? await resp.json().catch(() => null)
      : await resp.text().catch(() => null);

    if (!resp.ok) {
      const errMsg = (data && data.message) ? data.message : `Ошибка HTTP ${resp.status}`;
      return { isOk: false, status: resp.status, error: errMsg, data: null };
    }

    return { isOk: true, status: resp.status, error: null, data: data };
  }

  /**
   * Получение состояния сети и узла (высота, пиры, chainId).
   */
  static async getNetworkState() {
    return await this.request('GET', '/api/v1/network');
  }

  /**
   * Получение состояния адреса (баланс, nonce, неподтвержденный баланс).
   */
  static async getAddressState(address) {
    if (!address) return { isOk: false, error: 'Адрес не указан' };
    return await this.request('GET', `/api/v1/addresses/${encodeURIComponent(address)}`);
  }

  /**
   * Оценка рекомендуемой комиссии сети (low, normal, high).
   */
  static async getEstimatedFee(priority = 'normal') {
    return await this.request('GET', `/api/v1/transactions/fee?priority=${encodeURIComponent(priority)}`);
  }

  /**
   * Получение текущих транзакций в очереди mempool с поддержкой пагинации.
   */
  static async getMempool(limit = 100, offset = 0) {
    return await this.request('GET', `/api/v1/transactions/mempool?limit=${limit}&offset=${offset}`);
  }

  /**
   * История транзакций адреса с постраничной пагинацией.
   */
  static async getAddressTransactions(address, limit = 25, offset = 0) {
    if (!address) return { isOk: false, error: 'Адрес не указан' };
    return await this.request('GET', `/api/v1/addresses/${encodeURIComponent(address)}/transactions?limit=${limit}&offset=${offset}`);
  }

  /**
   * Отправка новой подписанной транзакции в сеть.
   */
  static async sendTransaction(payload) {
    if (!payload) return { isOk: false, error: 'Данные транзакции отсутствуют' };
    return await this.request('POST', '/api/v1/transactions/send', payload);
  }

  /**
   * Просмотр транзакции по хешу.
   */
  static async getTransaction(txHash) {
    if (!txHash) return { isOk: false, error: 'Хеш транзакции не указан' };
    return await this.request('GET', `/api/v1/transactions/${encodeURIComponent(txHash)}`);
  }

  /**
   * Получение блока по номеру, хешу или слову 'latest'.
   */
  static async getBlock(identifier = 'latest') {
    const id = identifier || 'latest';
    return await this.request('GET', `/api/v1/blocks/${encodeURIComponent(id)}`);
  }
}

// Псевдоним для совместимости
const NodeApi = NodeApiClient;
