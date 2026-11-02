package edu.hw3.flight;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "seat_reservations")
public class ReservationEntity {
  @Id public UUID id;

  @Column(unique = true, nullable = false)
  public UUID bookingId;

  public UUID flightId;
  public int seatCount;
  public String status;
  public Instant createdAt, updatedAt;
}
