/**
 * Утилита нормализации и валидации URL адресов узла сети (Static-First).
 */
class UrlUtil {

  /**
   * Нормализует HTTP/HTTPS URL адрес узла, возвращая null при некорректном формате.
   */
  static normalizeUrl(url) {
    if (!url || typeof url !== 'string') return null;
    const trimmed = url.trim();
    if (!trimmed) return null;
    const withProto = /^https?:\/\//i.test(trimmed) ? trimmed : 'http://' + trimmed;
    let parsed;
    try {
      parsed = new URL(withProto);
    } catch (e) {
      return null;
    }
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return null;
    if (!parsed.hostname) return null;
    const cleanPath = parsed.pathname.replace(/\/+$/, '');
    return `${parsed.origin}${cleanPath}`;
  }
}

