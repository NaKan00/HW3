package edu.hw3.booking;

import io.grpc.*;
import java.util.function.LongSupplier;
import org.slf4j.*;

public class CircuitBreakerInterceptor implements ClientInterceptor {
  public enum State {
    CLOSED,
    OPEN,
    HALF_OPEN
  }

  private static final Logger log = LoggerFactory.getLogger(CircuitBreakerInterceptor.class);
  private State state = State.CLOSED;
  private int failures;
  private long openedAt;
  private long generation;
  private final int threshold;
  private final long openMillis;
  private final LongSupplier clock;

  public CircuitBreakerInterceptor(int threshold, long millis) {
    this(threshold, millis, System::currentTimeMillis);
  }

  CircuitBreakerInterceptor(int threshold, long millis, LongSupplier clock) {
    if (threshold < 1 || millis < 1) throw new IllegalArgumentException();
    this.threshold = threshold;
    this.openMillis = millis;
    this.clock = clock;
  }

  public synchronized State state() {
    return state;
  }

  private void transition(State next) {
    log.info("CIRCUIT_TRANSITION {} -> {}", state, next);
    state = next;
    generation++;
  }

  private synchronized long permit() {
    if (state == State.OPEN && clock.getAsLong() - openedAt >= openMillis) {
      transition(State.HALF_OPEN);
      return generation;
    }
    return state == State.CLOSED ? generation : -1;
  }

  private synchronized void record(Status s, long ticket) {
    if (ticket != generation) return;
    if (state == State.HALF_OPEN) {
      if (s.isOk()) {
        failures = 0;
        transition(State.CLOSED);
      } else {
        openedAt = clock.getAsLong();
        transition(State.OPEN);
      }
      return;
    }
    if (state != State.CLOSED) return;
    if (retryable(s)) {
      if (++failures >= threshold) {
        openedAt = clock.getAsLong();
        transition(State.OPEN);
      }
    } else {
      failures = 0;
    }
  }

  public static boolean retryable(Status s) {
    return s.getCode() == Status.Code.UNAVAILABLE || s.getCode() == Status.Code.DEADLINE_EXCEEDED;
  }

  @Override
  public <Q, S> ClientCall<Q, S> interceptCall(
      MethodDescriptor<Q, S> method, CallOptions options, Channel next) {
    return new ClientCall<>() {
      private ClientCall<Q, S> delegate;

      @Override
      public void start(Listener<S> listener, Metadata headers) {
        long ticket = permit();
        if (ticket < 0) {
          listener.onClose(Status.UNAVAILABLE.withDescription("CIRCUIT_OPEN"), new Metadata());
          return;
        }
        delegate = next.newCall(method, options);
        delegate.start(
            new ForwardingClientCallListener.SimpleForwardingClientCallListener<>(listener) {
              @Override
              public void onClose(Status status, Metadata trailers) {
                record(status, ticket);
                super.onClose(status, trailers);
              }
            },
            headers);
      }

      @Override
      public void request(int n) {
        if (delegate != null) delegate.request(n);
      }

      @Override
      public void cancel(String message, Throwable cause) {
        if (delegate != null) delegate.cancel(message, cause);
      }

      @Override
      public void halfClose() {
        if (delegate != null) delegate.halfClose();
      }

      @Override
      public void sendMessage(Q message) {
        if (delegate != null) delegate.sendMessage(message);
      }
    };
  }
}
