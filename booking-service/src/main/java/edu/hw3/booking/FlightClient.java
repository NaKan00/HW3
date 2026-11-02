package edu.hw3.booking;

import edu.hw3.contract.*;
import io.grpc.*;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FlightClient {
  private final ManagedChannel channel;
  private final FlightServiceGrpc.FlightServiceBlockingStub stub;
  private final long deadline;
  private final RetryPolicy retry;

  public FlightClient(
      @Value("${grpc.host}") String host,
      @Value("${grpc.port}") int port,
      @Value("${grpc.api-key}") String key,
      @Value("${grpc.deadline-ms}") long deadline,
      @Value("${grpc.backoff-ms}") long backoff,
      @Value("${grpc.cb-failure-threshold}") int threshold,
      @Value("${grpc.cb-open-millis}") long openMillis) {
    this.deadline = deadline;
    retry = new RetryPolicy(backoff);
    channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().disableRetry().build();
    stub =
        FlightServiceGrpc.newBlockingStub(
            ClientInterceptors.intercept(
                channel,
                new AuthClientInterceptor(key),
                new CircuitBreakerInterceptor(threshold, openMillis)));
  }

  private FlightServiceGrpc.FlightServiceBlockingStub call() {
    return stub.withDeadlineAfter(deadline, TimeUnit.MILLISECONDS);
  }

  public Flight get(String id) {
    return retry.execute(
        "GetFlight", () -> call().getFlight(GetFlightRequest.newBuilder().setId(id).build()));
  }

  public SearchFlightsResponse search(String origin, String destination, String date) {
    return retry.execute(
        "SearchFlights",
        () ->
            call()
                .searchFlights(
                    SearchFlightsRequest.newBuilder()
                        .setOrigin(origin)
                        .setDestination(destination)
                        .setDate(date == null ? "" : date)
                        .build()));
  }

  public Reservation reserve(String booking, String flight, int seats) {
    return retry.execute(
        "ReserveSeats",
        () ->
            call()
                .reserveSeats(
                    ReserveSeatsRequest.newBuilder()
                        .setBookingId(booking)
                        .setFlightId(flight)
                        .setSeatCount(seats)
                        .build()));
  }

  public Reservation release(String booking) {
    return retry.execute(
        "ReleaseReservation",
        () ->
            call()
                .releaseReservation(
                    ReleaseReservationRequest.newBuilder().setBookingId(booking).build()));
  }

  @PreDestroy
  public void close() {
    channel.shutdownNow();
  }
}
