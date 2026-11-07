# Main audit and release integration

The active integration is based on merged main, not on the release branch's older auth defaults.
Research changes were ported from release commit `808768c`; the wrapper was taken from `043021e`
and verified against the upstream 9.3.1 JAR and release distribution digest.

## Audit disposition

1. Missing initial token `scope` inherits requested scopes (RFC 6749 §5.1). Refresh inherits the
   previous grant. An explicit empty, narrowed, null or malformed field never uses that fallback.
2. Account availability controls both setup copy resources and the sign-in card. Disabled builds
   describe only the local model and gateway routes.
3. JWKS cooldown starts after a successful document fetch, not before network I/O. Failed transport,
   service and malformed-document responses permit retry; successful fetches still throttle unknown kids.
4. **Do not normalize token issuers.** OIDC Core §3.1.3.7 requires exact issuer equality. A configured
   issuer ending in `/` works only with the same identifier in the signed token. Configure the trusted
   issuer correctly rather than accepting another identifier. This matches mature reference validation.
5. Callback headers use bounded bulk refills. Timeout updates occur per refill; the 8 KiB total budget
   and absolute three-second/sign-in deadline remain shared across all lines.
6. Both `127.0.0.1` and `localhost` accept bare authorities for existing client compatibility, or the
   actual bound port. Foreign names, lookalikes, IPv6, userinfo, duplicate Hosts and wrong ports fail.
   Binding remains IPv4 loopback only. State filtering and outcome-held browser responses are unchanged.
7. Official Gradle 9.3.1 wrapper checked in, with distribution checksum enforcement. JAR SHA-256:
   `b3a875ddc1f044746e1b1a55f645584505f4a10438c1afea9f15e92a7c42ec13`.
   Distribution SHA-256: `b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06`.
8. Billing guard scans app/core sources and dependency declarations, not generated build directories.
   One exact defensive constant declaration is allowed; the safety file itself is not exempt.
   Missing/unreadable scan roots fail closed. Isolated shell fixtures exercise these boundaries.
9. Application configuration/low-memory callbacks call super; corresponding baseline entries removed.

## Release integration boundaries

- Control ablations disable learned skills as well as replay/shortcuts; compact observations preserve
  their IDs. Research counters/CSV retain legacy-row support and separate plans, members and bitmaps.
- Event sequence is sampled at capture start, so events arriving during capture are not consumed early.
- Current `ActionBurstRunner`, deferred-plan handling, per-member safety and verification remain intact.
- Numeric labels and row suffixes are meaningful identities. Do not strip them or select a nearby
  widget when the original target disappears. Ambiguous targets close a burst and request replanning.
- Replay metadata survives disk round trips. Lookup is not a verified hit; successful verification
  increments hits, and repeated divergence demotes/evicts an entry. Sensitive fields do not seed replay.
- Existing provider endpoints, dynamic-registration consent, feature-off defaults, and main's auth
  ownership/identity checks are not replaced with release-branch versions.

## Reproduction

With Java 17 and the Android SDK configured:

```sh
./gradlew :core-agent:test :app:testDebugUnitTest :app:lintDebug --console=plain
bash scripts/ci-preflight.sh
```

The catalog guard needs outbound access to its configured model host. Network failures remain failures,
not evidence of valid URLs. See the PR's CI checks for provisioned-runner validation.
