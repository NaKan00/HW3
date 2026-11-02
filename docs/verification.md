# Verification record

- Root multi-module `mvn clean test`: **31 tests, 0 failures, 0 errors, 0 skipped**.
  - Flight PostgreSQL/Redis integration: 13 (including actual network gRPC business flow and exclusion of non-SCHEDULED flights from SearchFlights).
  - In-process gRPC authentication: 3.
  - Booking PostgreSQL/REST integration: 7.
  - Retry/circuit breaker: 8.
- Clean Docker Compose build/start after `down -v`: all seven services healthy.
- `python3 scripts/demo.py`: passed, including SQL correlation of booking/reservation UUIDs and stock restoration.
- `python3 scripts/negative.py`: passed, including no partial booking after reservation failure.
- `python3 scripts/cache_demo.py`: passed; positive TTL (599 seconds observed), invalidation, repopulation and release.
- `python3 scripts/fault_tolerance.py`: passed; stopped and restored Flight, observed retries and CLOSED -> OPEN -> HALF_OPEN -> CLOSED. Open-circuit REST failure completed in approximately 0.004 seconds. Recovery polling waited for a successful flight request through Booking Service before checking the CLOSED transition.
- Sentinel reports `mymaster`, one replica, quorum 1; replica reports `master_link_status:up`. Flight actuator reports Redis UP through the configured Sentinel connection.
- Final SQL: three demo bookings CANCELLED, three reservations RELEASED, all seeded flight inventories restored (100/2/80/80 seats).

The scripts were executed on Linux with Python 3. Actual Windows execution and optional manual Redis failover were not independently exercised. The Python scripts use standard-library APIs and direct argument-list subprocess calls without shell dependencies. See architecture.md for intentional demonstration-system limitations.
