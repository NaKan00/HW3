# HW3 - Flight Booking

Homework 3: flight booking system with REST, gRPC, PostgreSQL and Redis.

The project consists of two services:

- `booking-service` - REST API for searching flights and managing bookings
- `flight-service` - gRPC service for flights and seat reservations

Each service has its own PostgreSQL database. Flight Service also uses Redis for caching.

## Tech stack

- Java 21
- Spring Boot 3.3.5
- Maven
- gRPC / Protocol Buffers
- PostgreSQL 16
- Flyway
- Redis 7 + Sentinel
- Docker Compose
- Testcontainers

## Architecture

```text
Client
  |
  | REST
  v
Booking Service
  |
  | gRPC
  v
Flight Service
  |
  +--- PostgreSQL
  |
  +--- Redis

Booking Service
  |
  +--- PostgreSQL
```

Booking Service does not access the Flight Service database directly. Communication between the services goes through gRPC.

More details are available in:

- `docs/architecture.md`
- `docs/er-diagram.md`

## Project structure

```text
HW3/
├── booking-service/
├── flight-contract/
├── flight-service/
├── docs/
├── infra/redis/
├── scripts/
├── docker-compose.yml
└── pom.xml
```

`flight-contract` contains the `.proto` file and generated gRPC interfaces.

Generated files are created during the Maven build and are not stored in Git.

## Running the project

Requirements:

- Docker Desktop
- Docker Compose
- Python 3

From PowerShell:

```powershell
docker compose up --build
```

In another terminal:

```powershell
docker compose ps
```

The main ports are:

- Booking REST API: `localhost:8080`
- Flight gRPC: `localhost:9090`

To completely reset the project:

```powershell
docker compose down -v
docker compose up --build
```

## Demo scripts

The repository contains several scripts for checking the main scenarios.

Main scenario:

```powershell
py scripts/demo.py
```

Negative scenarios:

```powershell
py scripts/negative.py
```

Redis caching:

```powershell
py scripts/cache_demo.py
```

Retry and Circuit Breaker:

```powershell
py scripts/fault_tolerance.py
```

On Linux/macOS, use `python3` instead of `py`.

## REST API

| Method | Endpoint | Description |
|---|---|---|
| GET | `/flights?origin=SVO&destination=LED&date=2030-06-15` | Search flights |
| GET | `/flights/{id}` | Get flight |
| POST | `/bookings` | Create booking |
| GET | `/bookings/{id}` | Get booking |
| GET | `/bookings?user_id={id}` | Get bookings by user |
| POST | `/bookings/{id}/cancel` | Cancel booking |

Example booking request:

```powershell
$body = @{
    user_id='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
    flight_id='11111111-1111-1111-1111-111111111111'
    passenger_name='Demo Passenger'
    passenger_email='demo@example.org'
    seat_count=2
} | ConvertTo-Json

Invoke-RestMethod `
    -Method Post `
    -Uri http://localhost:8080/bookings `
    -ContentType application/json `
    -Body $body
```

## gRPC API

Flight Service contains the following methods:

- `SearchFlights`
- `GetFlight`
- `ReserveSeats`
- `ReleaseReservation`
- `UpdateFlight`

The contract is located in:

```text
flight-contract/src/main/proto/flight/v1/flight_service.proto
```

`Timestamp` is used for dates and enums are used for flight and reservation statuses.

## Booking creation

When a booking is created:

1. Booking Service calls `GetFlight`.
2. A booking UUID is generated.
3. Booking Service calls `ReserveSeats`.
4. Flight Service checks and reserves the requested seats.
5. Booking Service calculates the total price.
6. The booking is saved with status `CONFIRMED`.

The price is saved at the moment the booking is created:

```text
total_price = seat_count * flight.price
```

If seat reservation fails, the booking is not created.

If the seats were reserved but saving the booking fails, Booking Service calls `ReleaseReservation` to return them.

## Seat reservation

Seat reservation is performed inside one PostgreSQL transaction.

Flight Service:

