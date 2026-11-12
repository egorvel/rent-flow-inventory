# Inventory security implementation acceptance

Date: 2026-11-08.
Scope: Original Inventory tasks T1–T4 and subsequent dated T5/T6/T7 amendments below; external Common task C1 pending.
The agreed requirements/design/task files remain the policy source of truth. Their original
specification review is [9 PASS / 0 WEAK / 0 FAIL](eval-reports/eval-report-2026-11-08-1810.md).
This record documents implementation evidence rather than changing those decisions.

## Delivered behavior

- Boot-managed Security 7.1.0 resource-server validation checks RS256 signatures, the exact
  issuer, inventory-api audience, JWT/Bearer types, required identity/time claims and 30s skew.
  Explicit JWKS configuration avoids discovery/startup coupling; supported fetching uses 2s
  connection/read timeouts and the decoder's supported key cache.
- HTTP and proxied service authorization enforce CUSTOMER/STAFF permissions, Pricing lookup-only
  access and Reservation all-RESERVED claims. Route/method, caller and permission checks run
  before business processing, and parsed target checks run before locks or saved outcomes.
- V5 introduces the verified issuer/subject scope, composite ledger identity and scoped advisory
  lock. Existing outcomes retain their expiry in an inaccessible legacy namespace. Cleanup joins
  both key components; unchanged batch/history/ledger transaction and replay behavior is retained.
- Separate application/management policies preserve minimal anonymous probes and private
  health/Prometheus. Local Swagger uses public-client PKCE without persistence; documentation is
  disabled by default. Denials share stable sanitized Problem Details and bounded diagnostics.
- README documents provider settings, permissions, private curl configuration, local PKCE,
  revocation limits, migration/cutover/rollback and pending cross-service rollout gates.

## Verification executed

| Check | Actual result |
| --- | --- |
| Java 25 `mvn -B -ntp clean verify` | BUILD SUCCESS at 22:38 Europe/Warsaw; 100 unit/architecture and 195 integration tests, zero failures/errors/skips; Spotless clean |
| `./scripts/container-smoke-test.sh` | Container smoke verification passed; signed-token security checks, runtime/bootstrap, private metrics, restart, database outage/recovery and retained-volume recreation passed |
| `INVENTORY_SMOKE_FAIL_AFTER_SETUP=1 ./scripts/container-smoke-test.sh` | Failed at the intentional assertion after security checks; cleanup verification passed |
| Smoke resource read-back after failure | No containers, volumes or networks with the rentflow-inventory-smoke project label remained |
| Shell/Python/whitespace checks | Shell syntax and Python parsing passed; final whitespace checks passed |

Maven uses ephemeral local JWKS/signing fixtures and real PostgreSQL/Kafka Testcontainers, never
the developer's Keycloak realm. The container suite builds its own pinned test signer/runner,
keeps private keys in memory and writes curl headers only to a private temporary file removed
at teardown. No signing fixture enters the application runtime image or publishes a host port.
No Common policy, active realm, developer database or unrelated Compose project was changed.

## Acceptance-criteria evidence map

Test names below identify assertions in `src/test/java/com/rentflow` (service tests live below
`service/`, configuration tests below `config/`). The existing regression suites were adapted to
signed staff credentials so their business/input/rollback assertions still reach those paths.

