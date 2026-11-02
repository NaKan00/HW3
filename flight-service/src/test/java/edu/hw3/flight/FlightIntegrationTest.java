package edu.hw3.flight;

import static org.junit.jupiter.api.Assertions.*;

import edu.hw3.contract.*;
import io.grpc.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(
    properties = {"grpc.port=0", "grpc.api-key=test-key", "server.port=0", "cache.ttl-seconds=60"})
class FlightIntegrationTest {
  @Container static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16.6-alpine");

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7.4.1-alpine").withExposedPorts(6379);

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", pg::getJdbcUrl);
    r.add("spring.datasource.username", pg::getUsername);
    r.add("spring.datasource.password", pg::getPassword);
  }

  @org.springframework.boot.test.context.TestConfiguration
  static class RedisTestConfig {
    @org.springframework.context.annotation.Bean
    @org.springframework.context.annotation.Primary
    org.springframework.data.redis.connection.RedisConnectionFactory testRedis() {
      return new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(
          redis.getHost(), redis.getMappedPort(6379));
    }
  }

  @Autowired GrpcEndpoint endpoint;
  @Autowired FlightOperations ops;
  @Autowired FlightRepository flights;
  @Autowired ReservationRepository reservations;
  @Autowired StringRedisTemplate cache;
  static final String ID = "11111111-1111-1111-1111-111111111111";

  @BeforeEach
  void reset() {
    reservations.deleteAll();
    var f = flights.findById(UUID.fromString(ID)).orElseThrow();
    f.availableSeats = f.totalSeats;
    f.status = "SCHEDULED";
    flights.saveAndFlush(f);
    cache.getConnectionFactory().getConnection().serverCommands().flushDb();
  }

  ReserveSeatsRequest req(String b, int n) {
    return ReserveSeatsRequest.newBuilder().setBookingId(b).setFlightId(ID).setSeatCount(n).build();
  }

  GetFlightRequest get() {
    return GetFlightRequest.newBuilder().setId(ID).build();
  }

  void code(Status.Code c, Runnable action) {
    assertEquals(c, assertThrows(StatusRuntimeException.class, action::run).getStatus().getCode());
  }

  @Test
  void realGrpcBusinessFlow() {
    ManagedChannel channel =
        ManagedChannelBuilder.forAddress("localhost", endpoint.boundPort()).usePlaintext().build();
    try {
      Metadata h = new Metadata();
      h.put(ApiKeyInterceptor.KEY, "test-key");
      var stub =
          FlightServiceGrpc.newBlockingStub(channel)
              .withInterceptors(io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(h))
              .withDeadlineAfter(10, TimeUnit.SECONDS);
      assertEquals(100, stub.getFlight(get()).getAvailableSeats());
      String b = UUID.randomUUID().toString();
      assertEquals(ReservationStatus.ACTIVE, stub.reserveSeats(req(b, 1)).getStatus());
      assertEquals(99, stub.getFlight(get()).getAvailableSeats());
      assertEquals(
          ReservationStatus.RELEASED,
          stub.releaseReservation(ReleaseReservationRequest.newBuilder().setBookingId(b).build())
              .getStatus());
    } finally {
      channel.shutdownNow();
    }
  }

  @Test
  void searchAndGet() {
    assertEquals(ID, ops.get(get()).getId());
    assertEquals(
        2,
        ops.search(
                SearchFlightsRequest.newBuilder()
                    .setOrigin("SVO")
                    .setDestination("LED")
                    .setDate("2030-06-15")
                    .build())
            .getFlightsCount());
  }

  @Test
  void searchExcludesCancelledFlights() {
    var cancelled = flights.findById(UUID.fromString(ID)).orElseThrow();
    cancelled.status = "CANCELLED";
    flights.saveAndFlush(cancelled);
    try {
      var result =
          ops.search(
              SearchFlightsRequest.newBuilder()
                  .setOrigin("SVO")
                  .setDestination("LED")
                  .setDate("2030-06-15")
                  .build());
      assertFalse(result.getFlightsList().stream().anyMatch(f -> f.getId().equals(ID)));
      assertEquals(1, result.getFlightsCount());
      assertEquals("22222222-2222-2222-2222-222222222222", result.getFlights(0).getId());
      assertEquals(FlightStatus.SCHEDULED, result.getFlights(0).getStatus());
      assertEquals(FlightStatus.CANCELLED, ops.get(get()).getStatus());
    } finally {
      cancelled.status = "SCHEDULED";
      flights.saveAndFlush(cancelled);
    }
  }

  @Test
  void cacheHitMissTtl() {
    ops.get(get());
    assertTrue(cache.getExpire("flight:" + ID) > 0);
    var f = flights.findById(UUID.fromString(ID)).orElseThrow();
    f.availableSeats = 3;
    flights.saveAndFlush(f);
    assertEquals(100, ops.get(get()).getAvailableSeats());
    cache.delete("flight:" + ID);
    assertEquals(3, ops.get(get()).getAvailableSeats());
  }

  @Test
  void reserveReleaseInvalidation() {
    String b = UUID.randomUUID().toString();
    ops.get(get());
    var search =
        SearchFlightsRequest.newBuilder()
            .setOrigin("SVO")
            .setDestination("LED")
            .setDate("2030-06-15")
            .build();
    ops.search(search);
    ops.search(search.toBuilder().clearDate().build());
    ops.reserve(req(b, 2));
    assertFalse(cache.hasKey("flight:" + ID));
    assertFalse(cache.hasKey("search:SVO:LED:2030-06-15"));
    assertFalse(cache.hasKey("search:SVO:LED:ALL"));
    assertEquals(98, ops.get(get()).getAvailableSeats());
    ops.release(ReleaseReservationRequest.newBuilder().setBookingId(b).build());
    assertEquals(100, ops.get(get()).getAvailableSeats());
    ops.release(ReleaseReservationRequest.newBuilder().setBookingId(b).build());
    assertEquals(100, flights.findById(UUID.fromString(ID)).orElseThrow().availableSeats);
  }

  @Test
  void duplicateAndMismatch() {
    String b = UUID.randomUUID().toString();
    var first = ops.reserve(req(b, 2));
    assertEquals(first, ops.reserve(req(b, 2)));
    assertEquals(98, flights.findById(UUID.fromString(ID)).orElseThrow().availableSeats);
    code(Status.Code.ALREADY_EXISTS, () -> ops.reserve(req(b, 1)));
  }

  @Test
  void insufficient() {
    code(Status.Code.RESOURCE_EXHAUSTED, () -> ops.reserve(req(UUID.randomUUID().toString(), 101)));
    assertEquals(100, ops.get(get()).getAvailableSeats());
    assertEquals(0, reservations.count());
  }

  @Test
  void unknown() {
    code(
        Status.Code.NOT_FOUND,
        () -> ops.get(GetFlightRequest.newBuilder().setId(UUID.randomUUID().toString()).build()));
  }

  @Test
  void wrongStatus() {
    var f = flights.findById(UUID.fromString(ID)).orElseThrow();
    f.status = "CANCELLED";
    flights.saveAndFlush(f);
    code(Status.Code.FAILED_PRECONDITION, () -> ops.reserve(req(UUID.randomUUID().toString(), 1)));
  }

  @Test
  void invalid() {
    code(Status.Code.INVALID_ARGUMENT, () -> ops.reserve(req(UUID.randomUUID().toString(), 0)));
    code(
        Status.Code.INVALID_ARGUMENT,
        () -> ops.get(GetFlightRequest.newBuilder().setId("bad").build()));
  }

  @Test
  void concurrentDuplicate() throws Exception {
    String b = UUID.randomUUID().toString();
    var results = race(() -> ops.reserve(req(b, 1)), () -> ops.reserve(req(b, 1)));
    assertEquals(results.get(0), results.get(1));
    assertEquals(99, flights.findById(UUID.fromString(ID)).orElseThrow().availableSeats);
    assertEquals(1, reservations.count());
  }

  @Test
  void lastSeat() throws Exception {
    var f = flights.findById(UUID.fromString(ID)).orElseThrow();
    f.availableSeats = 1;
    flights.saveAndFlush(f);
    Callable<String> buy =
        () -> {
          try {
            ops.reserve(req(UUID.randomUUID().toString(), 1));
            return "OK";
          } catch (StatusRuntimeException e) {
            return e.getStatus().getCode().name();
          }
        };
    var result = race(buy, buy);
    assertEquals(1, result.stream().filter("OK"::equals).count());
    assertEquals(1, result.stream().filter("RESOURCE_EXHAUSTED"::equals).count());
    assertEquals(0, flights.findById(UUID.fromString(ID)).orElseThrow().availableSeats);
  }

  <T> List<T> race(Callable<T> a, Callable<T> b) throws Exception {
    try (var pool = Executors.newFixedThreadPool(2)) {
      var gate = new CountDownLatch(1);
      var x =
          pool.submit(
              () -> {
                gate.await();
                return a.call();
              });
      var y =
          pool.submit(
              () -> {
                gate.await();
                return b.call();
              });
      gate.countDown();
      return List.of(x.get(15, TimeUnit.SECONDS), y.get(15, TimeUnit.SECONDS));
    }
  }

  @Test
  void updateInvalidates() {
    ops.get(get());
    ops.search(SearchFlightsRequest.newBuilder().setOrigin("SVO").setDestination("LED").build());
    ops.update(
        UpdateFlightRequest.newBuilder()
            .setId(ID)
            .setPrice(Money.newBuilder().setAmountMinor(900001).setCurrency("RUB"))
            .build());
    assertFalse(cache.hasKey("flight:" + ID));
    assertFalse(cache.hasKey("search:SVO:LED:ALL"));
    assertEquals(900001, ops.get(get()).getPrice().getAmountMinor());
  }
}
