# Inventory HTTP security design

Status: Agreed specification, 2026-11-08; implementation pending.

## 1. Scope, baseline and dependencies

This is Inventory's Phase 2 design under the [global plan](../../../SECURITY_PLAN.md) and
[Common contract](../../../rent-flow-common/SECURITY_SPEC.md). It changes the HTTP trust
boundary and status-request key scope. Earlier service-skeleton, status-transition and
observability specs remain authoritative for business behavior except their unauthenticated
access, Security bans and global-key statements, which this feature supersedes.

Baseline: Java 25, Spring Boot 4.1.0, Springdoc 3.0.3, PostgreSQL 18.4, Flyway migrations V1–V4.
The locally cached Boot 4.1.0 dependency BOM manages Spring Security 7.1.0. Use the Boot-managed
version; no framework upgrade or independent Security pin is part of this feature. Add
spring-boot-starter-security-oauth2-resource-server and its Boot-managed test support. Remove the
Maven ban only as part of the secured cutover. Replace the ArchUnit Security ban with assertions
that Security dependencies stay in config/service/converter/controller code, never model,
repository, DTO or util. Retain existing layer directions and package/component rules.

No new production package or shared module is needed. Put filter chains, JWT validation and
security handlers in config, authority conversion in converter, the small authorization policy
in service (InventoryAuthorizationService), and a composite persistence identifier in model.
Reuse ProblemResponse, LengthPrefixedSha256 and the existing ledger/cleanup repositories.
Configuration must not acquire a compile-time dependency on service classes; method-security
expressions can reference the service bean by name. Any serialization helper must follow the
existing config-to-DTO dependency direction.

## 2. JWT validation and identity

Use Spring Security's resource-server bearer processing and Nimbus-backed JWT decoder. Do not
write an authentication filter or trust decoded but unverified claims. Accept exactly one bearer
credential through Authorization; leave form/query bearer-token resolution disabled. Duplicate
Authorization values and malformed bearer syntax are rejected, never concatenated or selected
arbitrarily. Multiple token mechanisms cannot select a different identity.

| Check | Required behavior |
| --- | --- |
| Signature | RS256 only, using the configured issuer's JWKS; reject none, HMAC, other RSA/EC algorithms and tampering |
| JOSE type | typ exactly JWT |
| Payload type | typ exactly Bearer; ID/refresh token shapes fail |
| Issuer | Exact configured issuer string, nonblank; no alternate localhost/master/dev issuer |
| Audience | inventory-api is a member of a nonempty string or array-of-strings aud; reject other types |
| Identity | Nonblank string sub and azp |
| Time | Numeric iat and exp required; exp later than iat; iat no later than now + 30s; exp and optional numeric nbf validated with exactly 30s skew |
| Role structure | If resource_access, this API's entry, or its roles is present with an incompatible type, reject the token; absent roles means zero permissions |

The explicit permission allowlist is inventory:read, inventory:list, inventory:manage,
inventory:history and inventory:reserve. Convert only string roles from
resource_access["inventory-api"].roles into PERM_ followed by the exact role. Ignore unknown
string roles, every other resource client's roles, and realm roles for authorization. OAuth
scope/scp values may retain Spring's SCOPE_ prefix but never satisfy a PERM_ check. Missing own
roles is a valid authenticated token that receives 403 on protected operations. Do not infer a
permission from CUSTOMER, STAFF, username, email or caller-supplied headers.

Caller classification comes from the verified azp:

- Human clients: inventory-swagger and rentflow-pkce-check only initially.
- Pricing machine: pricing-service only.
- Reservation machine: reservation-service only.
- Other clients: authenticated if the token contract passes, but denied all business operations.

Client classification restricts capabilities even if a token accidentally contains extra allowed
roles. Permissions are still required; azp alone grants nothing. A human's identity is (iss, sub),
not azp. No service-account subject UUID is hardcoded: Common owns those identities, disallows
interactive grants on machine registrations and verifies effective grants. Adding another human
tool requires an explicit policy/configuration change and tests, not a wildcard caller allowlist.