| Criteria | Implemented evidence |
| --- | --- |
| AC1.1, AC1.2 | SecurityIT.validatesRealSignaturesAlgorithmsTokenTypesAndAllRequiredClaims; InventorySecurityConfigTest.enforcesExactThirtySecondTimeBoundariesWithAnInjectedClock |
| AC1.3 | SecurityIT.rejectsMissingMalformedDuplicateAndNonHeaderCredentialsWithSafeChallenges |
| AC1.4 | InventoryAuthorityConverterTest.mapsOnlyOwnAllowlistedRolesAndKeepsScopesDistinct; SecurityIT caller matrix |
| AC1.5 | InventorySecurityConfigTest.createsDecoderWithoutNetworkAndUsesCachedKeysDuringOutageThenRefreshes; URL/configuration tests; actual probe/outage checks |
| AC2.1, AC2.2, AC2.4, AC2.5 | SecurityIT.enforcesHumanMachineAndRoutePermissionMatrixEvenWithAccidentalOvergrants; SecurityIT.bindsEachRouteToItsSpecificCapabilityBeforeBodyValidation; authenticated InventoryIT regressions and explicit unknown-route/method denials |
| AC2.3 | InventoryIT create/replace tests retain defined-status assignment without lifecycle history |
| AC3.1, AC3.2, AC3.5 | SecurityIT caller matrix; signed container smoke customer/staff/Pricing/Reservation checks |
| AC3.3, AC3.4 | SecurityIT.rejectsForbiddenReservationTargetsBeforeReplayLocksOrItemMutation; existing aggregate lifecycle and missing-item regressions |
| AC4.1 | SecurityIT.isolatesEqualKeysAcrossSubjectsAndRechecksPermissionsBeforeSavedResults; IdempotencyIT.heldExecutionLockDoesNotBlockAnotherSubjectUsingTheSameUuid |
| AC4.2, AC4.3 | SecurityIT subject/cross-client/reduced-permission replay tests; IdempotentStatusTransitionServiceTest exact scope/client-stability vector; IdempotencyIT existing replay, mismatch, busy, expiry, restart and header assertions |
| AC4.4 | SecurityMigrationIT.upgradesLegacyLedgerWithoutLosingOutcomesOrOtherServiceObjects; SecurityIT legacy no-replay check |
| AC4.5 | SecurityIT.cleansOnlyExpiredCompositeRowsAndNeverReplaysLegacyResults; existing IdempotencyIT chunk/locking/concurrent cleanup checks |
| AC4.6 | Existing IdempotencyIT/InventoryIT injected persistence-failure rollback and safe-retry assertions |
| AC5.1, AC5.2, AC5.3 | SecurityIT.isolatesRealManagementListenerAndDisablesDocs; ObservabilityIT listener/probe checks; container DB outage/recovery and private metric checks |
| AC5.5, AC5.6 | SecurityIT.publishesAccuratePublicMetadataUsingRequestUrlsAndNativeResourcePaths; metadata credential/outage and non-GET tests; isolated container metadata checks |
| AC5.4 | SecurityIT missing/malformed/Basic/query/form/OPTIONS denials and no-session-cookie response assertions; existing observability cookie/baggage checks |
| AC6.1, AC6.2 | OpenApiIT.exposesPkceConfigurationWithoutSecretsOrPersistentAuthorization; SecurityIT default-disabled real-listener docs checks |
| AC6.3 | SecurityIT generic authentication/target denials and proxied-service checks; InventoryIT stable denied-route/method errors |
| AC6.4 | OpenApiIT security scheme/response/permission descriptions; README authorization, PKCE, idempotency and cutover sections |
| AC6.5 | SecurityIT.deniesDoNotLeakCredentialsOrBodiesAndUseOnlyBoundedMetricLabels; ObservabilityIT.securityDenialTracesExcludeCredentialsBodiesAndAuthorizationDetails |
| AC7.1 | Full Maven lifecycle and isolated signed-token/proxy/database/real-broker suites above |
| AC7.2 | Full signed-fixture container smoke and intentional-failure resource-cleanup read-back |
| AC7.3 | Pending C1: real Common provider grants and API interoperability require the separately scoped Common change |
| AC7.4 | README rollout/cutover guide and external gates below |

## Integration details confirmed during implementation

The original implementation removed OAuth2ProtectedResourceMetadataFilter to enforce its initial
endpoint allowlist. The 2026-11-11 user-approved amendment supersedes that decision: the application
now retains all Spring filters and customizes public RFC 9728 metadata through the supported API.
The resolved Spring Security 7.1.0 configurer has no dedicated metadata disable switch, but no
such workaround is needed under the amended contract. No custom JWT authentication filter was
introduced.

Framework Security observations add authorization-detail fields and change business span
parentage. SecurityObservationSettings.noObservations() disables those framework detail spans;
the bounded Inventory denial counter/diagnostic and existing HTTP/business/JDBC observations
remain active. Existing SQL-error/trace-parent assertions and the new denial leakage test pass.

Prometheus is disabled by default in Spring test observability configuration; the real-listener
security test explicitly enables metrics so it verifies the actual exposed endpoint. Composite
registry counters have the two security labels, and exported children can additionally carry the
existing service/environment common tags; assertions reject every other label.

## JWT validator refactor — 2026-11-09

InventoryJwtValidator now composes Spring's JwtIssuerValidator, JwtAudienceValidator,
JwtTimestampValidator, JwtTypeValidator and JwtClaimValidator through
DelegatingOAuth2TokenValidator. Claim-shape guards, scalar-audience compatibility and Keycloak
role-structure validation are retained. Timestamp validation still uses the injected clock,
required expiry, optional nbf and exactly 30s skew; the iat predicate rejects only excessively
future issuance. Decoder-enforced RS256 signatures and token-model exp/iat ordering are no
longer duplicated in the claim validator.

