/**
 * Криптографический модуль клиента NodeChain (Static-First).
 * Нативный Web Crypto API (Ed25519, SHA-256, Fox-адреса) без использования try/catch.
 */
class CryptoUtil {
  static #ADDRESS_PREFIX_PATTERN = /^[0-9a-fox]{5}$/i;
  static #ADDRESS_SUFFIX_PATTERN = /^[0-9a-f]{59}$/i;
  static #BASE64_PATTERN = /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/;

  static #CRC32_TABLE = (() => {
    const table = [];
    for (let i = 0; i < 256; i++) {
      let c = i;
      for (let k = 0; k < 8; k++) {
        c = ((c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1));
      }
      table[i] = c;
    }
    return table;
  })();

  /**
   * Преобразует ArrayBuffer в строку Base64.
   */
  static arrayBufferToBase64(buffer) {
    if (!buffer) return '';
    const bytes = new Uint8Array(buffer);
    let binary = '';
    for (let i = 0; i < bytes.byteLength; i++) {
      binary += String.fromCharCode(bytes[i]);
    }
    return window.btoa(binary);
  }

  /**
   * Преобразует строку Base64 в ArrayBuffer с проверкой валидности по регулярному выражению без лишних исключений.
   */
  static base64ToArrayBuffer(base64) {
    if (!base64 || typeof base64 !== 'string') return null;
    const cleaned = base64.replace(/\s+/g, '');
    if (!cleaned || cleaned.length % 4 !== 0) return null;
    if (!this.#BASE64_PATTERN.test(cleaned)) return null;

    let binary;
    try {
      binary = window.atob(cleaned);
    } catch (e) {
      return null;
    }
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) {
      bytes[i] = binary.charCodeAt(i);
    }
    if (this.arrayBufferToBase64(bytes.buffer) !== cleaned) return null;
    return bytes.buffer;
  }

  /**
   * Генерирует новую пару ключей Ed25519 и повторно импортирует приватный ключ как неизвлекаемый.
   */
  static async generateKeyPair() {
    const kp = await window.crypto.subtle.generateKey(
      { name: 'Ed25519' },
      true,
      ['sign', 'verify']
    ).catch(() => null);
    if (!kp) return null;

    const privB64 = await this.exportPrivateKey(kp.privateKey);
    const pubB64 = await this.exportPublicKey(kp.publicKey);
    const address = await this.getAddressFromPublicKey(kp.publicKey);
    if (!privB64 || !pubB64 || !address) return null;

    // Реимпортируем приватный ключ с extractable: false для защиты от утечки
    const nonExtractablePrivKey = await this.importPrivateKey(privB64, false);
    if (!nonExtractablePrivKey) return null;

    return {
      privateKey: nonExtractablePrivKey,
      publicKey: kp.publicKey,
      privateKeyBase64: privB64,
      publicKeyBase64: pubB64,
      address: address
    };
  }

  /**
   * Экспортирует открытый ключ в формат Base64 X.509 SPKI.
   */
  static async exportPublicKey(pubKey) {
    if (!pubKey) return null;
    const spki = await window.crypto.subtle.exportKey('spki', pubKey).catch(() => null);
    if (!spki) return null;
    return this.arrayBufferToBase64(spki);
  }

  /**
   * Экспортирует закрытый ключ в формат Base64 PKCS#8.
   */
  static async exportPrivateKey(privKey) {
    if (!privKey) return null;
    const pkcs8 = await window.crypto.subtle.exportKey('pkcs8', privKey).catch(() => null);
    if (!pkcs8) return null;
    return this.arrayBufferToBase64(pkcs8);
  }

  /**
   * Восстанавливает открытый ключ из строки Base64.
   */
  static async importPublicKey(b64) {
    if (!b64) return null;
    const buf = this.base64ToArrayBuffer(b64);
    if (!buf) return null;
    return await window.crypto.subtle.importKey(
      'spki',
      buf,
      { name: 'Ed25519' },
      true,
      ['verify']
    ).catch(() => null);
  }

  /**
   * Восстанавливает закрытый ключ из строки Base64 (по умолчанию extractable: false для безопасности).
   */
  static async importPrivateKey(b64, isExtractable = false) {
    if (!b64) return null;
    const buf = this.base64ToArrayBuffer(b64);
    if (!buf) return null;
    return await window.crypto.subtle.importKey(
      'pkcs8',
      buf,
      { name: 'Ed25519' },
      isExtractable,
      ['sign']
    ).catch(() => null);
  }

  /**
   * Восстанавливает и валидирует пару ключей Ed25519 со строгой проверкой соответствия без try/catch.
   */
  static async loadWalletFromData(privB64, pubB64, addr = '') {
    if (!privB64 || !pubB64) return null;
    const cleanPriv = privB64.trim();
    const cleanPub = pubB64.trim();
    const privKey = await this.importPrivateKey(cleanPriv, false);
    const pubKey = await this.importPublicKey(cleanPub);
    if (!privKey || !pubKey) return null;

    const derivedAddr = await this.getAddressFromPublicKey(pubKey);
    if (!derivedAddr) return null;
    if (addr && addr.trim().toLowerCase() !== derivedAddr) return null;

    const challenge = new TextEncoder().encode('nodechain-key-verify');
    const sigBuf = await window.crypto.subtle.sign({ name: 'Ed25519' }, privKey, challenge).catch(() => null);
    if (!sigBuf) return null;
    const isPairValid = await window.crypto.subtle.verify({ name: 'Ed25519' }, pubKey, sigBuf, challenge).catch(() => false);
    if (!isPairValid) return null;

    return {
      privateKey: privKey,
      publicKey: pubKey,
      privateKeyBase64: cleanPriv,
      publicKeyBase64: cleanPub,
      address: derivedAddr
    };
  }

  /**
   * Вычисляет уникальный Fox-адрес из объекта открытого ключа или Base64 строки.
   */
  static async getAddressFromPublicKey(pubKeyInput) {
    if (!pubKeyInput) return null;
    let spkiBuffer;
    if (typeof pubKeyInput === 'string') {
      spkiBuffer = this.base64ToArrayBuffer(pubKeyInput);
    } else {
      spkiBuffer = await window.crypto.subtle.exportKey('spki', pubKeyInput).catch(() => null);
    }
    if (!spkiBuffer) return null;

    const hashBuffer = await window.crypto.subtle.digest('SHA-256', spkiBuffer).catch(() => null);
    if (!hashBuffer) return null;
    const hashBytes = new Uint8Array(hashBuffer);

    let hexArr = Array.from(hashBytes).map(b => b.toString(16).padStart(2, '0')).join('').split('');
    const posF = hashBytes[0] % 5;
    let posO = hashBytes[1] % 5;
    while (posO === posF) {
      posO = (posO + 1) % 5;
    }
    let posX = hashBytes[2] % 5;
    while (posX === posF || posX === posO) {
      posX = (posX + 1) % 5;
    }

    hexArr[posF] = 'f';
    hexArr[posO] = 'o';
    hexArr[posX] = 'x';

    const payload = hexArr.slice(0, 56).join('');
    const checksum = this.computeCrc32(payload);
    for (let i = 0; i < 8; i++) {
      hexArr[56 + i] = checksum[i];
    }
    return hexArr.join('').toLowerCase();
  }

  /**
   * Вычисляет 8-символьную hex контрольную сумму CRC32 от строки.
   */
  static computeCrc32(str) {
    if (!str) return '00000000';
    let crc = 0 ^ (-1);
    for (let i = 0; i < str.length; i++) {
      crc = (crc >>> 8) ^ this.#CRC32_TABLE[(crc ^ str.charCodeAt(i)) & 0xFF];
    }
    return ((crc ^ (-1)) >>> 0).toString(16).padStart(8, '0');
  }

  /**
   * Проверяет формат Fox-адреса сети и его контрольную сумму.
   */
  static isValidAddress(addr) {
    if (!addr || typeof addr !== 'string' || addr.length !== 64) return false;
    const lower = addr.toLowerCase();
    const prefix = lower.substring(0, 5);
    if (!this.#ADDRESS_PREFIX_PATTERN.test(prefix)) return false;
    if (prefix.indexOf('f') < 0 || prefix.indexOf('o') < 0 || prefix.indexOf('x') < 0) return false;
    const suffix = lower.substring(5);
    if (!this.#ADDRESS_SUFFIX_PATTERN.test(suffix)) return false;
    const payload = lower.substring(0, 56);
    const expected = this.computeCrc32(payload);
    return lower.substring(56).toLowerCase() === expected;
  }

  /**
   * Формирует каноническую строку для подписи транзакции, включая timestamp.
   */
  static buildCanonicalString(from, to, amount, fee, nonce, timestamp, comment, chainId) {
    if (!chainId || typeof chainId !== 'string' || chainId.length > 64 || chainId.includes('|')) {
      return null;
    }
    const f = (from || '').toLowerCase();
    const t = (to || '').toLowerCase();
    const pChain = `${chainId.trim()}|`;
    const head = `${pChain}${f}|${t}|${amount}|${fee}|${nonce}|${timestamp}`;
    if (comment && comment.trim().length > 0) {
      return `${head}|${comment.trim()}`;
    }
    return head;
  }

  /**
   * Создает цифровую подпись канонической строки алгоритмом Ed25519 без try/catch.
   */
  static async sign(privKey, canonicalString) {
    if (!privKey || !canonicalString) return null;
    const dataBytes = new TextEncoder().encode(canonicalString);
    const sigBuf = await window.crypto.subtle.sign(
      { name: 'Ed25519' },
      privKey,
      dataBytes
    ).catch(() => null);
    if (!sigBuf) return null;
    return this.arrayBufferToBase64(sigBuf);
  }
}

/**
 * Персистентное безопасное хранилище приватных ключей в IndexedDB.
 * Объекты CryptoKey сохраняются нативно через Structured Clone с изоляцией ошибок окружения.
 */
class KeyVault {
  static #dbPromise = null;

  /**
   * Открывает базу данных IndexedDB для хранения ключей.
   */
  static getDb() {
    if (this.#dbPromise) return this.#dbPromise;
    this.#dbPromise = new Promise(resolve => {
      if (typeof window === 'undefined' || !window.indexedDB) {
        return resolve(null);
      }
      try {
        const req = window.indexedDB.open('nodechain_keystore', 1);
        req.onupgradeneeded = e => {
          const db = e.target.result;
          if (!db.objectStoreNames.contains('wallets')) {
            db.createObjectStore('wallets', { keyPath: 'id' });
          }
        };
        req.onsuccess = e => resolve(e.target.result);
        req.onerror = () => resolve(null);
      } catch (err) {
        resolve(null);
      }
    });
    return this.#dbPromise;
  }

  /**
   * Получает запись ключа кошелька по id с безопасной изоляцией транзакций.
   */
  static async get(id) {
    if (!id) return null;
    const db = await this.getDb();
    if (!db) return null;
    return new Promise(resolve => {
      try {
        const tx = db.transaction('wallets', 'readonly');
        tx.onerror = () => resolve(null);
        const store = tx.objectStore('wallets');
        const req = store.get(id);
        req.onsuccess = () => resolve(req.result || null);
        req.onerror = () => resolve(null);
      } catch (err) {
        resolve(null);
      }
    });
  }

  /**
   * Сохраняет криптографические ключи CryptoKey в IndexedDB без сохранения открытых секретов (C-06).
   */
  static async set(id, data) {
    if (!id || !data) return false;
    const db = await this.getDb();
    if (!db) return false;
    const record = {
      id: id,
      cryptoPrivKey: data.cryptoPrivKey || null,
      cryptoPubKey: data.cryptoPubKey || null
    };
    return new Promise(resolve => {
      try {
        const tx = db.transaction('wallets', 'readwrite');
        tx.oncomplete = () => resolve(true);
        tx.onerror = () => resolve(false);
        const store = tx.objectStore('wallets');
        store.put(record);
      } catch (err) {
        resolve(false);
      }
    });
  }

  /**
   * Удаляет запись ключа кошелька из IndexedDB с безопасной изоляцией транзакций.
   */
  static async remove(id) {
    if (!id) return false;
    const db = await this.getDb();
    if (!db) return false;
    return new Promise(resolve => {
      try {
        const tx = db.transaction('wallets', 'readwrite');
        tx.oncomplete = () => resolve(true);
        tx.onerror = () => resolve(false);
        const store = tx.objectStore('wallets');
        store.delete(id);
      } catch (err) {
        resolve(false);
      }
    });
  }
}
