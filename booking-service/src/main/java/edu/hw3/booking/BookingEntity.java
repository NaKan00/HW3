package edu.hw3.booking;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bookings")
public class BookingEntity {
  @Id public UUID id;
  public UUID userId, flightId;
  public String passengerName, passengerEmail;
  public int seatCount;

  @Column(precision = 14, scale = 2)
  public BigDecimal totalPrice;

  public String status;
  public Instant createdAt, updatedAt;
}