The user approved one contract relaxation: the required JOSE header typ matches JWT ignoring
case. Absent/blank/other header types remain invalid, and payload typ stays exactly Bearer.
Requirements AC1.6, design section 2, T1's regression-sensitive DoD and the README record this
decision. Common's canonical emitted token shape remains unchanged.

Verification for this refactor:

- `mvn -B -ntp clean verify`: BUILD SUCCESS on 2026-11-09, 104 unit/architecture tests and
  196 integration tests, zero failures/errors/skips, Spotless clean.
- `./scripts/container-smoke-test.sh`: Container smoke verification passed, including signed
  token/caller/replay assertions, database outage/recovery and retained-volume recreation.
- InventoryJwtValidatorTest exercises header cases/omission, exact payload type, issuer/audience
  representations, strict claim shapes, required claims, older valid tokens and all three time
  boundaries. SecurityIT.acceptsCaseInsensitiveRequiredJwtHeaderAndPreservesPayloadTypeAndOlderTokenValidation
  proves those header/payload/older-token rules using real signed HTTP credentials; existing
  signature/algorithm and generic error assertions remain active.
- [Updated spec evaluation](eval-reports/eval-report-2026-11-09-2131.md): 9 PASS / 0 WEAK / 0 FAIL
  across all 35 criteria. The prior evaluation remains a historical record.

## Initial public protected-resource metadata amendment — 2026-11-11 (historical)

The following records the initially verified fixed-origin implementation. Its origin policy and
associated validation tests are superseded by the user's native-URL simplification below; the
earlier verification results remain historical evidence.

T5 implements the five metadata decisions explicitly confirmed by the user. The application
retains Spring's metadata filter and configures its response with the supported customizer:
resource uses the normalized INVENTORY_PUBLIC_ORIGIN plus Spring's native raw resource path,
authorization_servers contains only the exact trusted issuer, bearer_methods_supported is
["header"], and tls_client_certificate_bound_access_tokens is false. No OAuth scope list or
internal/credential data is published. Existing bearer challenge headers are unchanged.

Root, trailing-slash and path-suffixed metadata GETs are intentionally public before bearer
authentication, including malformed/duplicate credentials and provider outages. Management
discovery remains denied; non-GET metadata requests, unrelated well-known paths and business
operations retain their existing authentication/authorization rules. Public origin configuration
requires HTTPS outside local/test and rejects credentials, paths, queries, fragments and invalid
ports. Local YAML defaults to localhost:8080; Compose respects its mapped port and explicit
override. The configured origin is independent of inventory-api token audience validation.

Verification executed for this amendment:

- Java 25 `mvn -B -ntp clean verify`: BUILD SUCCESS on 2026-11-11 at 16:16 Europe/Warsaw;
  106 unit/architecture tests and 200 integration tests, zero failures/errors/skips, Spotless clean.
- `SecurityIT.publishesAccuratePublicMetadataWithNativeResourcePathsAndNoRequestOriginTrust`
  asserts exact document fields, root/trailing/nested/encoded resource paths, query stripping,
  response type, no challenge/cookie, and resistance to Host/Forwarded/X-Forwarded-* spoofing.
- `SecurityIT.publicMetadataDoesNotAuthenticateCredentialsOrContactUnavailableProvider`
  verifies missing/valid/malformed/duplicate credentials with the JWKS fixture unavailable and
  proves the request count to that fixture remains unchanged. Default-disabled docs remain
  independently denied by `isolatesRealManagementListenerAndDisablesDocs`, which also retains
  management metadata denial. `metadataNonGetMethodsRetainNormalAuthenticationAndDenyRules`
  verifies HEAD/OPTIONS/POST denials, while all existing signed business-route assertions pass.
- `InventorySecurityConfigTest` exercises explicit/normalized HTTPS origins, local/test HTTP
  allowance, missing configuration and malformed/non-origin/invalid-port inputs. Compose
  configuration checks passed for localhost defaults, a different mapped port, and an explicit
  HTTPS override. Shell syntax and Python parsing passed.
- `./scripts/container-smoke-test.sh`: Container smoke verification passed, including exact
  root/path-suffixed public metadata, signed caller/replay checks, private metrics, database
  outage/recovery, restart and retained-volume recreation.
