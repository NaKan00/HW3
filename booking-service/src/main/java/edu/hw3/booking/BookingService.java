package edu.hw3.booking;

import edu.hw3.contract.Flight;
import edu.hw3.contract.ReservationStatus;
import io.grpc.Status;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class BookingService {
  private static final Logger log = LoggerFactory.getLogger(BookingService.class);
  private final BookingRepository bookings;
  private final FlightClient flights;
  private final TransactionTemplate tx;

  public BookingService(
      BookingRepository bookings, FlightClient flights, PlatformTransactionManager tm) {
    this.bookings = bookings;
    this.flights = flights;
    tx = new TransactionTemplate(tm);
  }

  public BookingEntity create(CreateBooking req) {
    Flight flight = flights.get(req.flightId().toString());
    UUID id = UUID.randomUUID();
    var reservation = flights.reserve(id.toString(), req.flightId().toString(), req.seatCount());
    if (reservation.getStatus() != ReservationStatus.ACTIVE)
      throw Status.FAILED_PRECONDITION
          .withDescription("Reservation is not active")
          .asRuntimeException();
    try {
      return tx.execute(
          status -> {
            BookingEntity b = new BookingEntity();
            b.id = id;
            b.userId = req.userId();
            b.flightId = req.flightId();
            b.passengerName = req.passengerName();
            b.passengerEmail = req.passengerEmail();
            b.seatCount = req.seatCount();
            b.totalPrice =
                BigDecimal.valueOf(flight.getPrice().getAmountMinor(), 2)
                    .multiply(BigDecimal.valueOf(req.seatCount()));
            b.status = "CONFIRMED";
            b.createdAt = b.updatedAt = Instant.now();
            return bookings.saveAndFlush(b);
          });
    } catch (RuntimeException failure) {
      log.warn("COMPENSATION_START booking={}", id);
      try {
        flights.release(id.toString());
        log.info("COMPENSATION_SUCCESS booking={}", id);
      } catch (RuntimeException compensation) {
        failure.addSuppressed(compensation);
        log.error("COMPENSATION_FAILED booking={} requires reconciliation", id);
      }
      throw failure;
    }
  }

  public BookingEntity get(UUID id) {
    return bookings
        .findById(id)
        .orElseThrow(
            () -> Status.NOT_FOUND.withDescription("Booking not found").asRuntimeException());
  }

  public List<BookingEntity> list(UUID user) {
    return bookings.findByUserIdOrderByCreatedAt(user);
  }

  public BookingEntity cancel(UUID id) {
    return tx.execute(
        status -> {
          BookingEntity b =
              bookings
                  .lock(id)
                  .orElseThrow(
                      () ->
                          Status.NOT_FOUND
                              .withDescription("Booking not found")
                              .asRuntimeException());
          if (!b.status.equals("CONFIRMED"))
            throw Status.FAILED_PRECONDITION
                .withDescription("Booking is not confirmed")
                .asRuntimeException();
          flights.release(id.toString());
          b.status = "CANCELLED";
          b.updatedAt = Instant.now();
          return bookings.saveAndFlush(b);
        });
  }
}
