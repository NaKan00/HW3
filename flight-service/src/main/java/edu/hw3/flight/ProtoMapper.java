package edu.hw3.flight;

import com.google.protobuf.Timestamp;
import edu.hw3.contract.*;
import java.time.Instant;

public final class ProtoMapper {
  private ProtoMapper() {}

  public static Timestamp timestamp(Instant t) {
    return Timestamp.newBuilder().setSeconds(t.getEpochSecond()).setNanos(t.getNano()).build();
  }

  public static Instant instant(Timestamp t) {
    if (t.getNanos() < 0 || t.getNanos() > 999999999)
      throw io.grpc.Status.INVALID_ARGUMENT
          .withDescription("Invalid timestamp")
          .asRuntimeException();
    return Instant.ofEpochSecond(t.getSeconds(), t.getNanos());
  }

  public static Flight flight(FlightEntity f) {
    return Flight.newBuilder()
        .setId(f.id.toString())
        .setFlightNumber(f.flightNumber)
        .setAirline(f.airline)
        .setOrigin(f.origin)
        .setDestination(f.destination)
        .setDepartureTime(timestamp(f.departureTime))
        .setArrivalTime(timestamp(f.arrivalTime))
        .setTotalSeats(f.totalSeats)
        .setAvailableSeats(f.availableSeats)
        .setPrice(
            Money.newBuilder()
                .setAmountMinor(f.price.movePointRight(2).longValueExact())
                .setCurrency("RUB"))
        .setStatus(FlightStatus.valueOf(f.status))
        .build();
  }

  public static Reservation reservation(ReservationEntity r) {
    return Reservation.newBuilder()
        .setId(r.id.toString())
        .setBookingId(r.bookingId.toString())
        .setFlightId(r.flightId.toString())
        .setSeatCount(r.seatCount)
        .setStatus(ReservationStatus.valueOf(r.status))
        .build();
  }
}