- [Metadata spec review](eval-reports/eval-report-2026-11-11-1618.md): 9 PASS / 0 WEAK / 0 FAIL
  across all 37 criteria. The prior evaluations and verification runs remain historical.

## Native resource URL simplification — 2026-11-11

The user's explicit follow-up replaces the initial fixed-origin policy with Spring's default
resource URL generation. The customizer now only supplies the exact trusted issuer and
tls_client_certificate_bound_access_tokens=false. Spring retains header bearer presentation and
derives resource from the effective request URL, preserving native path suffixes/encoding and
removing queries. No public-origin environment variable/property, URL rewriting, startup
validator, Compose override or test-fixture setting remains in the active configuration.

AC5.6, resolved question 9, design section 7.1 and T5 describe this final contract. Standard
trusted forwarding and public HTTPS URL handling belong to deployment; this change does not
enable global forwarding or change the actual-port-based security-chain selection. Metadata
GET credential/provider independence, private-management denial, business enforcement and
existing WWW-Authenticate behavior remain unchanged. The previous two origin-validator tests
were removed because their feature was explicitly removed; metadata HTTP assertions now check
request-derived URLs rather than the superseded fixed-origin guarantee.

Verification:

- Java 25 `mvn -B -ntp clean verify`: BUILD SUCCESS on 2026-11-11 at 16:45 Europe/Warsaw;
  104 unit/architecture and 200 integration tests, zero failures/errors/skips, Spotless clean.
- `SecurityIT.publishesAccuratePublicMetadataUsingRequestUrlsAndNativeResourcePaths` checks
  exactly four fields for root, trailing-slash, nested and encoded suffixes using the real
  application listener's origin, and HTTP/HTTPS effective request hosts/ports. Query stripping,
  content type and absence of challenges/cookies remain asserted. The application starts
  without a public-origin fixture setting. Credential/outage and non-GET/listener tests pass.
- Both base and smoke Compose configurations validate without the removed origin setting;
  Python parsing and shell syntax checks pass. The smoke metadata expectation uses its actual
  request origin, http://inventory:8080, while retaining exact issuer/header/certificate fields.
- `./scripts/container-smoke-test.sh`: Container smoke verification passed, including native
  root/path-suffixed metadata URLs, signed caller/replay checks, restart, database outage/recovery
  and retained-volume recreation. Read-back confirms no smoke-project containers, volumes or
  networks remain after teardown.
- [Simplified metadata spec review](eval-reports/eval-report-2026-11-11-1644.md):
  9 PASS / 0 WEAK / 0 FAIL across all 37 criteria.

## Authorization policy and typed route bindings — 2026-11-11

T6 implements the explicitly requested refactor without changing permissions, caller classes,
token validation, HTTP errors, metadata, idempotency identities or persistence. The former
InventoryAuthorizationService is now the plain config.InventoryAuthorizationPolicy, registered
as inventoryAuthorizationPolicy in the unconditional InventoryMethodSecurityConfig. Service
annotations reference that bean by name, including both status-transition boundaries. The policy
contains the unchanged capability predicates and no HTTP dispatcher or caller-scope hashing.

applicationSecurity uses typed per-route authorization callbacks for list/history/read/manage
and request-transition capabilities. Spring's matchers own route selection, retaining the exact
method/path allowlist and denyAll fallback. callerScope is a package-private static helper in
IdempotentStatusTransitionService with identical authenticated-JWT guard, length-prefixed hash,
namespace, issuer/subject inputs and p1: prefix. Existing ArchitectureTest rules are unchanged:
no service-to-config or config-to-service Java dependency or Security dependency in util/domain
was introduced, and no new package, dependency or policy registry was added.

Verification:

- Java 25 `mvn -B -ntp clean verify`: BUILD SUCCESS on 2026-11-11 at 19:15 Europe/Warsaw;
  106 unit/architecture and 201 integration tests, zero failures/errors/skips, Spotless clean.
- `SecurityIT.bindsEachRouteToItsSpecificCapabilityBeforeBodyValidation` uses individual
  read/list/history/manage grants to prove route mapping and 403 before malformed-body parsing
  for denied writes. Existing machine overgrant, Reservation target, proxied-method, replay,
  HEAD/OPTIONS, unknown-route, challenge and listener/metadata assertions all pass.
