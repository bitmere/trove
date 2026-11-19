# torbox-paging

## Requirements

### Requirement: A TorBox kind loads all of its items, not just the first 1000
`listKind` SHALL page through a kind with `offset`/`limit` until a short
page, deduping by `(kind, id)`, so no item is silently invisible.

#### Scenario: A kind with more than 1000 items loads them all
- WHEN a kind has 2,500 items
- THEN the list contains all 2,500 items after the load finishes.

#### Scenario: A short page ends the loop
- WHEN a page returns fewer items than requested
- THEN no further page is fetched for that kind.

#### Scenario: Duplicate rows across pages are removed
- WHEN an item appears on two pages (offset shift between fetches)
- THEN it appears once, keyed by `(kind, id)`.

#### Scenario: The cap is visible until paging lands
- WHEN a kind returns exactly 1000 rows and paging is not yet active
- THEN the UI shows "1000+ — older items not loaded" instead of
  silently truncating.

### Requirement: The `checking_resume_data` state maps to Downloading
A torrent in the `checkingResumeData` state SHALL be shown as
Downloading, not UNKNOWN.

#### Scenario: State string matches after lowercasing
- WHEN the server reports `checkingResumeData`
- THEN the mapped state is Downloading.

### Requirement: Readiness is gated on the download booleans, not the state string
A download SHALL be Ready only when `download_finished`,
`download_present`, or `cached` says so; `download_state == "completed"`
alone SHALL NOT mark it Ready.

#### Scenario: A completed-state item that is not finished is not Ready
- WHEN the state is `completed` but `download_finished` is false
- THEN the item is not shown as Ready.

### Requirement: Viewport appends load the next page near the end of a tab
Once Step 1 is measured and viewport paging is approved, each tab SHALL
load its next page (`limit=100`) when the user scrolls near the end,
with a per-page error row and Retry that re-requests only the failed page.

#### Scenario: Scrolling near the end appends the next page
- WHEN the user scrolls within the last 10 visible items of a tab
- AND more pages exist
- THEN the next page loads and appends without resetting scroll.

#### Scenario: A failed page does not lose loaded rows
- WHEN a page request fails
- THEN the loaded rows and scroll position stay, and a Retry row offers
  to re-request only that page.

#### Scenario: Search sees the whole tab, not just loaded rows
- WHEN the user types a query under viewport paging
- THEN the tab finishes its background sync first, and search matches
  the complete set.

#### Scenario: Fresh load resets pagination
- WHEN the user pulls to refresh or adds a download
- THEN offsets reset to 0, end-reached clears, and page 0 reloads.

### Requirement: Scroll state is keyed by account, tab, and criteria
List scroll state SHALL be keyed by (account, tab, criteria): a provider
switch resets all tabs to top, and process-death restoration restores
position only once the anchor row is in the loaded set, else starts at top.

#### Scenario: Provider switch does not reuse old scroll state
- WHEN the active provider changes
- THEN all three tab lists reset to the top.

#### Scenario: Process death restores only when the anchor is loaded
- WHEN the app is recreated and the saved anchor row is not yet loaded
- THEN the list starts at the top instead of jumping to a meaningless
  index.
