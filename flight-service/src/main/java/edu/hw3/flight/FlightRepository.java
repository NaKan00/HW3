package edu.hw3.flight;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.*;

public interface FlightRepository extends JpaRepository<FlightEntity, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select f from FlightEntity f where f.id=:id")
  Optional<FlightEntity> lock(UUID id);

  @Query(
      "select f from FlightEntity f where f.status='SCHEDULED' and f.origin=:origin and"
          + " f.destination=:destination and f.departureTime>=:start and f.departureTime<:end order"
          + " by f.departureTime")
  List<FlightEntity> search(String origin, String destination, Instant start, Instant end);
}