1. locks the flight row;
2. checks that the flight is `SCHEDULED`;
3. checks available seats;
4. decreases `available_seats`;
5. creates a `SeatReservation`.

A pessimistic database lock is used so two concurrent requests cannot reserve the same last seat.

`booking_id` is also used as an idempotency key. Repeating the same `ReserveSeats` request does not decrease the number of seats again.

## gRPC authentication

Calls between Booking Service and Flight Service use an API key.

The key is sent in gRPC metadata:

```text
x-service-api-key
```

The value is configured using:

```text
GRPC_API_KEY
```

Flight Service checks the key in a gRPC server interceptor.

If the key is missing or incorrect, the service returns:

```text
UNAUTHENTICATED
```

## Redis cache

Flight Service caches:

```text
flight:{id}
```

and search results:

```text
search:{origin}:{destination}:{date}
```

For example:

```text
search:SVO:LED:2030-06-15
```

The default TTL is 600 seconds.

The cache works using Cache-Aside:

1. check Redis;
2. if the value exists, return it;
3. otherwise read from PostgreSQL;
4. save the result to Redis with TTL.

The cache is invalidated after:

- `ReserveSeats`
- `ReleaseReservation`
- `UpdateFlight`

Logs contain `CACHE_HIT`, `CACHE_MISS` and `CACHE_INVALIDATE`.

## Redis Sentinel

Docker Compose starts:

- `redis-master`
- `redis-replica`
- `redis-sentinel`

Flight Service connects to Redis through Sentinel.

Useful commands:

```powershell
docker compose exec redis-sentinel redis-cli -p 26379 sentinel master mymaster
```

```powershell
docker compose exec redis-sentinel redis-cli -p 26379 sentinel get-master-addr-by-name mymaster
```

```powershell
docker compose exec redis-replica redis-cli info replication
```

To check cache TTL:

```powershell
docker compose exec redis-master redis-cli TTL flight:11111111-1111-1111-1111-111111111111
```

## Retry

Booking Service retries gRPC calls only for:

- `UNAVAILABLE`
- `DEADLINE_EXCEEDED`

Maximum number of attempts:

```text
3
```

Default delays:

```text
100 ms
200 ms
```

Business errors such as `NOT_FOUND` and `RESOURCE_EXHAUSTED` are not retried.

## Circuit Breaker

The Circuit Breaker is implemented as a gRPC client interceptor in Booking Service.

It has three states:

```text
CLOSED
OPEN
HALF_OPEN
```

In `CLOSED`, calls work normally.

After several infrastructure errors, it changes to `OPEN`.

In `OPEN`, requests fail immediately without calling Flight Service.

After the timeout it changes to `HALF_OPEN` and allows a test request.

If the test request succeeds, it returns to `CLOSED`.

The main settings are configured with:

```text
CB_FAILURE_THRESHOLD
CB_OPEN_MILLIS
```

Transitions can be seen in Booking Service logs.

## Database inspection

Booking database:

```powershell
docker compose exec booking-postgres psql -U booking -d booking -c "SELECT id,user_id,flight_id,seat_count,total_price,status FROM bookings;"
```

Flight database:

```powershell
docker compose exec flight-postgres psql -U flight -d flight -c "SELECT id,flight_number,available_seats,total_seats,status FROM flights;"
```

Reservations:

```powershell
docker compose exec flight-postgres psql -U flight -d flight -c "SELECT booking_id,flight_id,seat_count,status FROM seat_reservations;"
```

The two services use separate databases and there are no foreign keys between them.

## Tests

Run all tests:

```powershell
mvn clean test
```

Integration tests use PostgreSQL and Redis through Testcontainers.

The tests cover:

- flight search
- flight retrieval
- booking creation
- cancellation
- insufficient seats
- transactions
- concurrent reservation
- idempotency
- gRPC authentication
- Redis caching and invalidation
- retry
- Circuit Breaker

## Sample data

The project contains several test flights for:

```text
2030-06-15
```

Examples:

```text
SVO -> LED
VKO -> LED
```

The main flight used by the demo scripts is:

```text
11111111-1111-1111-1111-111111111111
```
