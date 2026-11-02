package edu.hw3.flight;

import static org.junit.jupiter.api.Assertions.*;

import edu.hw3.contract.*;
import io.grpc.*;
import io.grpc.inprocess.*;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.*;

class AuthenticationTest {
  Server server;
  ManagedChannel channel;

  @BeforeEach
  void start() throws Exception {
    String name = InProcessServerBuilder.generateName();
    server =
        InProcessServerBuilder.forName(name)
            .directExecutor()
            .addService(
                ServerInterceptors.intercept(
                    new FlightServiceGrpc.FlightServiceImplBase() {
                      public void getFlight(GetFlightRequest r, StreamObserver<Flight> o) {
                        o.onNext(Flight.newBuilder().setId("executed").build());
                        o.onCompleted();
                      }
                    },
                    new ApiKeyInterceptor("test-key")))
            .build()
            .start();
    channel = InProcessChannelBuilder.forName(name).directExecutor().build();
  }

  @AfterEach
  void close() {
    channel.shutdownNow();
    server.shutdownNow();
  }

  FlightServiceGrpc.FlightServiceBlockingStub stub(String key) {
    Metadata h = new Metadata();
    if (key != null) h.put(ApiKeyInterceptor.KEY, key);
    return FlightServiceGrpc.newBlockingStub(channel)
        .withInterceptors(io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(h));
  }

  @Test
  void missing() {
    assertEquals(
        Status.Code.UNAUTHENTICATED,
        assertThrows(
                StatusRuntimeException.class,
                () -> stub(null).getFlight(GetFlightRequest.getDefaultInstance()))
            .getStatus()
            .getCode());
  }

  @Test
  void incorrect() {
    assertEquals(
        Status.Code.UNAUTHENTICATED,
        assertThrows(
                StatusRuntimeException.class,
                () -> stub("wrong").getFlight(GetFlightRequest.getDefaultInstance()))
            .getStatus()
            .getCode());
  }

  @Test
  void correct() {
    assertEquals(
        "executed", stub("test-key").getFlight(GetFlightRequest.getDefaultInstance()).getId());
  }
}