Configure spring.security.oauth2.resourceserver.jwt.issuer-uri and an explicit matching
jwk-set-uri ending in /protocol/openid-connect/certs. Require both values, validate their URL
shape at startup and retain exact issuer validation in the decoder. Require absolute URLs without
userinfo, query or fragment and the JWKS URL to equal the issuer plus that exact suffix.
There is no development issuer
fallback. HTTP is allowed only for the documented local/smoke issuer topology; deployed settings
must use HTTPS. Inventory requires no client secret. Local values are:

- Issuer: http://auth.rentflow.test:8180/realms/rentflow-local.
- JWKS: http://auth.rentflow.test:8180/realms/rentflow-local/protocol/openid-connect/certs.

Use the explicit JWKS URL to avoid discovery at startup and construct the decoder without fetching
keys during bean creation. Configure supported HTTP fetching with a 2s connect timeout, 2s read
timeout and no application retry loop. Keep the supported in-memory JWKS cache (five-minute TTL
for the selected decoder unless the resolved version proves otherwise); verify actual behavior
against Security 7.1.0. No unbounded stale-key extension or acceptance of unknown keys. Tests warm
the cache, stop the fixture, verify a known-key token within cache lifetime, then reject a new kid.
A new uncached key fails closed if JWKS is unavailable; return sanitized 401 rather than a stack
trace or dependency error. Key refresh and overlapping-key rotation use the supported decoder;
full real-Keycloak rotation acceptance remains Phase 5. Do not make Keycloak a health contributor
or a Compose dependency. Missing required configuration fails startup; provider unavailability
with valid configuration does not.

Tokens last 300s in Common. Logout, account disabling and removed grants do not revoke an already
issued JWT immediately; the remaining lifetime plus up to 30s skew remains the documented window.
Newly issued reduced-permission tokens must be checked before replay. Do not imply immediate
revocation or reauthorize old JWTs by calling Keycloak per request.

## 3. Permission matrix and HTTP rules

Human role composites proposed for Common:

| Business role | Inventory client roles |
| --- | --- |
| CUSTOMER | inventory:read, inventory:list |
| STAFF | inventory:read, inventory:list, inventory:manage, inventory:history |

STAFF receives its own explicit set; it does not depend on also holding CUSTOMER. Neither receives
inventory:reserve. The resource server checks client permissions and caller class, not realm role
names. inventory:manage does not implicitly include read/list/history; Common grants all four.

| Method and exact route shape | Required permission | Approved caller class |
| --- | --- | --- |
| GET /api/v1/inventory/{serialNumber} | inventory:read | Human or Pricing |
| GET /api/v1/inventory | inventory:list | Human |
| POST /api/v1/inventory | inventory:manage | Human |
| PUT /api/v1/inventory/{serialNumber} | inventory:manage | Human |
| DELETE /api/v1/inventory/{serialNumber} | inventory:manage | Human |
| GET /api/v1/inventory-history | inventory:history | Human |
| PATCH /api/v1/inventory/status | inventory:manage OR inventory:reserve | Human with manage OR Reservation with reserve; see §4 |

