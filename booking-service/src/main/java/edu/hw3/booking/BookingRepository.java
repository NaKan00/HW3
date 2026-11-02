package edu.hw3.booking;

import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;

public interface BookingRepository extends JpaRepository<BookingEntity, UUID> {
  List<BookingEntity> findByUserIdOrderByCreatedAt(UUID userId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select b from BookingEntity b where b.id=:id")
  Optional<BookingEntity> lock(UUID id);
}
