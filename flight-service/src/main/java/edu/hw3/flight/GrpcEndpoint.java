package edu.hw3.flight;

import edu.hw3.contract.*;
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import jakarta.annotation.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GrpcEndpoint extends FlightServiceGrpc.FlightServiceImplBase {
  private final FlightOperations ops;
  private final ApiKeyInterceptor auth;
  private final int port;
  private Server server;

  public GrpcEndpoint(
      FlightOperations ops, ApiKeyInterceptor auth, @Value("${grpc.port}") int port) {
    this.ops = ops;
    this.auth = auth;
    this.port = port;
  }

  @PostConstruct
  public void start() throws java.io.IOException {
    server =
        ServerBuilder.forPort(port)
            .addService(ServerInterceptors.intercept(this, auth))
            .build()
            .start();
  }

  int boundPort() {
    return server.getPort();
  }

  @PreDestroy
  public void stop() throws InterruptedException {
    if (server != null) server.shutdown().awaitTermination(5, TimeUnit.SECONDS);
  }

  private <T> void respond(StreamObserver<T> out, Supplier<T> action) {
    try {
      out.onNext(action.get());
      out.onCompleted();
    } catch (StatusRuntimeException e) {
      out.onError(e);
    } catch (org.springframework.dao.DataIntegrityViolationException e) {
      out.onError(
          Status.ALREADY_EXISTS
              .withDescription("Conflicting flight or reservation")
              .asRuntimeException());
    } catch (Exception e) {
      org.slf4j.LoggerFactory.getLogger(getClass()).error("GRPC_FAILURE", e);
      out.onError(Status.INTERNAL.withDescription("Flight operation failed").asRuntimeException());
    }
  }

  @Override
  public void getFlight(GetFlightRequest r, StreamObserver<Flight> o) {
    respond(o, () -> ops.get(r));
  }

  @Override
  public void searchFlights(SearchFlightsRequest r, StreamObserver<SearchFlightsResponse> o) {
    respond(o, () -> ops.search(r));
  }

  @Override
  public void reserveSeats(ReserveSeatsRequest r, StreamObserver<Reservation> o) {
    respond(o, () -> ops.reserve(r));
  }

  @Override
  public void releaseReservation(ReleaseReservationRequest r, StreamObserver<Reservation> o) {
    respond(o, () -> ops.release(r));
  }

  @Override
  public void updateFlight(UpdateFlightRequest r, StreamObserver<Flight> o) {
    respond(o, () -> ops.update(r));
  }
}
