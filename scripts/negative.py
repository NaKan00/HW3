from common import *
ready()
api("/flights?origin=xx&destination=LED", expected=400)
missing = "ffffffff-ffff-ffff-ffff-ffffffffffff"
api("/flights/" + missing, expected=404)
api("/bookings/" + missing, expected=404)
api("/bookings", {"user_id": "bad"}, 400)
invalid = booking(); invalid["passenger_email"] = "invalid"
api("/bookings", invalid, 400)
count = sql("booking", "SELECT count(*) FROM bookings")
api("/bookings", booking(10000), 409)
assert sql("booking", "SELECT count(*) FROM bookings") == count
b = api("/bookings", booking(1), 201)
api("/bookings/" + b["id"] + "/cancel", {})
api("/bookings/" + b["id"] + "/cancel", {}, 409)
print("Negative scenarios passed; no partial booking after reservation failure.")
