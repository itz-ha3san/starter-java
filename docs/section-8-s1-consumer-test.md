# Section 8 — S1 Consumer-side HTTP Stub test

The S3 → S1 `POST /api/v1/accounts/transfer-check` consumer is implemented in
`integration/s1/S1AccountClient`. Its base URL is configured by
`NEIGHBOR_BASE_URL` (default: `http://localhost:8081`). The request follows
`openapi/S1.yaml`: two Iranian Sheba values, the S1 customer Bearer token, and
an optional `X-Correlation-ID`.

`S1TransferAccountCheckService` applies S3-side transfer eligibility rules to
the returned account facts: source must be active, destination must be found
and active, and amount must not exceed the source limit.

`S1TransferAccountCheckServiceTest` runs a real HTTP exchange against a local
WireMock server. The stub is not called as a Java mock; `S1AccountClient`
performs the HTTP request. The success payloads mirror the named 200 examples
in `openapi/S1.yaml`. Tests assert S3's decisions, request body and headers,
not merely that WireMock received a call.

Covered cases: accepted transfer; destination not found; inactive source;
inactive destination; amount over source limit; S1 400/401/404/503 responses;
connection failure; invalid Sheba; and missing customer token.

Run the focused test with:

```bash
mvn -Dtest=S1TransferAccountCheckServiceTest test
```

Set the neighbor address when running against a different local S1 instance:

```powershell
$env:NEIGHBOR_BASE_URL = "http://localhost:8081"
```

The S1 client and its consumer-side test are complete as an isolated integration
component. It is deliberately not yet invoked from `TransferService.createTransfer`:
the current public S3 request contract does not supply source/destination Sheba
and the S1 customer token. Wiring it into the transfer flow before those values
are defined would require fabricating identity/account data. This boundary is
recorded in ADR-0002 and does not affect the Section 8 HTTP consumer stub test.
