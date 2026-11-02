package edu.hw3.booking;

import jakarta.validation.constraints.*;
import java.util.UUID;

public record CreateBooking(
    @NotNull UUID userId,
    @NotNull UUID flightId,
    @NotBlank @Size(max = 200) String passengerName,
    @NotBlank @Email @Size(max = 254) String passengerEmail,
    @Min(1) @Max(100000) int seatCount) {}
