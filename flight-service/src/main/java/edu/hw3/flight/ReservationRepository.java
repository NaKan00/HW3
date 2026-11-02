package edu.hw3.flight;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<ReservationEntity, UUID> {
  Optional<ReservationEntity> findByBookingId(UUID bookingId);
}
