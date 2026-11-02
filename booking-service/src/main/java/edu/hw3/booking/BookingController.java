package edu.hw3.booking;

import edu.hw3.contract.Flight;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
public class BookingController {
  private final BookingService bookings;
  private final FlightClient flights;

  public BookingController(BookingService b, FlightClient f) {
    bookings = b;
    flights = f;
  }

  public record FlightView(
      String id,
      String flightNumber,
      String airline,
      String origin,
      String destination,
      String departureTime,
      String arrivalTime,
      int totalSeats,
      int availableSeats,
      java.math.BigDecimal price,
      String currency,
      String status) {
    static FlightView of(Flight f) {
      return new FlightView(
          f.getId(),
          f.getFlightNumber(),
          f.getAirline(),
          f.getOrigin(),
          f.getDestination(),
          java.time.Instant.ofEpochSecond(
                  f.getDepartureTime().getSeconds(), f.getDepartureTime().getNanos())
              .toString(),
          java.time.Instant.ofEpochSecond(
                  f.getArrivalTime().getSeconds(), f.getArrivalTime().getNanos())
              .toString(),
          f.getTotalSeats(),
          f.getAvailableSeats(),
          java.math.BigDecimal.valueOf(f.getPrice().getAmountMinor(), 2),
          f.getPrice().getCurrency(),
          f.getStatus().name());
    }
  }

  @GetMapping("/flights")
  public List<FlightView> search(
      @RequestParam @Pattern(regexp = "[A-Z]{3}") String origin,
      @RequestParam @Pattern(regexp = "[A-Z]{3}") String destination,
      @RequestParam(required = false) String date) {
    return flights.search(origin, destination, date).getFlightsList().stream()
        .map(FlightView::of)
        .toList();
  }

  @GetMapping("/flights/{id}")
  public FlightView flight(@PathVariable UUID id) {
    return FlightView.of(flights.get(id.toString()));
  }

  @PostMapping("/bookings")
  @ResponseStatus(HttpStatus.CREATED)
  public BookingEntity create(@Valid @RequestBody CreateBooking r) {
    return bookings.create(r);
  }

  @GetMapping("/bookings/{id}")
  public BookingEntity get(@PathVariable UUID id) {
    return bookings.get(id);
  }

  @GetMapping("/bookings")
  public List<BookingEntity> list(@RequestParam("user_id") UUID user) {
    return bookings.list(user);
  }

  @PostMapping("/bookings/{id}/cancel")
  public BookingEntity cancel(@PathVariable UUID id) {
    return bookings.cancel(id);
  }
}
