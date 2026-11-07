/**
 * Криптографический модуль клиента NodeChain (Static-First).
 * Использует нативный Web Crypto API браузера (Ed25519, SHA-256, Fox-адреса).
 */
const CryptoUtil = {

  ADDRESS_PATTERN: /^[0-9a-fox]{64}$/i,

  /**
   * Преобразует ArrayBuffer в строку Base64.
   */
  arrayBufferToBase64(buffer) {
    if (!buffer) return '';
    const bytes = new Uint8Array(buffer);
    let binary = '';
    for (let i = 0; i < bytes.byteLength; i++) {
      binary += String.fromCharCode(bytes[i]);
    }
    return window.btoa(binary);
  },

  /**
   * Преобразует строку Base64 в ArrayBuffer с безопасной обработкой битого формата.
   */
  base64ToArrayBuffer(base64) {
    if (!base64 || typeof base64 !== 'string') return null;
    const cleaned = base64.replace(/\s+/g, '');
    try {
      const binary = window.atob(cleaned);
      const bytes = new Uint8Array(binary.length);
      for (let i = 0; i < binary.length; i++) {
        bytes[i] = binary.charCodeAt(i);
      }
      return bytes.buffer;
    } catch (err) {
      return null;
    }
  },

  /**
   * Генерирует новую пару ключей Ed25519.
   */
  async generateKeyPair() {
    try {
      const kp = await window.crypto.subtle.generateKey(
        { name: 'Ed25519' },
        true,
        ['sign', 'verify']
      );
      const privB64 = await this.exportPrivateKey(kp.privateKey);
      const pubB64 = await this.exportPublicKey(kp.publicKey);
      const address = await this.getAddressFromPublicKey(kp.publicKey);
      return {
        privateKey: kp.privateKey,
        publicKey: kp.publicKey,
        privateKeyBase64: privB64,
        publicKeyBase64: pubB64,
        address: address
      };
    } catch (err) {
      console.error('Ошибка генерации ключей:', err);
      return null;
    }
  },

  /**
   * Экспортирует открытый ключ в формат Base64 X.509 SPKI.
   */
  async exportPublicKey(pubKey) {
    if (!pubKey) return null;
    try {
      const spki = await window.crypto.subtle.exportKey('spki', pubKey);
      return this.arrayBufferToBase64(spki);
    } catch (err) {
      return null;
    }
  },

  /**
   * Экспортирует закрытый ключ в формат Base64 PKCS#8.
   */
  async exportPrivateKey(privKey) {
    if (!privKey) return null;
    try {
      const pkcs8 = await window.crypto.subtle.exportKey('pkcs8', privKey);
      return this.arrayBufferToBase64(pkcs8);
    } catch (err) {
      return null;
    }
  },

  /**
   * Восстанавливает открытый ключ из строки Base64.
   */
  async importPublicKey(b64) {
    if (!b64) return null;
    try {
      const buf = this.base64ToArrayBuffer(b64);
      if (!buf) return null;
      return await window.crypto.subtle.importKey(
        'spki',
        buf,
        { name: 'Ed25519' },
        true,
        ['verify']
      );
    } catch (err) {
      return null;
    }
  },

  /**
   * Восстанавливает закрытый ключ из строки Base64.
   */
  async importPrivateKey(b64) {
    if (!b64) return null;
    try {
      const buf = this.base64ToArrayBuffer(b64);
      if (!buf) return null;
      return await window.crypto.subtle.importKey(
        'pkcs8',
        buf,
        { name: 'Ed25519' },
        true,
        ['sign']
      );
    } catch (err) {
      return null;
    }
  },

  /**
   * Восстанавливает и валидирует пару ключей Ed25519.
   * Возвращает null, если не удалось импортировать privateKey или publicKey.
   */
  async loadWalletFromData(privB64, pubB64, addr = '') {
    if (!privB64) return null;
    const privKey = await this.importPrivateKey(privB64);
    if (!privKey) return null;

    let pubKey = null;
    let address = addr || '';
    if (pubB64) {
      pubKey = await this.importPublicKey(pubB64);
      if (pubKey && !address) {
        address = await this.getAddressFromPublicKey(pubKey);
      }
    }
    if (!pubKey || !address) return null;

    return {
      privateKey: privKey,
      publicKey: pubKey,
      privateKeyBase64: privB64,
      publicKeyBase64: pubB64,
      address: address
    };
  },

  /**
   * Вычисляет уникальный Fox-адрес из объекта открытого ключа или Base64 строки.
   */
  async getAddressFromPublicKey(pubKeyInput) {
    if (!pubKeyInput) return null;
    let spkiBuffer;
    if (typeof pubKeyInput === 'string') {
      spkiBuffer = this.base64ToArrayBuffer(pubKeyInput);
    } else {
      spkiBuffer = await window.crypto.subtle.exportKey('spki', pubKeyInput);
    }
    if (!spkiBuffer) return null;

    const hashBuffer = await window.crypto.subtle.digest('SHA-256', spkiBuffer);
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
  },

  /**
   * Вычисляет 8-символьную hex контрольную сумму CRC32 от строки.
   */
  computeCrc32(str) {
    if (!str) return '00000000';
    let crc = 0 ^ (-1);
    for (let i = 0; i < str.length; i++) {
      crc = (crc >>> 8) ^ this.CRC32_TABLE[(crc ^ str.charCodeAt(i)) & 0xFF];
    }
    return ((crc ^ (-1)) >>> 0).toString(16).padStart(8, '0');
  },

  /**
   * Таблица констант IEEE 802.3 CRC32.
   */
  CRC32_TABLE: (() => {
    const table = [];
    for (let i = 0; i < 256; i++) {
      let c = i;
      for (let k = 0; k < 8; k++) {
        c = ((c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1));
      }
      table[i] = c;
    }
    return table;
  })(),

  /**
   * Проверяет формат Fox-адреса сети и его контрольную сумму.
   */
  isValidAddress(addr) {
    if (!addr || typeof addr !== 'string' || addr.length !== 64) return false;
    const lower = addr.toLowerCase();
    if (!this.ADDRESS_PATTERN.test(lower)) return false;
    if (lower.indexOf('f') < 0 || lower.indexOf('o') < 0 || lower.indexOf('x') < 0) return false;
    const payload = lower.substring(0, 56);
    const expected = this.computeCrc32(payload);
    return lower.substring(56).toLowerCase() === expected;
  },

  /**
   * Формирует каноническую строку для подписи транзакции с учетом chainId сети.
   */
  buildCanonicalString(from, to, amount, fee, nonce, comment, chainId) {
    const f = (from || '').toLowerCase();
    const t = (to || '').toLowerCase();
    const pChain = chainId ? `${chainId}|` : '';
    if (comment && comment.trim().length > 0) {
      return `${pChain}${f}|${t}|${amount}|${fee}|${nonce}|${comment.trim()}`;
    }
    return `${pChain}${f}|${t}|${amount}|${fee}|${nonce}`;
  },

  /**
   * Создает цифровую подпись канонической строки алгоритмом Ed25519.
   */
  async sign(privKey, canonicalString) {
    if (!privKey || !canonicalString) return null;
    try {
      const dataBytes = new TextEncoder().encode(canonicalString);
      const sigBuf = await window.crypto.subtle.sign(
        { name: 'Ed25519' },
        privKey,
        dataBytes
      );
      return this.arrayBufferToBase64(sigBuf);
    } catch (err) {
      console.error('Ошибка создания подписи:', err);
      return null;
    }
  }
};

