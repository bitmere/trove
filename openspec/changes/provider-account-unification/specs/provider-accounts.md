# provider-account-unification

## Requirements

### Requirement: One generic provider-account flow replaces six copies
A single generic validate/save/test/sign-out flow, parameterized by a
per-service descriptor, SHALL replace the six per-provider copies in
the repository, ViewModel, and Settings screen, with no behaviour
change for any provider.

#### Scenario: Each provider still signs in, tests, and signs out
- WHEN the user tests and saves a key for any of the six providers
- THEN validation, account storage, feedback, and the account-changed
  signal behave exactly as before.

#### Scenario: TorBox sign-out no longer runs on Main
- WHEN the user signs out of TorBox
- THEN the sign-out runs on the shared dispatched path like the other
  five providers.

#### Scenario: Per-provider strings survive
- WHEN a provider has distinct help or note text
- THEN that text still appears in its section, sourced from the
  descriptor.

### Requirement: Download references are kind-aware
The provider interface SHALL carry a kind-aware download reference, so
TorBox ids that collide across kinds route to the right endpoint without
`is TorBoxProvider` branches.

#### Scenario: Same id in two kinds deletes the right one
- WHEN two TorBox downloads share a numeric id in different kinds
- THEN deleting one deletes exactly that kind's download.

#### Scenario: Deleting one torrent does not re-fetch the whole library
- WHEN the user deletes a torrent
- THEN the provider resolves its kind from the reference instead of
  fetching up to 3 full 1,000-item lists.

### Requirement: Hoster discovery is an interface capability
`hosterDomains()` SHALL be a default interface method (empty-set
fallback), so share classification needs no per-provider type switch.

#### Scenario: A provider without hosters classifies as unsupported
- WHEN a provider returns the empty hoster set
- THEN a web link for it classifies as an unsupported hoster, as today.

### Requirement: Shared helpers are adopted one provider at a time
The shared `mapError` and `opt*` JSON accessors SHALL be the canonical
set, adopted one provider at a time with that provider's tests green,
and the six `call()` bodies SHALL NOT be merged into a base class.

#### Scenario: A migrated provider keeps its error behaviour
- WHEN a provider is migrated to the shared helpers
- THEN its error mapping, cancellation, and credential redaction behave
  exactly as before.
