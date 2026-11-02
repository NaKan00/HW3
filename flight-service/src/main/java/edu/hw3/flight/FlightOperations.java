package edu.hw3.flight;

import edu.hw3.contract.*;
import io.grpc.Status;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FlightOperations {
  private static final Logger log = LoggerFactory.getLogger(FlightOperations.class);
  private final FlightRepository flights;
  private final ReservationRepository reservations;
  private final FlightCache cache;
  private final EntityManager em;

  public FlightOperations(
      FlightRepository f, ReservationRepository r, FlightCache c, EntityManager em) {
    flights = f;
    reservations = r;
    cache = c;
    this.em = em;
  }

  static UUID uuid(String s) {
    try {
      UUID id = UUID.fromString(s);
      if (!id.toString().equalsIgnoreCase(s)) throw new IllegalArgumentException();
      return id;
    } catch (IllegalArgumentException e) {
      throw Status.INVALID_ARGUMENT.withDescription("Invalid UUID").asRuntimeException();
    }
  }

  @Transactional(readOnly = true)
  public Flight get(GetFlightRequest r) {
    UUID id = uuid(r.getId());
    return cache.read(
        "flight:" + id,
        Flight.parser(),
        () ->
            ProtoMapper.flight(
                flights
                    .findById(id)
                    .orElseThrow(
                        () ->
                            Status.NOT_FOUND
                                .withDescription("Flight not found")
                                .asRuntimeException())));
  }

  @Transactional(readOnly = true)
  public SearchFlightsResponse search(SearchFlightsRequest r) {
    if (!r.getOrigin().matches("[A-Z]{3}")
        || !r.getDestination().matches("[A-Z]{3}")
        || r.getOrigin().equals(r.getDestination()))
      throw Status.INVALID_ARGUMENT.withDescription("Invalid route").asRuntimeException();
    LocalDate d;
    try {
      d = r.getDate().isEmpty() ? null : LocalDate.parse(r.getDate());
    } catch (Exception e) {
      throw Status.INVALID_ARGUMENT.withDescription("Invalid date").asRuntimeException();
    }
    Instant
        start =
            d == null
                ? Instant.parse("1900-01-01T00:00:00Z")
                : d.atStartOfDay().toInstant(ZoneOffset.UTC),
        end =
            d == null
                ? Instant.parse("9999-01-01T00:00:00Z")
                : d.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    return cache.read(
        "search:" + r.getOrigin() + ":" + r.getDestination() + ":" + (d == null ? "ALL" : d),
        SearchFlightsResponse.parser(),
        () ->
            SearchFlightsResponse.newBuilder()
                .addAllFlights(
                    flights.search(r.getOrigin(), r.getDestination(), start, end).stream()
                        .map(ProtoMapper::flight)
                        .toList())
                .build());
  }

  private void bookingLock(UUID id) {
    em.createNativeQuery("select pg_advisory_xact_lock(:key)")
        .setParameter("key", id.getMostSignificantBits() ^ id.getLeastSignificantBits())
        .getSingleResult();
  }

  private FlightEntity locked(UUID id) {
    return flights
        .lock(id)
        .orElseThrow(
            () -> Status.NOT_FOUND.withDescription("Flight not found").asRuntimeException());
  }

  @Transactional
  public Reservation reserve(ReserveSeatsRequest req) {
    UUID booking = uuid(req.getBookingId()), flight = uuid(req.getFlightId());
    if (req.getSeatCount() <= 0)
      throw Status.INVALID_ARGUMENT
          .withDescription("Seat count must be positive")
          .asRuntimeException();
    bookingLock(booking);
    var old = reservations.findByBookingId(booking);
    if (old.isPresent()) {
      var r = old.get();
      if (!r.flightId.equals(flight) || r.seatCount != req.getSeatCount())
        throw Status.ALREADY_EXISTS
            .withDescription("Idempotency key parameters differ")
            .asRuntimeException();
      log.info("RESERVATION_REPLAY booking={}", booking);
      return ProtoMapper.reservation(r);
    }
    FlightEntity f = locked(flight);
    if (!f.status.equals("SCHEDULED"))
      throw Status.FAILED_PRECONDITION
          .withDescription("Flight is not scheduled")
          .asRuntimeException();
    if (f.availableSeats < req.getSeatCount())
      throw Status.RESOURCE_EXHAUSTED.withDescription("Insufficient seats").asRuntimeException();
    f.availableSeats -= req.getSeatCount();
    f.updatedAt = Instant.now();
    ReservationEntity r = new ReservationEntity();
    r.id = UUID.randomUUID();
    r.bookingId = booking;
    r.flightId = flight;
    r.seatCount = req.getSeatCount();
    r.status = "ACTIVE";
    r.createdAt = r.updatedAt = Instant.now();
    reservations.saveAndFlush(r);
    cache.invalidate(f);
    log.info("RESERVATION_CREATED booking={} seats={}", booking, r.seatCount);
    return ProtoMapper.reservation(r);
  }

  @Transactional
  public Reservation release(ReleaseReservationRequest req) {
    UUID booking = uuid(req.getBookingId());
    bookingLock(booking);
    ReservationEntity r =
        reservations
            .findByBookingId(booking)
            .orElseThrow(
                () ->
                    Status.NOT_FOUND.withDescription("Reservation not found").asRuntimeException());
    FlightEntity f = locked(r.flightId);
    if (r.status.equals("ACTIVE")) {
      f.availableSeats += r.seatCount;
      f.updatedAt = Instant.now();
      r.status = "RELEASED";
      r.updatedAt = Instant.now();
      cache.invalidate(f);
      log.info("RESERVATION_RELEASED booking={}", booking);
    }
    return ProtoMapper.reservation(r);
  }

  @Transactional
  public Flight update(UpdateFlightRequest req) {
    FlightEntity f = locked(uuid(req.getId()));
    cache.invalidate(f);
    Instant departure =
        req.hasDepartureTime() ? ProtoMapper.instant(req.getDepartureTime()) : f.departureTime;
    Instant arrival =
        req.hasArrivalTime() ? ProtoMapper.instant(req.getArrivalTime()) : f.arrivalTime;
    if (!arrival.isAfter(departure))
      throw Status.INVALID_ARGUMENT
          .withDescription("Arrival must follow departure")
          .asRuntimeException();
    if (req.hasPrice()) {
      if (req.getPrice().getAmountMinor() <= 0
          || req.getPrice().getAmountMinor() > 99999999999999L
          || !req.getPrice().getCurrency().equals("RUB"))
        throw Status.INVALID_ARGUMENT
            .withDescription("Positive RUB price required")
            .asRuntimeException();
      f.price = BigDecimal.valueOf(req.getPrice().getAmountMinor(), 2);
    }
    if (req.getStatusValue() != 0) {
      if (req.getStatus() == FlightStatus.UNRECOGNIZED)
        throw Status.INVALID_ARGUMENT.withDescription("Invalid flight status").asRuntimeException();
      f.status = req.getStatus().name();
    }
    f.departureTime = departure;
    f.arrivalTime = arrival;
    f.updatedAt = Instant.now();
    flights.flush();
    cache.invalidate(f);
    return ProtoMapper.flight(f);
  }
}
