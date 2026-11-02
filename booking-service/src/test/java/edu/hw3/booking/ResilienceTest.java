package edu.hw3.booking;

import static org.junit.jupiter.api.Assertions.*;

import edu.hw3.contract.*;
import io.grpc.*;
import io.grpc.inprocess.*;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;

class ResilienceTest {
  @Test
  void unavailable() {
    retry(Status.UNAVAILABLE, 3);
  }

  @Test
  void deadline() {
    retry(Status.DEADLINE_EXCEEDED, 3);
  }

  @Test
  void exhausted() {
    retry(Status.RESOURCE_EXHAUSTED, 1);
  }

  @Test
  void notFound() {
    retry(Status.NOT_FOUND, 1);
  }

  @Test
  void invalid() {
    retry(Status.INVALID_ARGUMENT, 1);
  }

  @Test
  void unauthenticated() {
    retry(Status.UNAUTHENTICATED, 1);
  }

  void retry(Status s, int expected) {
    AtomicInteger n = new AtomicInteger();
    assertThrows(
        StatusRuntimeException.class,
        () ->
            new RetryPolicy(0)
                .execute(
                    "test",
                    () -> {
                      n.incrementAndGet();
                      throw s.asRuntimeException();
                    }));
    assertEquals(expected, n.get());
  }

  @Test
  void retryEventuallySucceeds() {
    AtomicInteger n = new AtomicInteger();
    assertEquals(
        "ok",
        new RetryPolicy(0)
            .execute(
                "test",
                () -> {
                  if (n.incrementAndGet() < 3) throw Status.UNAVAILABLE.asRuntimeException();
                  return "ok";
                }));
    assertEquals(3, n.get());
  }

  @Test
  void circuitTransitionsAndSingleProbe() throws Exception {
    AtomicLong time = new AtomicLong();
    AtomicInteger calls = new AtomicInteger();
    AtomicReference<Status> result = new AtomicReference<>(Status.UNAVAILABLE);
    AtomicReference<StreamObserver<Flight>> pending = new AtomicReference<>();
    AtomicBoolean hold = new AtomicBoolean();
    String name = InProcessServerBuilder.generateName();
    Server server =
        InProcessServerBuilder.forName(name)
            .directExecutor()
            .addService(
                new FlightServiceGrpc.FlightServiceImplBase() {
                  public void getFlight(GetFlightRequest r, StreamObserver<Flight> o) {
                    calls.incrementAndGet();
                    if (hold.get()) {
                      pending.set(o);
                      return;
                    }
                    if (result.get().isOk()) {
                      o.onNext(Flight.getDefaultInstance());
                      o.onCompleted();
                    } else o.onError(result.get().asRuntimeException());
                  }
                })
            .build()
            .start();
    ManagedChannel channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    try {
      var cb = new CircuitBreakerInterceptor(2, 100, time::get);
      Channel intercepted = ClientInterceptors.intercept(channel, cb);
      var stub = FlightServiceGrpc.newBlockingStub(intercepted);
      Runnable call = () -> stub.getFlight(GetFlightRequest.getDefaultInstance());
      assertThrows(StatusRuntimeException.class, call::run);
      assertEquals(CircuitBreakerInterceptor.State.CLOSED, cb.state());
      assertThrows(StatusRuntimeException.class, call::run);
      assertEquals(CircuitBreakerInterceptor.State.OPEN, cb.state());
      int before = calls.get();
      var open =
          assertThrows(
              StatusRuntimeException.class,
              () ->
                  new RetryPolicy(0)
                      .execute(
                          "test", () -> stub.getFlight(GetFlightRequest.getDefaultInstance())));
      assertEquals("CIRCUIT_OPEN", open.getStatus().getDescription());
      assertEquals(before, calls.get());
      time.addAndGet(101);
      hold.set(true);
      FlightServiceGrpc.newStub(intercepted)
          .getFlight(
              GetFlightRequest.getDefaultInstance(),
              new StreamObserver<>() {
                public void onNext(Flight f) {}

                public void onError(Throwable e) {}

                public void onCompleted() {}
              });
      assertEquals(CircuitBreakerInterceptor.State.HALF_OPEN, cb.state());
      assertThrows(StatusRuntimeException.class, call::run);
      assertEquals(before + 1, calls.get());
      pending.get().onError(Status.UNAVAILABLE.asRuntimeException());
      assertEquals(CircuitBreakerInterceptor.State.OPEN, cb.state());
      time.addAndGet(101);
      hold.set(false);
      result.set(Status.OK);
      call.run();
      assertEquals(CircuitBreakerInterceptor.State.CLOSED, cb.state());
    } finally {
      channel.shutdownNow();
      server.shutdownNow();
    }
  }
}
