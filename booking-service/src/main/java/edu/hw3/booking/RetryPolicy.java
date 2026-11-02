package edu.hw3.booking;

import io.grpc.*;
import java.util.function.Supplier;
import org.slf4j.*;

public class RetryPolicy {
  private static final Logger log = LoggerFactory.getLogger(RetryPolicy.class);
  private final long backoff;

  public RetryPolicy(long backoff) {
    if (backoff < 0) throw new IllegalArgumentException();
    this.backoff = backoff;
  }

  public <T> T execute(String operation, Supplier<T> call) {
    for (int attempt = 1; ; attempt++) {
      try {
        log.info("GRPC_CALL operation={} attempt={}", operation, attempt);
        return call.get();
      } catch (StatusRuntimeException e) {
        boolean retry =
            CircuitBreakerInterceptor.retryable(e.getStatus())
                && !"CIRCUIT_OPEN".equals(e.getStatus().getDescription());
        log.info(
            "GRPC_ATTEMPT operation={} attempt={} status={} retryable={}",
            operation,
            attempt,
            e.getStatus().getCode(),
            retry);
        if (!retry || attempt == 3) throw e;
        try {
          Thread.sleep(backoff * (1L << (attempt - 1)));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw Status.CANCELLED.withDescription("Interrupted retry").asRuntimeException();
        }
      }
    }
  }
}
