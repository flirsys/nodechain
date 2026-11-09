package so.fox.nodechain.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

// Фильтр ограничения частоты запросов к API по алгоритму Token Bucket.
@Component
@Order(1)
public class ApiRateLimitFilter extends OncePerRequestFilter {

  private static final int MAX_TOKENS = 500;
  private static final long REFILL_MS = 10L;
  private final Map<String, long[]> buckets = new ConcurrentHashMap<>();

  // Фильтрация запроса: заголовки безопасности и квота токенов на IP
  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain) throws IOException, ServletException {
    resp.setHeader("X-Content-Type-Options", "nosniff");
    resp.setHeader("Referrer-Policy", "no-referrer");
    resp.setHeader("Cache-Control", "no-store");

    String uri = req.getRequestURI();
    if (uri != null && uri.startsWith("/api/")) {
      if (!isAllowed(resolveIp(req))) {
        resp.setStatus(429);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write("{\"code\":429,\"message\":\"Слишком много запросов\"}");
        return;
      }
    }
    chain.doFilter(req, resp);
  }

  private boolean isAllowed(String ip) {
    // Потолок на число бакетов: дальше считаем всех одним ведром
    if (buckets.size() > 10_000) buckets.clear();
    long now = System.currentTimeMillis();
    long[] bucket = buckets.computeIfAbsent(ip, k -> new long[]{MAX_TOKENS, now});
    synchronized (bucket) {
      long refill = (now - bucket[1]) / REFILL_MS;
      if (refill > 0) {
        bucket[0] = Math.min(MAX_TOKENS, bucket[0] + refill);
        bucket[1] = bucket[1] + refill * REFILL_MS;
      }
      if (bucket[0] <= 0) return false;
      bucket[0]--;
      return true;
    }
  }

  // Ключ лимита — реальный адрес сокета, X-Forwarded-For не читаем
  private String resolveIp(HttpServletRequest req) {
    String remote = req.getRemoteAddr();
    return remote != null ? remote : "127.0.0.1";
  }
}

