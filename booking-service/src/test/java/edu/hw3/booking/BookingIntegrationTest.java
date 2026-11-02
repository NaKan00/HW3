package edu.hw3.booking;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import edu.hw3.contract.*;
import io.grpc.Status;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.*;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@Testcontainers
@SpringBootTest(properties = {"grpc.api-key=test-key", "server.port=0"})
class BookingIntegrationTest {
  @Container static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16.6-alpine");

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", pg::getJdbcUrl);
    r.add("spring.datasource.username", pg::getUsername);
    r.add("spring.datasource.password", pg::getPassword);
  }

  @Autowired org.springframework.test.web.servlet.MockMvc mvc;
  @Autowired BookingService service;
  @SpyBean BookingRepository repo;
  @MockBean FlightClient client;
  CreateBooking request =
      new CreateBooking(
          UUID.randomUUID(), UUID.randomUUID(), "Test Passenger", "test@example.org", 2);

  @BeforeEach
  void setup() {
    repo.deleteAll();
    when(client.get(anyString()))
        .thenReturn(
            Flight.newBuilder()
                .setPrice(Money.newBuilder().setAmountMinor(12345).setCurrency("RUB"))
                .build());
    when(client.reserve(anyString(), anyString(), anyInt()))
        .thenReturn(Reservation.newBuilder().setStatus(ReservationStatus.ACTIVE).build());
  }

  @Test
  void searchAndGetProxy() throws Exception {
    when(client.search("SVO", "LED", "2030-06-15"))
        .thenReturn(
            SearchFlightsResponse.newBuilder()
                .addFlights(Flight.newBuilder().setId("test"))
                .build());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/flights?origin=SVO&destination=LED&date=2030-06-15"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].id")
                .value("test"));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/flights/" + request.flightId()))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
  }

  @Test
  void invalidRestInput() throws Exception {
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/flights?origin=xx&destination=LED"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isBadRequest());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/bookings")
                .contentType("application/json")
                .content("{}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isBadRequest());
  }

  @Test
  void createPriceSnapshotAndList() {
    var b = service.create(request);
    assertEquals(new BigDecimal("246.90"), b.totalPrice);
    assertEquals("CONFIRMED", service.get(b.id).status);
    assertEquals(1, service.list(request.userId()).size());
    var order = inOrder(client);
    order.verify(client).get(request.flightId().toString());
    order.verify(client).reserve(b.id.toString(), request.flightId().toString(), 2);
  }

  @Test
  void reservationFailureNoBooking() {
    when(client.reserve(anyString(), anyString(), anyInt()))
        .thenThrow(Status.RESOURCE_EXHAUSTED.asRuntimeException());
    assertThrows(RuntimeException.class, () -> service.create(request));
    assertEquals(0, repo.count());
  }

  @Test
  void cancellation() {
    var b = service.create(request);
    assertEquals("CANCELLED", service.cancel(b.id).status);
    verify(client).release(b.id.toString());
    assertThrows(RuntimeException.class, () -> service.cancel(b.id));
    verify(client, times(1)).release(b.id.toString());
  }

  @Test
  void releaseFailureRemainsConfirmed() {
    var b = service.create(request);
    when(client.release(anyString())).thenThrow(Status.UNAVAILABLE.asRuntimeException());
    assertThrows(RuntimeException.class, () -> service.cancel(b.id));
    assertEquals("CONFIRMED", service.get(b.id).status);
  }

  @Test
  void persistenceFailureCompensates() {
    doThrow(new org.springframework.dao.DataIntegrityViolationException("simulated"))
        .when(repo)
        .saveAndFlush(any());
    try {
      assertThrows(RuntimeException.class, () -> service.create(request));
      verify(client).release(anyString());
      assertEquals(0, repo.count());
    } finally {
      reset(repo);
    }
  }
}
