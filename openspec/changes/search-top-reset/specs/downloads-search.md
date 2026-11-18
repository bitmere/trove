# downloads-search

## Requirements

### Requirement: Reset search restores the default search state
The Downloads screen SHALL provide a Reset search action that clears the
search query and restores the sort order to Newest first (the default),
persisting the sort through the existing sort storage.

#### Scenario: Reset from a dirty search
- WHEN the query is non-blank and/or the sort is not Newest
- AND the user taps Reset search
- THEN the query becomes empty, the sort becomes Newest first and is
  persisted, and the current tab's list scrolls to the top.

#### Scenario: Reset is hidden when search is already clean
- WHEN the query is blank and the sort is Newest first
- THEN no Reset search action is shown.

#### Scenario: Reset does not change the tab
- WHEN the user taps Reset search on any tab
- THEN the selected tab (Torrents / Web / Usenet) is unchanged.

### Requirement: Go to top returns to the first result
The Downloads screen SHALL provide a Go to top action that scrolls the
currently visible tab's list to its first item without changing the query,
sort, or tab.

#### Scenario: Go to top appears only when scrolled down
- WHEN the visible list's first visible item index is below the threshold
- THEN the Go to top action is shown; when the list is at the top it is hidden.

#### Scenario: Go to top changes no search state
- WHEN the user taps Go to top
- THEN the list animates to item 0 and the query, sort, and tab are unchanged.

### Requirement: Existing clear keeps working
The search field's clear (X) action SHALL keep clearing only the query
text, and the empty-state "Clear search" button SHALL keep its current
behaviour. Neither is replaced by Reset search.
