from common import *
ready()
route = "/flights?origin=SVO&destination=LED&date=" + DATE
flights = api(route)
assert len(flights) >= 2
assert api(route) == flights
before = api("/flights/" + FLIGHT)["available_seats"]
b = api("/bookings", booking(), 201)
print(json.dumps(b, indent=2))
try:
    assert b["status"] == "CONFIRMED"
    assert api("/flights/" + FLIGHT)["available_seats"] == before - 2
    assert sql("booking", "SELECT status FROM bookings WHERE id='" + b["id"] + "'") == "CONFIRMED"
    assert sql("flight", "SELECT status FROM seat_reservations WHERE booking_id='" + b["id"] + "'") == "ACTIVE"
    print(sql("booking", "SELECT id, flight_id, total_price, status FROM bookings WHERE id='" + b["id"] + "'"))
    print(sql("flight", "SELECT id, available_seats FROM flights WHERE id='" + FLIGHT + "'"))
    print(sql("flight", "SELECT booking_id, seat_count, status FROM seat_reservations WHERE booking_id='" + b["id"] + "'"))
    print("Redis keys:", redis("KEYS", "flight:*"), "TTL:", redis("TTL", "flight:" + FLIGHT))
finally:
    api("/bookings/" + b["id"] + "/cancel", {})
assert api("/bookings/" + b["id"])["status"] == "CANCELLED"
assert sql("flight", "SELECT status FROM seat_reservations WHERE booking_id='" + b["id"] + "'") == "RELEASED"
assert api("/flights/" + FLIGHT)["available_seats"] == before
print("Booking CANCELLED; reservation RELEASED; seats restored.")
print("E2E walkthrough passed.")
