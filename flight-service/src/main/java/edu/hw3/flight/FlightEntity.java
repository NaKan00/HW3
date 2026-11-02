package edu.hw3.flight;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "flights")
public class FlightEntity {
  @Id public UUID id;
  public String flightNumber, airline, origin, destination;
  public Instant departureTime, arrivalTime;
  public int totalSeats, availableSeats;

  @Column(precision = 14, scale = 2)
  public BigDecimal price;

  public String status;
  public Instant createdAt, updatedAt;
}