All business routes require authentication. Match method and full path shape, never a broad
authenticated /api/** allowance. HEAD is denied even though MVC can dispatch it to GET; OPTIONS
has no default allowance because Swagger uses the same origin and there is no separate browser
frontend. Do not enable CORS or credentialed origins without a new concrete requirement. The
literal status path is a PATCH operation, while GET /inventory/status remains a lookup for an
item whose serial is status; retain existing serial-number validation.

Use denyAll for unmatched requests on the application listener after explicit probe/docs rules.
For protected or unmatched paths, missing credentials produce 401; valid credentials with no
allowed route/operation produce 403. This deliberately supersedes earlier expectations of public
404/405 for denied routes/methods. Existing 400/404/409/415/422 contracts still apply after an
authorized caller reaches the mapped operation. An authenticated denied item request gets 403
for both existing and nonexistent serial numbers, before any existence query.

Enable method security on public InventoryService CRUD, get, list, history and transitionStatus,
and on IdempotentStatusTransitionService.transition. Use a small explicit policy service and
@PreAuthorize checks, not duplicated claim parsing or permission strings throughout controllers.
HTTP rules screen caller class/operation; method rules protect the same business boundary for
other invocations and inspect status targets where required. Both use the same permission/caller
definitions. Proxied-service tests prove the checks execute; pure unit tests with direct new
instances continue testing domain logic without pretending to exercise authorization.

The cancellation listener's releaseAfterCancellation entry point and the cleanup scheduler remain
separate internal operations without human bearer requirements. Never add an anonymous bypass
to HTTP-capable methods for background work. These internal operations have no HTTP mapping and
retain the existing Kafka/inbox and scheduler trust boundaries. Source review and tests must check
self-invocation and proxy reachability so no alternative HTTP route bypasses method security.

## 4. Status-operation authorization and validation order

Keep PATCH /api/v1/inventory/status, its direct array of 1–100 unique serial/status entries,
required canonical UUID-v4 Idempotency-Key and existing response contract. Staff manage permission
allows the complete existing transition matrix. Reservation reserve permission allows only
requests where every defined target is RESERVED. Even a Reservation token with manage added
must take only the Reservation branch. Human reserve permission alone never allows PATCH.

Processing order:

1. Authenticate the bearer token and authorize route, caller class and operation permission.
2. Apply existing media, JSON, DTO, array-size, duplicate-serial and key validation. Unreadable or
   invalid inputs retain their existing 400/415 contract; they never touch the ledger.
3. Authorize the entire parsed batch at the service boundary. Any defined non-RESERVED target
   from Reservation yields generic 403, with no item lookups, advisory locks or replay access.
4. Derive the authenticated scope, acquire the scoped execution lock and examine the scoped ledger.
5. For a new execution, apply existing row locks, all-entry lifecycle checks and atomic writes.
   AVAILABLE is the only existing source that may become RESERVED. Other sources yield the
   existing aggregate 409; missing-only failures yield 404, and mixed failures keep precedence.
6. Commit outcome and business writes in one transaction, then return the existing terminal
   status, body and replay/expiry headers. Replay does not re-evaluate current item status.

Step 3 precedes every replay, including stored successful, missing-item and conflict outcomes.
An authorized same-key replay after the item changes/deletes still returns its saved result.
Caller-permission failures occur before request-body validation; target-level authorization occurs
after valid parsing, so invalid enum tokens are 400 rather than a guessed target-based 403.
Authorization is never stored as a terminal ledger outcome.

Preserve unrestricted defined status assignment on staff POST/PUT, including no status-history
record for those operations. This is an explicit administrative capability, not a claim that PUT
enforces lifecycle rules. Machines cannot use that capability. No new status, inspection proof,
ownership field or Kafka release permission is introduced.

## 5. Authenticated idempotency and migration

The operation namespace is still the status batch endpoint. An authenticated caller scope is:

```text
p1: + LengthPrefixedSha256.hex([
  "inventory-status-transition:principal:v1", verified_iss, verified_sub
])
```

Use the existing UTF-8, four-byte length-prefixed hashing implementation; no concatenated strings
or unverified request values. The result is 67 characters. Do not include azp, token text, jti,
username or role membership: the same subject can retry through another approved human client
with current permissions. Distinct service-account subjects are separate callers. Subject
recreation or issuer changes deliberately create a new scope. Scope is derived inside the
authenticated service boundary and cannot be supplied through a header/body parameter.

Add V5__scope_inventory_status_transition_requests.sql (use the next free version if V5 is taken
before implementation). Never edit V3. In the inventory schema only:

- Add caller_scope varchar(80) NOT NULL, backfilling existing rows with the exact reserved value
  legacy:unauthenticated. Drop any migration-only default after backfill.
- Constrain the value to legacy:unauthenticated or the p1: prefix plus exactly 64 lowercase hex
  characters. Runtime authenticated code only generates the p1: form and cannot query legacy.
- Replace the UUID-only primary key with (caller_scope, idempotency_key). Preserve outcome,
  fingerprint, recorded_at, expires_at, terminal-status constraints and all existing rows.
- Replace the expiry index with (expires_at, caller_scope, idempotency_key).

Use an embedded composite identifier in model with value equality, and adapt repository IDs and
derived pessimistic lookup to both components. Do not retain an authenticated UUID-only lookup,
fallback to legacy rows, or an owner claim supplied by a client. JPA/JPQL remain preferred except
the existing PostgreSQL-specific advisory lock, clock and SKIP LOCKED cleanup.

The execution-lock ID is LengthPrefixedSha256.firstLong over
["inventory-status-transition:key:v2", caller_scope, canonical_uuid]. Scope both the saved row
and the lock: scoping only storage would leave cross-caller busy conflicts. As with the current
64-bit advisory hash, a theoretical collision can conservatively report busy but never expose
another scope's outcome; deterministic distinct-scope test vectors must not collide.

Cleanup's expired CTE selects both caller_scope and idempotency_key, orders by expires_at then
caller_scope then idempotency_key, and DELETE joins on both key columns. Its 1000-row limit,
SKIP LOCKED, per-chunk transactions, daily UTC schedule and runtime budget remain unchanged.
Test equal UUIDs in different scopes with one expired and the other unexpired: a UUID-only delete
would violate the contract. Legacy rows remain eligible only when their original expiry passes.

Retain the existing payload fingerprint, 7-day TTL, database clock, 204/404/409 saved outcomes,
409 busy plus Retry-After: 1, 422 mismatched payload, expiry reuse before cleanup, replay headers,
and item/history/ledger rollback guarantees within each new scope. No new ledger entry is created
for 400/401/403/415/422/busy failures. Existing metrics remain bounded and contain no caller scope.

Cutover requires stopping old Inventory instances, draining in-flight writes, applying the new
migration and deploying only code that understands composite keys and enforced authentication.
Old binaries cannot safely coexist with the changed primary key. Retain a database backup and
the migration-compatible artifact; rollback requires coordinated schema/data restoration, not
running the old binary against the migrated ledger. Migration does not delete business or history
rows. Pre-cutover saved results cannot be safely attributed; callers must use fresh keys and
reconcile uncertain pre-cutover operations instead of blindly reissuing them. Reusing an old UUID
in an authenticated scope is a new operation, never proof that a legacy outcome belongs to it.

## 6. Security responses and telemetry

Use the existing ProblemResponse shape and application/problem+json for security errors.
instance contains request path only, never query, token or exception text. Omit violations.

| Status | type | title | code | detail |
| --- | --- | --- | --- | --- |
| 401 | urn:rentflow:problem:authentication-required | Authentication required | AUTHENTICATION_REQUIRED | Valid bearer authentication is required. |
| 403 | urn:rentflow:problem:access-denied | Access denied | ACCESS_DENIED | This operation is not permitted. |

Use Bearer challenge semantics: missing credentials receive WWW-Authenticate: Bearer;
invalid/expired/unverifiable tokens receive a Bearer challenge with error="invalid_token";
authenticated permission/target denials receive a Bearer challenge with
error="insufficient_scope". Omit error_description, token details and a scope attribute: local
permissions are not OAuth scopes. Malformed/duplicate bearer credentials use sanitized 401 with
the invalid_token challenge for consistency. No Location redirect, Set-Cookie or replay headers
on these denials. Security responses take precedence over Accept negotiation for generic denials.

Implement AuthenticationEntryPoint and AccessDeniedHandler and explicitly handle method-security
AccessDeniedException in ApiExceptionHandler. Its current catch-all Exception handler must not
turn method denials into 500 or log their full exceptions. Reuse the same response definition
for filter and MVC paths, preserving WWW-Authenticate. Keep framework request-rejection details
and JWT decoder exceptions out of responses and ordinary logs.

Record inventory.security.denials with only reason=authentication|authorization and
surface=application|management. Emit a sanitized structured diagnostic containing only those
bounded fields and numeric status, under the existing log access/retention policy; no security
denial stack traces, raw URI/query, serial numbers, subject/client IDs, key IDs, scope hashes,
claim values or token text. Existing request status/trace correlation can supply context without
adding identity labels. These diagnostics are not a durable audit trail. Do not enable Security
TRACE/DEBUG or credential-bearing HTTP logging in normal profiles. Synthetic-marker tests inspect
responses, captured logs, spans and new metric labels for credential/identity/body leakage.

## 7. Filter chains, operations and Swagger

Select chains by actual server/listener identity, not Host or X-Forwarded-* headers. The management
server stays separate (8085, loopback by default; private container binding permitted without host
publication). Make its explicit security chain available in the separate management context;
verify both listeners with a running-server test rather than assuming a root-context bean
secures the child context. A request on the application port can never match a management
allowance. Host/forwarded-header spoofing must not affect this decision.

| Listener | Anonymous GET allowance |
| --- | --- |
| Application | /livez and /readyz only, plus local documentation paths when enabled |
| Management | /actuator/health, /actuator/health/liveness, /actuator/health/readiness, /actuator/prometheus |

Deny every other management path/method, including the actuator root discovery page, application
routes and docs. Keep management exposure limited to health,prometheus and show-details never
with component disclosure disabled. Application probes retain livenessState-only liveness and
readinessState+db readiness; management metrics and probes never require Keycloak. Wrong-listener
requests expose no content. On the application listener they produce 401 without credentials and
403 with valid credentials. The private management chain needs no bearer decoder and uses generic
403 without WWW-Authenticate for denied routes regardless of supplied credentials; explicitly
document/test this operational exception to application error selection. It has no business
operations to authenticate and does not process supplied bearer headers.

Application security is stateless, with request caching, form login, HTTP Basic, remember-me,
logout endpoints and session-based SecurityContext persistence disabled. CSRF is disabled only
on the stateless header-bearer application chain and the read-only private management chain;
no cookie-authenticated state-changing route exists. Public probes/docs on the application chain
still reject a supplied invalid bearer credential; anonymous means credentials are unnecessary,
not that supplied credentials are silently trusted or ignored.

Default configuration disables both springdoc.api-docs.enabled and springdoc.swagger-ui.enabled.
Only the explicitly selected local profile enables both. Deployment configurations must not
activate local; there is no generic security-disabled profile. When docs are off, do not leave a
permit rule for their paths. Enabled documentation paths are GET /swagger-ui.html,
/swagger-ui/**, /v3/api-docs, /v3/api-docs.yaml and /v3/api-docs/swagger-config; do not permit
unrelated webjars or arbitrary /v3/** content. These allowances are specific to the application
listener and never permit an API operation.

OpenAPI declares an HTTP bearer JWT scheme for API tools and an OAuth2 authorizationCode scheme
for Swagger. Each operation offers either scheme, with an empty OAuth permission-scope list.
Descriptions and 401/403 responses carry the Inventory permission requirements; openid is the
OIDC login scope, not inventory:manage or another RBAC permission. Configure Swagger clientId
inventory-swagger, usePkceWithAuthorizationCodeGrant=true, scopes=openid and
persistAuthorization=false. Derive authorization/token URLs from the exact configured issuer.
The local redirect is http://localhost:8080/swagger-ui/oauth2-redirect.html, matching Common's
registration. Use localhost as registered; port/origin changes require Common policy changes.
Swagger is a public client with no secret and keeps credentials in memory. Same-origin UI-to-API
calls need no API CORS allowance; Keycloak owns its exact allowed web origin for the token exchange.

## 8. Inventory verification strategy

Unit tests (no Spring context, Mockito as needed) cover claim validation, authority conversion,
caller/permission/target policy, scope/lock hashing, composite identity and sanitized rendering.
Existing direct service unit tests remain domain tests; integration tests prove real proxies.

Full HTTP tests use ephemeral RSA signing keys and a loopback-only JWKS HTTP fixture. Generate
JWTs in memory with the resolved JOSE library, never handwave signature validation with mockJwt.
Mock authentication may supplement permission matrices but cannot replace signed-token requests.
Configure an isolated issuer/JWKS pair explicitly; PostgreSQL remains Testcontainers 18.4 and
existing Kafka tests remain real-broker tests. No running Common stack, developer keys, saved
tokens or password grant is required. Adapt existing HTTP tests to pass intended credentials,
including error and DB-outage tests so they still reach the business path being tested.

Required negative tokens include wrong signature/issuer/audience/type/algorithm, string and array
audience edges, missing/blank/wrong-type required claims, missing/future iat, missing/expired exp,
future nbf, invalid role structures, absent local permissions, and query/form-only credentials.
Use deterministic injected clocks for the 30s boundary checks. Verify startup with unreachable
JWKS, fetch timeout configuration, warmed-cache outage success, unknown-kid outage failure, and
supported refresh when the fixture adds a signing key. Assert no blanket readiness failure.

Run the whole endpoint/caller matrix with positive and negative permissions, including accidental
machine overgrants, human reserve grants, unapproved azp, HEAD/OPTIONS, nonexistent serials,
wrong listeners, spoofed Host/forwarded headers, local-enabled and default-disabled docs, and
method denials that would otherwise hit the advice catch-all. Test real proxied calls without an
HTTP request; they must enforce the same business checks while Kafka cancellation and cleanup
continue with no human SecurityContext.

Migration tests apply V1–V4 with a legacy saved row, apply V5, verify unchanged outcome/expiry,
composite PK/index/constraints, schema ownership and other-service sentinels. Persistence tests
cover same UUID across two subjects, a renewed JWT and a second approved client for one subject,
legacy isolation, same-scope busy/mismatch, different-scope lock independence, equal-key cleanup,
auth denial before replay or persistence access, rollback, expiry and restart. Retain all matrix,
batch atomicity, history retention and arbitrary-staff-PUT regressions.

Update scripts/container-smoke-test.sh with an isolated JWKS fixture service in a test-only
Compose override. Use a dedicated smoke network/volume, fresh in-memory keys and tokens and a
controlled JWKS URL reachable by the application; do not run Keycloak in this service smoke.
The fixture/test runner image and dependencies are pinned during implementation, scoped to
tests, and never enter the Inventory runtime image. Preserve existing host prerequisites; use
Docker for any new test runner rather than installing host crypto tooling. Supply bearer headers
via private temporary files/configuration or the in-container runner, never token-valued command
arguments or printed curl commands. The script must keep existing image/bootstrap, persistence,
private metrics, DB outage/recovery and retained-volume checks, now with authenticated business
requests. Add customer/staff/machine positive and negative requests and two-subject same-key
isolation. Clean only the rentflow-inventory-smoke project's resources and generated credentials.

Implementation acceptance requires mvn -B -ntp clean verify with BUILD SUCCESS and no skip flags,
plus ./scripts/container-smoke-test.sh with Container smoke verification passed. If Spotless
fails, run mvn spotless:apply and repeat the full lifecycle. The specification-only request also
runs the required full Maven lifecycle but does not claim future security checks already exist.

## 9. Common handoff and rollout

No Common file or active realm is changed by this specification request. The following is a
separate Common-owned implementation dependency, not authorization to mutate a sibling project.

| Common artifact/policy | Phase 2 change |
| --- | --- |
| inventory-api client roles | Retain read/reserve; add list/manage/history |
| CUSTOMER composite | Add read/list only |
| STAFF composite | Add read/list/manage/history only |
| inventory-swagger default audience scope | Expose only read/list/manage/history through explicit role scope mappings |
| rentflow-pkce-check inventory-access optional scope | Expose the same human roles for the requested inventory-api audience |
| pricing-service | Keep exactly inventory-api audience and inventory:read |
| reservation-service | Keep exactly inventory-api audience and inventory:reserve |
| Verification expectations and walkthrough | Replace empty Inventory human composites with this explicit matrix; other APIs' policies stay unchanged |

Disable Full Scope Allowed as today, and ensure human scopes exclude reserve and machine scopes
exclude list/manage/history. Inventory:read in a Pricing token still does not allow listing.
Apply the desired policy using Common's existing plan/apply tooling; do not rotate secrets,
reset fixtures, alter subjects or reset the realm. Verify real browser PKCE customer/staff tokens
and Client Credentials machine tokens against secured Inventory in an isolated environment.
Direct staff setup, customer read/list, Pricing lookup and Reservation claim must pass; customer
administration/history, Pricing list/write/history, Reservation other targets/read/write/history,
and insufficient-permission replay must fail. Compare before/after state on denied writes.

Supply issuer/JWKS settings through existing environment injection or temporary test overrides
without changing Common Compose during the intermediate broken-caller period. Common's direct
Inventory setup/read calls must acquire appropriate test identities. Mark unchanged Pricing and
Reservation application workflows pending: they send no machine credentials until Phases 3 and
4. Never make Inventory anonymous, accept user forwarding, or fake final combined acceptance to
restore those flows. Defer affected Common Compose changes if its required complete suite cannot
pass; Phase 2 checks do not substitute for Combined container smoke verification passed. Phase 4
restores the final combined gate, and Phase 5 adds full integrated operational/rotation evidence.

Inventory implementation may report its own local acceptance separately from pending Common
interoperability and cross-service workflows. A release record must distinguish all three. For
deployed environments requiring continuity, coordinate authenticated callers before Inventory
enforcement; the accepted local rollout permits the temporary integration interruption. Phase 7
production hardening remains a deployment prerequisite, not a claim supplied by this feature.

## 10. Traceability and technical references

| Acceptance criteria | Design coverage |
| --- | --- |
| AC1.1, AC1.2, AC1.3, AC1.4, AC1.5 | §2, §6, §8 |
| AC2.1, AC2.2, AC2.3, AC2.4, AC2.5 | §3, §4, §7 |
| AC3.1, AC3.2, AC3.3, AC3.4, AC3.5 | §3, §4 |
| AC4.1, AC4.2, AC4.3, AC4.4, AC4.5, AC4.6 | §4, §5, §8 |
| AC5.1, AC5.2, AC5.3, AC5.4 | §7, §8 |
| AC6.1, AC6.2, AC6.3, AC6.4, AC6.5 | §6, §7, §8 |
| AC7.1, AC7.2, AC7.3, AC7.4 | §8, §9 |

Primary framework references checked while drafting:

- [Spring Security JWT resource server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html): supported decoding, issuer/audience checks, algorithm selection, JWKS and custom validation.
- [Spring method security](https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html): explicit enablement and proxy-based service authorization.
- [Spring Boot actuator endpoints](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html): explicit security when custom chains are present and separate probe exposure.
- [Springdoc properties](https://springdoc.org/properties.html): documentation enablement and Swagger OAuth configuration.

Online reference pages track newer patch releases; the resolved Boot 4.1.0/Security 7.1.0 and
Springdoc 3.0.3 artifacts govern implementation. Verify APIs/properties against those versions;
do not silently upgrade to match a current web example.