- `IdempotentStatusTransitionServiceTest.callerScopeKeepsItsExactNamespaceAndIdentityHashAcrossApprovedClients`
  asserts the fixed hash vector across both approved interactive clients;
  `callerScopeRejectsUnauthenticatedAndMissingIdentity` retains the identity guard. Existing
  IdempotencyIT scope/lock/replay assertions use the moved helper, including the non-web
  application restart that invokes the secured transition service and replays the saved outcome.
- Source comparison confirms all 15 capability/context/helper methods retain their original
  logic. No migration or existing saved caller scope was rewritten.
- `./scripts/container-smoke-test.sh`: Container smoke verification passed, including signed
  caller/permission and replay-isolation checks, native metadata, private metrics, persistence
  across restart/recreation and database outage/recovery. Read-back confirms teardown removed
  all smoke-project containers, volumes and networks.
- [Authorization refactor spec review](eval-reports/eval-report-2026-11-11-1913.md):
  9 PASS / 0 WEAK / 0 FAIL across all 37 criteria.

## Annotation-owned security documentation — 2026-11-12

T7 moves each business operation's permission/caller description into its existing controller
@Operation annotation. OpenApiConfig no longer switches on operationId, assumes a default manage
permission or rewrites descriptions. Both authentication schemes and their operation alternatives,
empty permission-scope lists, and common 401/403 ProblemResponse/WWW-Authenticate documentation
remain centralized in inventorySecurityDocumentation. No runtime authorization or filter-chain
configuration changes in this refactor.

All seven descriptions retain their specific caller/permission restrictions. POST/PUT retain
arbitrary defined status assignment; PATCH retains both authorization branches, all-RESERVED
targets, seven-day replay, verified issuer/subject key scope and authorization before replay.
The Idempotency-Key parameter's stale global-scope wording now describes that existing identity
scope. DELETE no longer inherits an irrelevant create/replace status-assignment sentence.

Verification:

- Java 25 `mvn -B -ntp clean verify`: BUILD SUCCESS on 2026-11-12 at 19:32 Europe/Warsaw;
  106 unit/architecture and 203 integration tests, zero failures/errors/skips, Spotless clean.
- `OpenApiIT.documentsEachOperationsPermissionsCallersAndReplayScope` verifies each method/path
  description, POST/PUT status assignment, both PATCH authorization branches, target restrictions,
  retained business text, scoped key parameter and role/scope distinction.
- `OpenApiIT.documentsSharedAuthenticationAlternativesAndSecurityResponses` verifies scheme
  definitions, issuer-derived token URL, openid login scope, both alternatives with empty scope
  lists, and exact common response descriptions, ProblemResponse references and header types.
  Existing operation IDs, business schemas, PKCE and runtime authorization assertions still pass.
- The first full run exposed an existing OpenAPI 3.1 header-schema defect: the generic Schema's
  type-only declaration serialized as an empty schema. A standalone reproduction with the
  resolved Swagger serializer confirmed it. Populate the 3.1 types collection as well so the
  centralized WWW-Authenticate schema actually advertises type=string; retain the strict test.
  Formatting was applied and the complete unskipped lifecycle passed on rerun.
- [Operation documentation spec review](eval-reports/eval-report-2026-11-12-1926.md):
  9 PASS / 0 WEAK / 0 FAIL across all 37 criteria.
- Container/runtime configuration and smoke scripts are unchanged. The 2026-11-11 container
  acceptance above remains historical evidence; no new container-smoke result is claimed here.

## External gates and deployment limits

C1 has not been run: Common must add Inventory list/manage/history roles, provision the approved
CUSTOMER/STAFF composites and human scope mappings, and verify real browser PKCE and machine
tokens against the secured service. Inventory local fixture acceptance does not establish that
provider interoperability or authorize changes to the running realm.

Pricing and Reservation applications still need outbound Client Credentials in Phases 3 and 4.
Their calls to protected Inventory operations remain blocked until then; Inventory has no
temporary authentication bypass. Common's full combined-smoke gate is retained, with affected
Compose changes deferred if that complete gate cannot pass. Phase 5 operational/key-rotation
evidence and Phase 7 production hardening remain separate obligations.

V5 changes the ledger primary key. Stop/drain old Inventory instances before upgrading; do not
mix old binaries with the composite-key schema. Legacy outcomes are not attributable to new
principals: use fresh keys and reconcile uncertain old operations. Rollback requires coordinated
schema/data restoration and an appropriate artifact, accounting for writes after the backup.
