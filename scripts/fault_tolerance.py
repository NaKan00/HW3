from common import *
def wait_for_flight(timeout=150):
    deadline = time.monotonic() + timeout
    while (remaining := deadline - time.monotonic()) > 0:
        try:
            with urllib.request.urlopen(BASE + "/flights/" + FLIGHT, timeout=min(15, remaining)) as response:
                flight = json.load(response)
            assert flight["id"] == FLIGHT, flight
            return
        except urllib.error.HTTPError as error:
            if error.code != 503:
                raise
        except (urllib.error.URLError, TimeoutError):
            pass
        remaining = deadline - time.monotonic()
        if remaining > 0:
            time.sleep(min(1, remaining))
    raise RuntimeError("Flight request did not recover within the timeout")

ready()
try:
    compose("stop", "flight-service")
    for _ in range(4):
        api("/flights/" + FLIGHT, expected=503)
    started = time.monotonic()
    api("/flights/" + FLIGHT, expected=503)
    elapsed = time.monotonic() - started
    assert elapsed < 1, ("Circuit did not fail fast", elapsed)
    print("Circuit OPEN: HTTP 503 in", round(elapsed, 3), "seconds")
finally:
    compose("start", "flight-service")
wait_for_flight(150)
logs = compose("logs", "--since", "3m", "booking-service")
for transition in ("CLOSED -> OPEN", "OPEN -> HALF_OPEN", "HALF_OPEN -> CLOSED"):
    assert transition in logs, transition
assert "retryable=true" in logs
print("Fault tolerance passed: retries, OPEN, HALF_OPEN and recovery verified.")
