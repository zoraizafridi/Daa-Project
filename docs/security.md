# Security posture

The server is a single-node educational application with simulated traffic and bounded in-memory state. It does not protect against a compromised host or an administrator who can modify its files.

## Credentials

`app.demoMode=true` permits the shipped accounts: admin/admin, analyst/analyst and viewer/viewer. These credentials must not be used on an exposed server.

Set `app.demoMode=false` and provide `NTD_ADMIN_PASSWORD`, `NTD_ANALYST_PASSWORD` and `NTD_VIEWER_PASSWORD`, or replace the configured password hashes. Startup rejects shipped credentials in production mode. Passwords use PBKDF2WithHmacSHA256 with random salts and constant-time verification. Plaintext credentials must satisfy `auth.password.minLength` and `auth.password.requireClasses`.

Admin is the primary role. Authorization is checked on the server using session-owned identity and permissions, not client navigation or a shared facade user. Viewer accounts cannot launch attacks or control simulation. Every `/api/daa/*` endpoint requires the Admin session role; Analyst and Viewer sessions are denied. DAA scenario runs and sensitivity copies do not mutate the live network or its last response plan.

## Sessions and CSRF

- Login rotates the session identifier; logout invalidates the session.
- The session cookie is HttpOnly and SameSite=Lax. `auth.session.secureCookie=true` adds Secure.
- `auth.session.timeoutSeconds` bounds idle time. `auth.session.absoluteTimeoutSeconds` bounds the lifetime regardless of activity; zero disables that additional limit.
- Login attempts are throttled by account and client address using `auth.login.maxAttempts` and `auth.login.windowSeconds`.
- An authenticated mutating request must echo the readable SameSite=Strict `csrf` cookie in `X-CSRF-Token`. Login is exempt from that token check.
- The client uses same-origin credentials; a 401 response clears its authenticated state.

## Requests and headers

The application serves HTTP. Use TLS before exposing credentials beyond localhost and enable Secure cookies. Enable `auth.hsts.enabled` only when the application is reached through HTTPS.

Responses carry Content-Security-Policy, X-Content-Type-Options, X-Frame-Options, Referrer-Policy, Permissions-Policy, Cross-Origin-Opener-Policy and Cross-Origin-Resource-Policy. HSTS is configurable. CSP currently permits inline scripts and styles.

`http.maxBodyBytes` limits request bodies. Cross-origin mutations are rejected when an Origin header is supplied. Unknown API paths do not fall through to the SPA. Unexpected errors return a generic 500 response rather than implementation details. The unauthenticated health endpoint exposes only liveness.

## Diagnostics and limits

Security actions use bounded, sanitized in-memory diagnostics. There is no durable audit trail, audit export or audit operations page. Application state and login-throttle counters are cleared when the process restarts.

CSRF uses a double-submit cookie rather than a session-bound signed token. Demo defaults do not enable Secure cookies or HSTS. These defaults support a local course demonstration, not a hardened production deployment.
