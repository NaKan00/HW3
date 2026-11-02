package edu.hw3.flight;

import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;

@Component
public class FlightCache {
  private static final Logger log = LoggerFactory.getLogger(FlightCache.class);
  private final StringRedisTemplate redis;
  private final Duration ttl;

  public FlightCache(StringRedisTemplate redis, @Value("${cache.ttl-seconds}") long seconds) {
    if (seconds <= 0) throw new IllegalArgumentException("TTL must be positive");
    this.redis = redis;
    ttl = Duration.ofSeconds(seconds);
  }

  public <T extends MessageLite> T read(String key, Parser<T> parser, Supplier<T> loader) {
    try {
      String v = redis.opsForValue().get(key);
      if (v != null) {
        T result = parser.parseFrom(Base64.getDecoder().decode(v));
        log.info("CACHE_HIT key={}", key);
        return result;
      }
    } catch (Exception e) {
      log.warn("CACHE_READ_FAILED type={}", e.getClass().getSimpleName());
    }
    log.info("CACHE_MISS key={}", key);
    T value = loader.get();
    try {
      redis.opsForValue().set(key, Base64.getEncoder().encodeToString(value.toByteArray()), ttl);
    } catch (Exception e) {
      log.warn("CACHE_WRITE_FAILED type={}", e.getClass().getSimpleName());
    }
    return value;
  }

  public void invalidate(FlightEntity f) {
    List<String> keys =
        List.of(
            "flight:" + f.id,
            "search:"
                + f.origin
                + ":"
                + f.destination
                + ":"
                + f.departureTime.atOffset(java.time.ZoneOffset.UTC).toLocalDate(),
            "search:" + f.origin + ":" + f.destination + ":ALL");
    Runnable action =
        () -> {
          try {
            redis.delete(keys);
            log.info("CACHE_INVALIDATE keys={}", keys);
          } catch (Exception e) {
            log.warn("CACHE_INVALIDATE_FAILED keys={}", keys);
          }
        };
    if (TransactionSynchronizationManager.isSynchronizationActive())
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            public void afterCommit() {
              action.run();
            }
          });
    else action.run();
  }
}
