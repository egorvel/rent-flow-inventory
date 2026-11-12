# Inventory HTTP security requirements

Status: Specification agreed on 2026-11-08; implementation pending.

## Context

Inventory is Phase 2 of the [RentFlow security plan](../../../SECURITY_PLAN.md).
[Common's security contract](../../../rent-flow-common/SECURITY_SPEC.md) supplies Keycloak,
issuer/audience conventions, interactive clients and two distinct machine identities. Inventory
currently has no HTTP authentication, bans Spring Security in Maven and ArchUnit, and stores
status-change results under endpoint-global idempotency keys.

This feature makes Inventory a stateless JWT resource server. Customers can browse the catalogue;
staff can administer it. Pricing can retrieve a single item, and Reservation can claim available
items without acquiring general inventory powers. Authorization must precede access to saved
results. Catalogue data has no customer ownership: customer identity mapping belongs to Reservation.

This specification supersedes earlier Inventory specs only where they require unauthenticated
HTTP access, prohibit Spring Security, or describe globally scoped status-change keys. Existing
payloads, lifecycle rules, atomicity, history, Kafka behavior and cleanup guarantees remain the
functional baseline.

## User stories with acceptance criteria

### US1 — Validate API credentials

As an API operator, I want only access tokens issued for this API to authenticate requests.

- **AC1.1 (Event-driven).** When a protected request supplies a JWT satisfying the complete token contract in `design.md` §2, the service shall authenticate it before evaluating its permissions.
- **AC1.2 (Unwanted).** If an application request outside the public metadata GET endpoints supplies credentials that are malformed, tampered with, expired beyond skew, premature beyond skew, signed with a disallowed algorithm, or violate required issuer/audience/type/claim rules, or a protected application request supplies no credentials, then the service shall return the generic authentication response in `design.md` §6 without invoking business or replay processing.
- **AC1.3 (Unwanted).** If a request supplies a bearer token through a query parameter or form field instead of the Authorization header, then the service shall treat that token as absent.
- **AC1.4 (Event-driven).** When a token contains realm roles, scopes, other APIs' roles or unknown Inventory roles, the service shall grant only the allowlisted local permissions extracted according to `design.md` §2.
- **AC1.5 (State-driven).** While Keycloak is unavailable, the service shall start without contacting Keycloak, preserve existing probe semantics, accept otherwise valid tokens whose signing keys remain in its cache, and reject tokens whose keys cannot be resolved within the configured fetch limits.
- **AC1.6 (Event-driven).** When an otherwise valid token supplies a JOSE typ header equal to JWT ignoring case, the service shall accept that header type while rejecting missing, blank or other header types and continuing to require the payload typ to equal Bearer exactly.

### US2 — Browse and administer the catalogue

As a customer or staff member, I want access appropriate to my Inventory responsibilities.

- **AC2.1 (Event-driven).** When an approved human client supplies the permissions assigned to CUSTOMER, the service shall allow single-item and collection reads while denying catalogue writes, history and status changes.
- **AC2.2 (Event-driven).** When an approved human client supplies the permissions assigned to STAFF, the service shall allow catalogue create/read/replace/delete, collection reads, history reads and the existing lifecycle transitions.
- **AC2.3 (Event-driven).** When authorized staff create or replace an item, the service shall preserve the existing ability to assign any defined status without adding transition history for that operation.
- **AC2.4 (Unwanted).** If a valid token's caller class or required permission does not authorize an operation, then the service shall return the generic forbidden response before item lookup or mutation.
- **AC2.5 (Unwanted).** If a request uses an HTTP method or route outside the explicit application allowlist, then the service shall deny it without reaching a business method, including implicit HEAD access and unconfigured OPTIONS access.

### US3 — Limit machine callers

As an Inventory owner, I want service accounts to have only their agreed downstream capabilities.

- **AC3.1 (Event-driven).** When pricing-service presents a valid inventory:read token, the service shall allow only the single-item GET operation and preserve its existing response and not-found behavior.
- **AC3.2 (Event-driven).** When reservation-service presents a valid inventory:reserve token and a valid batch targeting only RESERVED, the service shall process the batch through the existing atomic, idempotent AVAILABLE-to-RESERVED claim behavior.
- **AC3.3 (Unwanted).** If an otherwise valid Reservation batch contains any defined target other than RESERVED, then the service shall reject the entire request with 403 before acquiring execution locks, accessing saved outcomes or changing any row.
- **AC3.4 (Unwanted).** If an authorized Reservation claim encounters an existing item outside AVAILABLE, then the service shall return the existing lifecycle conflict outcome without changing any item or history in the batch.
- **AC3.5 (Unwanted).** If a machine caller requests an operation outside its caller-specific allowlist, or an interactive/unapproved client attempts to use inventory:reserve as its authorization, then the service shall deny that operation regardless of unrelated Inventory permissions; an approved human with inventory:manage retains the staff PATCH capability.

### US4 — Isolate idempotency and protect replay

As an authenticated caller, I want retries to return only results associated with my identity.

- **AC4.1 (Event-driven).** When two different verified issuer/subject identities use the same UUID key, the service shall isolate their stored outcomes and execution locks as specified in `design.md` §5.
- **AC4.2 (Event-driven).** When the same verified identity retries with a renewed token or through another approved client, the service shall reuse its scope and preserve the existing replay, payload-mismatch, busy, expiry and response-header contracts subject to current authorization.
- **AC4.3 (Unwanted).** If the current request lacks its required caller permission or requests a forbidden target, then the service shall deny replay before reading the stored result, including after a permission change reflected in a new token.
- **AC4.4 (Event-driven).** When the security migration is applied to a database with ownerless saved results, the service shall retain those results exclusively in the legacy namespace, preserve their expiry, and make them unavailable for authenticated replay.
- **AC4.5 (Event-driven).** When cleanup processes scoped and legacy records, the service shall delete only the selected expired composite keys within the existing chunk/time/locking limits and preserve unexpired results, items and history.
- **AC4.6 (Unwanted).** If a batch, history write or ledger write fails, then the service shall retain the existing transaction rollback guarantees and allow a safe retry within the authenticated scope.

### US5 — Preserve operational access

As an operator, I want useful health checks and scraping without exposing management on the API listener.

- **AC5.1 (Event-driven).** When GET /livez or GET /readyz is called without credentials, the service shall expose minimal health status with the existing liveness/readiness dependency semantics.
- **AC5.2 (Event-driven).** When the separate loopback/private management listener receives GET requests for the approved health or Prometheus paths, the service shall allow anonymous access and expose no health component details.
- **AC5.3 (Unwanted).** If an application-listener request targets management paths, or a management-listener request targets business, documentation, metadata discovery or unapproved management paths, then the service shall deny it without exposing the targeted content.
- **AC5.4 (Ubiquitous).** The service shall use stateless header-bearer authentication without login redirects, security sessions, cookie authentication, HTTP Basic, form login or unconfigured cross-origin access.
- **AC5.5 (Event-driven).** When the application listener receives a GET request for Spring's root or path-suffixed protected-resource metadata endpoint, the service shall return only the public metadata defined in `design.md` §7.1 without requiring or processing bearer credentials, independently of documentation enablement and provider availability.
- **AC5.6 (Event-driven).** When a metadata GET is processed, the service shall let Spring derive the resource identifier from the effective request URL, preserving native path suffixes and encoding while stripping query parameters, without requiring separate public-origin configuration, as specified in `design.md` §7.1.

### US6 — Explore protected APIs and understand denials

As a local developer, I want PKCE-enabled Swagger and predictable safe security errors.

- **AC6.1 (Optional).** Where local documentation is enabled, the service shall expose the approved Swagger/OpenAPI paths anonymously and configure inventory-swagger Authorization Code with S256 PKCE, exact registered callback, no client secret and disabled authorization persistence.
- **AC6.2 (Unwanted).** If local documentation is not explicitly enabled by the local configuration, then the service shall disable Swagger UI and OpenAPI generation and deny access to their paths.
- **AC6.3 (Event-driven).** When an API request fails authentication or authorization, the service shall return the corresponding stable 401/403 Problem Details and WWW-Authenticate behavior specified in `design.md` §6, including denials raised by service-method security.
- **AC6.4 (Ubiquitous).** The service shall document required credentials, caller-specific permissions, security responses and scoped replay behavior in OpenAPI and the service README without presenting permission names as OAuth scopes.
- **AC6.5 (Event-driven).** When the service records authentication or authorization denials, the service shall use bounded categories and exclude bearer credentials, token claim dumps, passwords, client secrets, raw request bodies and identity/key labels from logs, traces and security metric labels.

### US7 — Verify and roll out without bypasses

As a maintainer, I want reproducible evidence and an explicit boundary between Inventory adoption and integrated rollout.

- **AC7.1 (Event-driven).** When Inventory verification runs, the verification suite shall exercise real signed-token validation, endpoint and proxied-service authorization, scoped PostgreSQL persistence and existing business regressions without depending on a developer's Keycloak realm.
- **AC7.2 (Event-driven).** When the Inventory container smoke suite runs, the suite shall provide an isolated signing fixture, verify authenticated allow/deny paths and replay isolation, retain existing runtime/persistence/outage checks, and clean up its own resources.
- **AC7.3 (Event-driven).** When Common applies this service's approved human policy, its Phase 2 checks shall verify real customer/staff/machine tokens against Inventory using the grants and negative cases in `design.md` §9.
- **AC7.4 (State-driven).** While Pricing and Reservation applications have not adopted outbound Client Credentials, the rollout documentation shall mark their Inventory workflows pending and retain all Inventory enforcement and Common's final combined-smoke acceptance gate.

## Out of scope

- OAuth client/token acquisition in Inventory, a shared Java security module, token forwarding,
  token exchange, login/password endpoints, frontend/BFF sessions or customer ownership mapping.
- Changing catalogue PUT semantics, adding inspection/maintenance evidence, introducing a dedicated
  Reservation claim route, adding actor fields to history or building a durable security audit store.
- Kafka authentication/ACLs, database/Redis hardening, TLS/proxy/production identity operations,
  introspection/immediate revocation and full integrated key/secret rotation acceptance (later phases).

## Resolved questions

1. **Catalogue reads.** Resolution: authenticated CUSTOMER and STAFF can retrieve items and list the catalogue; Pricing retains only single-item GET. See `design.md` §3.
2. **Staff administration.** Resolution: full existing administration, including arbitrary defined status on create/replace and unchanged lifecycle rules on PATCH. See `design.md` §3 and §4.
3. **Reservation route.** Resolution: restrict existing PATCH /api/v1/inventory/status by verified caller and batch targets; no new public operation. See `design.md` §4.
4. **Replay identity.** Resolution: scope keys by verified (issuer, subject), stable across token renewal and approved interactive clients. See `design.md` §5.
5. **Legacy replay.** Resolution: retain ownerless rows for normal cleanup in an inaccessible legacy namespace; callers use fresh keys at cutover. See `design.md` §5.
6. **Documentation.** Resolution: anonymous local documentation with PKCE for API calls; disable generation and access outside explicitly enabled local configuration. See `design.md` §7.
7. **Management.** Resolution: minimal application probes and anonymous approved health/Prometheus endpoints only on the separate loopback/private management listener. See `design.md` §7.
8. **JOSE header type (2026-11-09).** Resolution: require JWT with case-insensitive comparison; missing/blank/other header types remain invalid, and payload typ remains exactly Bearer. This is the user's explicit exception to the previous case-sensitive header check. See `design.md` §2.
9. **Public resource metadata (2026-11-11, simplified by explicit user request).** Resolution: retain Spring's RFC 9728 filter and native request-derived resource URLs, including path-suffixed discovery. Customize only the trusted issuer and certificate-bound-token support=false; retain header bearer presentation, omitted OAuth scopes and existing WWW-Authenticate headers. Remove the previous public-origin setting, validation and URL rewriting. Proxy trust and effective external URL handling use Spring Boot's deployment configuration. Metadata GETs deliberately precede bearer validation; business routes retain all checks. See `design.md` §7.1 and `tasks.md` T5.
10. **Authorization structure (2026-11-11).** Resolution: move capability decisions to InventoryAuthorizationPolicy in config, register it unconditionally for HTTP and method security, and bind typed per-route checks directly in applicationSecurity. Remove the generic HTTP dispatcher; keep callerScope in IdempotentStatusTransitionService with the identical hash contract. Preserve all caller/permission/target rules, early HTTP denials, method/replay protection and existing dependency boundaries. This refactor adds no new behavioral acceptance criteria. See `design.md` §3.1 and §5 and `tasks.md` T6.
11. **Operation security documentation (2026-11-12).** Resolution: place permission and caller descriptions in each controller method's existing @Operation annotation, including PATCH target restrictions and authorization before scoped replay. Remove operationId-based description selection and its default permission assumption. Keep authentication schemes, both scheme alternatives and common 401/403 documentation centralized. These annotations describe the existing policy; runtime authorization remains unchanged. See `design.md` §7 and §8 and `tasks.md` T7.
