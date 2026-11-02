CREATE TABLE flights (
 id UUID PRIMARY KEY, flight_number VARCHAR(30) NOT NULL, airline VARCHAR(100) NOT NULL,
 origin VARCHAR(3) NOT NULL CHECK(origin ~ '^[A-Z]{3}$'), destination VARCHAR(3) NOT NULL CHECK(destination ~ '^[A-Z]{3}$'),
 departure_time TIMESTAMPTZ NOT NULL, arrival_time TIMESTAMPTZ NOT NULL,
 total_seats INTEGER NOT NULL CHECK(total_seats>0), available_seats INTEGER NOT NULL CHECK(available_seats>=0 AND available_seats<=total_seats),
 price NUMERIC(14,2) NOT NULL CHECK(price>0), status VARCHAR(20) NOT NULL CHECK(status IN ('SCHEDULED','DEPARTED','CANCELLED','COMPLETED')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 CHECK(origin<>destination), CHECK(arrival_time>departure_time)
);
CREATE UNIQUE INDEX flight_number_utc_date_unique ON flights(flight_number, ((departure_time AT TIME ZONE 'UTC')::date));
CREATE INDEX flight_route_departure ON flights(origin,destination,departure_time);
CREATE TABLE seat_reservations (
 id UUID PRIMARY KEY, booking_id UUID UNIQUE NOT NULL, flight_id UUID NOT NULL REFERENCES flights(id),
 seat_count INTEGER NOT NULL CHECK(seat_count>0),status VARCHAR(20) NOT NULL CHECK(status IN ('ACTIVE','RELEASED','EXPIRED')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX reservation_flight ON seat_reservations(flight_id);
