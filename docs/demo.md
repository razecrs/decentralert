# Demo walkthrough

Start `cargo run` in `backend`, then run the Kotlin/Wasm dashboard from `client`.

## Create an unknown emergency

```powershell
$body = @{
  idempotencyKey = "demo-001"
  category = "unknown"
  location = @{ latitude = 52.3728; longitude = 4.8936; accuracyMeters = 7 }
  description = "Person unresponsive near Dam Square"
  profile = @{
    displayName = "Demo Citizen"
    allergies = @("penicillin")
    medications = @()
    conditions = @("asthma")
    accessibilityNeeds = @()
    emergencyContacts = @()
    preferredLanguage = "nl-NL"
  }
  evidence = @()
} | ConvertTo-Json -Depth 8

Invoke-RestMethod -Method Post -Uri http://localhost:8080/v1/incidents -ContentType application/json -Body $body
```

The response contains four simulated service acknowledgements and ETA-ranked responder offers. Repeating the request with `demo-001` does not create another incident.

## Create an authorized public warning

```powershell
$warning = @{
  title = "Smoke plume"
  instructions = "Go indoors and close windows"
  category = "fire"
  center = @{ latitude = 52.37; longitude = 4.89; accuracyMeters = 10 }
  radiusMeters = 1500
} | ConvertTo-Json -Depth 5

Invoke-RestMethod -Method Post -Uri http://localhost:8080/v1/public-warnings `
  -Headers @{ Authorization = "Bearer demo-operator" } `
  -ContentType application/json -Body $warning
```

Omitting the authorization header returns HTTP 401. The endpoint records a simulated in-app warning only; it does not reach NL-Alert.

