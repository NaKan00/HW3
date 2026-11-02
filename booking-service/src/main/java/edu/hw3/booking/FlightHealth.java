package edu.hw3.booking;

import org.springframework.boot.actuate.health.*;
import org.springframework.stereotype.Component;

@Component("flight")
public class FlightHealth implements HealthIndicator {
  private final FlightClient client;

  public FlightHealth(FlightClient client) {
    this.client = client;
  }

  public Health health() {
    try {
      client.get("11111111-1111-1111-1111-111111111111");
      return Health.up().build();
    } catch (Exception e) {
      return Health.down().withDetail("dependency", "flight gRPC").build();
    }
  }
}
