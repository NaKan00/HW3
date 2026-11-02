from common import *
ready()
key = "flight:" + FLIGHT
route = "/flights?origin=SVO&destination=LED&date=" + DATE
search_key = "search:SVO:LED:" + DATE
redis("DEL", key, search_key)
print("1. Cache miss: reading flight and route")
before = api("/flights/" + FLIGHT)["available_seats"]
api(route)
assert int(redis("TTL", key)) > 0
assert int(redis("TTL", search_key)) > 0
print("2. Cache hit:", api("/flights/" + FLIGHT)["available_seats"])
print("3. TTL:", redis("TTL", key))
b = api("/bookings", booking(1), 201)
try:
    assert redis("EXISTS", search_key) == "0"
    print("4. Route cache invalidated; PostgreSQL mutation committed")
    assert api("/flights/" + FLIGHT)["available_seats"] == before - 1
    assert int(redis("TTL", key)) > 0
    assert next(f for f in api(route) if f["id"] == FLIGHT)["available_seats"] == before - 1
    print("5. Cache repopulated with updated available_seats")
finally:
    api("/bookings/" + b["id"] + "/cancel", {})
assert api("/flights/" + FLIGHT)["available_seats"] == before
print("Cache demonstration passed. Inspect CACHE_MISS / CACHE_HIT / CACHE_INVALIDATE logs.")
