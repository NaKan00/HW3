# HW3 — Flight Booking

Java 21 / Spring Boot 3.3.5, official grpc-java 1.68.1, PostgreSQL 16.6, Flyway and Redis 7.4.1 Sentinel. Two independent services and databases. See [architecture](docs/architecture.md) and [ER diagrams / 3NF](docs/er-diagram.md) for transaction boundaries and failure semantics.

## Quick start (Windows PowerShell)

Install Docker Desktop with Compose v2 and Python 3. No shell scripts or third-party Python dependencies are required.

```powershell
docker compose up --build
# In another terminal:
docker compose ps
py scripts/demo.py
py scripts/negative.py
py scripts/cache_demo.py
# Optional: stops and restores Flight Service
py scripts/fault_tolerance.py
```

Linux/macOS: replace `py` with `python3`. Scripts wait for health, assert outcomes and exit nonzero on failures. Run them serially, without other clients changing inventory. `demo.py` prints SQL evidence, checks reservation booking UUID, cancellation, stock restoration and TTL. Cache events are visible with `docker compose logs flight-service`.

REST: localhost:8080. gRPC: localhost:9090. Flight health HTTP :8081 is internal. PostgreSQL and Redis are intentionally not published to the host. Named volumes retain data. To reset only this project's demonstration data:

```powershell
docker compose down -v
docker compose up --build
```

Sample flights depart **2030-06-15 UTC**: SU100/SU102 SVO -> LED, DP200/DP202 VKO -> LED. SU102 has only 2 seats for insufficient-stock demonstrations. The main demo flight UUID is `11111111-1111-1111-1111-111111111111`.

## Layout and build

- `flight-contract`: protobuf source, generated Java only in target/generated-sources
- `flight-service`: gRPC server, Flight PostgreSQL, reservation transactions, explicit cache-aside
- `booking-service`: REST, Booking PostgreSQL, gRPC client middleware and compensation
- `infra/redis`: master/replica/Sentinel configurations
- `scripts`: standard-library Python defense scenarios
- `docs`: architecture, status mapping and ER models

With a **full JDK 21** and Maven 3.9.9+:

```powershell
mvn clean test
mvn -DskipTests package
```

Tests require a working Docker daemon for PostgreSQL/Redis Testcontainers. No H2 substitution; integration tests use actual PostgreSQL locks and constraints. Unit/in-process gRPC tests cover authentication and resilience. Compose E2E checks Sentinel topology. Generated sources, target directories, .env and data are excluded from Git.

## REST API

| Method | Path | Action |
|---|---|---|
| GET | /flights?origin=SVO&destination=LED&date=2030-06-15 | search; date optional |
| GET | /flights/{uuid} | flight snapshot |
| POST | /bookings | reserve and persist, returns 201 |
| GET | /bookings/{uuid} | booking |
| GET | /bookings?user_id={uuid} | user's bookings |
| POST | /bookings/{uuid}/cancel | release then cancel |

Example:

```powershell
$body = @{user_id='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'; flight_id='11111111-1111-1111-1111-111111111111'; passenger_name='Demo Passenger'; passenger_email='demo@example.org'; seat_count=2} | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://localhost:8080/bookings -ContentType application/json -Body $body
```

Error shape: `{"error_code":"RESOURCE_EXHAUSTED","message":"Insufficient seats","details":{}}`. Validation returns 400; missing objects 404; stock/state/idempotency conflicts 409; unavailable/deadline/authentication failures 503. See full mapping in architecture docs.

## gRPC and consistency

`flight.v1.FlightService`: SearchFlights, GetFlight, ReserveSeats, ReleaseReservation, UpdateFlight. Proto uses Timestamp, explicit status enums and integer minor-unit Money. UpdateFlight permits optional time, price and status changes while preserving capacity invariants. Same authentication applies to all methods.

Booking obtains GetFlight first, generates booking UUID, reserves using that UUID, calculates total from the GetFlight BigDecimal price snapshot, then commits its own database transaction. A local persistence failure triggers compensating release. Cancellation locks Booking and changes status only after successful remote release. There is no distributed ACID transaction. See architecture docs for crash/ambiguous-timeout reconciliation limits.

ReserveSeats takes transaction-scoped booking advisory lock and pessimistic flight row lock. Sequential/concurrent duplicate keys return the same reservation without double decrement; mismatched parameters return ALREADY_EXISTS. Release is idempotent. Booking double cancellation returns 409.

`GRPC_API_KEY` is supplied through `x-service-api-key` metadata by client interceptor and checked by server interceptor. `.env.example` contains local demo values; optionally copy it to `.env` and choose your own key. Never use the demonstration key in production.

## Cache / Sentinel

Cache keys: `flight:{uuid}`, `search:{origin}:{destination}:{date}`, or `search:SVO:LED:ALL`. Every write has TTL (`CACHE_TTL_SECONDS`, default 600). Explicit HIT/MISS logs demonstrate cache-aside. After commit, reservation/release/update invalidate flight and affected date/ALL route keys; date changes invalidate old and new date keys. No global flushing in application code. Redis failures fall back to PostgreSQL.

```powershell
docker compose exec redis-sentinel redis-cli -p 26379 sentinel master mymaster
docker compose exec redis-sentinel redis-cli -p 26379 sentinel get-master-addr-by-name mymaster
docker compose exec redis-replica redis-cli info replication
docker compose exec redis-master redis-cli keys 'flight:*'
docker compose exec redis-master redis-cli TTL flight:11111111-1111-1111-1111-111111111111
# Optional manual failover; then query Sentinel for the current master:
docker compose exec redis-sentinel redis-cli -p 26379 sentinel failover mymaster
```

Lettuce uses `REDIS_SENTINEL_MASTER=mymaster` and `REDIS_SENTINEL_NODES=redis-sentinel:26379`. After failover, direct redis-master inspection may refer to the old replica; Python scripts discover the current master through Sentinel. A single Sentinel/quorum 1 is a teaching topology, not production HA.

## Retry and circuit breaker

Up to **3 total attempts**, backoff 0/100/200ms (`GRPC_BACKOFF_MS` base), only UNAVAILABLE/DEADLINE_EXCEEDED, fresh deadline per attempt (`GRPC_DEADLINE_MS=1000`). ClientInterceptor circuit breaker defaults: `CB_FAILURE_THRESHOLD=5`, `CB_OPEN_MILLIS=10000`. OPEN rejects locally, stops retrying and returns REST 503; HALF_OPEN allows one probe. Logs show attempts, operation/status and state transitions, never credentials.

```powershell
py scripts/fault_tolerance.py
docker compose logs booking-service
```

## Database inspection

```powershell
docker compose exec booking-postgres psql -U booking -d booking -c 'SELECT id,user_id,flight_id,seat_count,total_price,status FROM bookings;'
docker compose exec flight-postgres psql -U flight -d flight -c 'SELECT id,flight_number,available_seats,total_seats FROM flights;'
docker compose exec flight-postgres psql -U flight -d flight -c 'SELECT booking_id,flight_id,seat_count,status FROM seat_reservations;'
```

Each service owns its Flyway migrations; Hibernate only validates schemas (`ddl-auto=validate`). Compose credentials are separate, local-only demo credentials. No cross-database FK or Flight JDBC access from Booking.
