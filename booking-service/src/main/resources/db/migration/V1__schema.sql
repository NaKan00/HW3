CREATE TABLE bookings (
 id UUID PRIMARY KEY,user_id UUID NOT NULL,flight_id UUID NOT NULL,
 passenger_name VARCHAR(200) NOT NULL CHECK(length(trim(passenger_name))>0), passenger_email VARCHAR(254) NOT NULL,
 seat_count INTEGER NOT NULL CHECK(seat_count>0),total_price NUMERIC(14,2) NOT NULL CHECK(total_price>0),
 status VARCHAR(20) NOT NULL CHECK(status IN ('CONFIRMED','CANCELLED')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX booking_user ON bookings(user_id,created_at);
