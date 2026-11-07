/**
 * Криптографический модуль клиента NodeChain (Static-First).
 * Использует нативный Web Crypto API браузера (RSA-2048, SHA256withRSA, Fox-адреса).
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
   * Преобразует строку Base64 в ArrayBuffer.
   */
  base64ToArrayBuffer(base64) {
    if (!base64) return new ArrayBuffer(0);
    const cleaned = base64.replace(/\s+/g, '');
    const binary = window.atob(cleaned);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) {
      bytes[i] = binary.charCodeAt(i);
    }
    return bytes.buffer;
  },

  /**
   * Генерирует новую пару RSA ключей длиной 2048 бит.
   */
  async generateKeyPair() {
    try {
      const kp = await window.crypto.subtle.generateKey(
        {
          name: 'RSASSA-PKCS1-v1_5',
          modulusLength: 2048,
          publicExponent: new Uint8Array([1, 0, 1]),
          hash: 'SHA-256'
        },
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
      return await window.crypto.subtle.importKey(
        'spki',
        buf,
        { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
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
      return await window.crypto.subtle.importKey(
        'pkcs8',
        buf,
        { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
        true,
        ['sign']
      );
    } catch (err) {
      return null;
    }
  },

  /**
   * Восстанавливает открытый ключ из закрытого ключа (через генерацию пары или экспорт).
   * Примечание: в WebCrypto из PKCS#8 напрямую SPKI не всегда экспортируется,
   * поэтому кошелек может хранить обе части ключа либо вычислять из файла кошелька.
   */
  async loadWalletFromData(privB64, pubB64) {
    if (!privB64) return null;
    const privKey = await this.importPrivateKey(privB64);
    if (!privKey) return null;

    let pubKey = null;
    let address = null;
    if (pubB64) {
      pubKey = await this.importPublicKey(pubB64);
      if (pubKey) address = await this.getAddressFromPublicKey(pubKey);
    }

    return {
      privateKey: privKey,
      publicKey: pubKey,
      privateKeyBase64: privB64,
      publicKeyBase64: pubB64 || '',
      address: address || ''
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
    return hexArr.join('').toLowerCase();
  },

  /**
   * Проверяет формат Fox-адреса сети.
   */
  isValidAddress(addr) {
    if (!addr || typeof addr !== 'string' || addr.length !== 64) return false;
    const lower = addr.toLowerCase();
    return this.ADDRESS_PATTERN.test(lower)
      && lower.indexOf('f') >= 0
      && lower.indexOf('o') >= 0
      && lower.indexOf('x') >= 0;
  },

  /**
   * Формирует каноническую строку для подписи транзакции.
   */
  buildCanonicalString(from, to, amount, fee, nonce, comment) {
    const f = (from || '').toLowerCase();
    const t = (to || '').toLowerCase();
    if (comment && comment.trim().length > 0) {
      return `${f}|${t}|${amount}|${fee}|${nonce}|${comment.trim()}`;
    }
    return `${f}|${t}|${amount}|${fee}|${nonce}`;
  },

  /**
   * Создает цифровую подпись канонической строки алгоритмом SHA256withRSA.
   */
  async sign(privKey, canonicalString) {
    if (!privKey || !canonicalString) return null;
    try {
      const dataBytes = new TextEncoder().encode(canonicalString);
      const sigBuf = await window.crypto.subtle.sign(
        'RSASSA-PKCS1-v1_5',
        privKey,
        dataBytes
      );
      return this.arrayBufferToBase64(sigBuf);
    } catch (err) {
      console.error('Ошибка создания подписи:', err);
      return null;
    }
  },

  /**
   * Вычисляет SHA-256 хеш строки (UTF-8) в виде hex-строки.
   */
  async sha256Hex(text) {
    if (!text) return '';
    const bytes = new TextEncoder().encode(text);
    const hash = await window.crypto.subtle.digest('SHA-256', bytes);
    return Array.from(new Uint8Array(hash))
      .map(b => b.toString(16).padStart(2, '0'))
      .join('');
  }
};

