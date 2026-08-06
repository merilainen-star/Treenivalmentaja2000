# API Integrations

*(Note: API integration is **planned** and currently mocked in the MVP).*

## Oura API V2

### Base Information
- **Base URL:** `https://api.ouraring.com/v2/usercollection`
- **Requested OAuth Scopes:** `daily`, `heartrate`, `personal`, `session`, `workout`

### Endpoints Used
1. `/daily_readiness` - For readiness scores.
2. `/daily_sleep` - For sleep scores.
3. `/daily_activity` - For activity scores.
4. `/workout` - For completed workouts.

### Request Parameters & Pagination
- Requests use `start_date` and `end_date` parameters (Format: YYYY-MM-DD).
- Pagination is handled via `next_token` in the response.

### Timestamp and Timezone Handling
- All internal scheduling uses Unix timestamps (UTC).
- UI and Oura parameters rely on local timezone, defaulting to `Europe/Helsinki` as specified by the product constraints.

### Units
- Metric units are enforced across the app and requested via API where applicable.

### Rate Limit Handling & Retry Behaviour
- **Rate Limit:** Oura API enforces a rate limit (HTTP 429). 
- **Retry:** Retrofit interceptors (or WorkManager policies) handle 429 and 5xx errors with exponential backoff.

### Used Fields from Workout Responses
- `id`
- `activity_type`
- `start_datetime`
- `end_datetime`
- `active_calories`

### Missing Data Handling
- Oura does not provide certain data on days the ring is not worn. The app handles null values for readiness, sleep, and activity gracefully. Missing data is NOT treated as zero.

### Third-Party Imports
- Workouts synced to Oura from third parties (e.g., Suunto → Strava → Oura) appear in the `/workout` endpoint. The app uses these transparently to match planned sessions.
- **Future Extension:** Direct Strava integration to fetch richer workout telemetry bypassing Oura if needed.
