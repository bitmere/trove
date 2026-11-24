# TMDB manual input and similar release-name cases

## Problem

Plan-only audit against main `91349f6`, Trove 0.1.41 (versionCode 41), on
`trove-tmdb-om-fix`. Findings below are source traces, not newly executed tests.
Required input: `/home/hatch/workspace/trove-tmdb-om-regex-research.md`
(2026-11-22), read before deciding scope. No Kotlin changes or validation runs
belong to this step.

Evidence abbreviations (all line numbers refer to this baseline):

- **T**: `core/src/main/java/app/trove/debrid/core/TmdbResolver.kt`
- **M**: `core/src/main/java/app/trove/debrid/core/MediaMetadata.kt`
- **V**: `app/src/main/java/app/trove/debrid/ui/downloads/DownloadsViewModel.kt`

### Included defects

| ID | Finding and evidence | Small fix |
| --- | --- | --- |
| F1 | T:119-123 parses digits as a positive Int before requiring a type. Unknown-type `418`, `1917`, `300`, `2012`, `42`, `0`, and an Int-overflow digit string become Invalid; T:135-136 makes no request. | With no valid movie/series fallback type, return ByTitle before Int conversion. With a valid type retain positive-Int ID semantics. |
| F2 | T:291 makes a search request, then T:296-298 discards nonexact prefixes shorter than three normalized characters. Thus `OM`/`pu` do reach TMDB but matching longer titles disappear. | Permit manual prefixes from two normalized characters; keep one-character searches exact-only. Approved item (1). |
| F3 | T:296-298 accepts exact/prefix matches only; a manual middle substring such as `Operation Mole` in `O.M Operation Mole` is discarded after the request. | Manual-only contains matching, at least two normalized characters, for localized and original titles. Approved item (2). |
| F4 | M:158-160 has no domain-banner removal and destroys dots before selecting the title. `www.1TamilMV.ceo - ` therefore contaminates the query. | Anchored raw-banner stripping before punctuation normalization. Approved item (5). |
| F5 | M:119,160 removes `(2026)` before M:165 extracts a year. The clean=false fallback at M:148 does not repair the primary query/year. | Retain standalone grouped-year candidates before cleanup, prefer grouped release years, and share the chosen year between clean/raw parsing. Approved item (6). |
| F6 | M:89 omits HDTS, DVDScr, CAM/TC families, HDRip, several resolution/codec/audio forms and sizes; M:162-168 consequently retains them as title text until another recognized token. `Film HQ HDTS 720p` currently retains `Film HQ HDTS`; `Film 1.7GB` retains the size. | Bounded technical-token families and adjacent HQ modifiers, using raw punctuation for compound/decimal recognition. Approved item (7), extended to the same evidenced omission across the research families. |
| F7 | M:84 only rejects adjacent digits, not letters; M:165-167 treats `Film A2012B` as title `Film A`, year 2012. `[1917]` is retained by M:159 but M:166 accepts its year at offset 1 and leaves an empty title; `(1917)` is deleted. | Require separator-bounded year candidates and meaningful title content before a release-year candidate, ignoring wrappers. Preserve a sole grouped numeric title; do not confuse it with a release year. |
| F8 | M:129 returns immediately on a blank primary title; M:245 also requires a valid first query. For `(Alien)` the clean path is empty although M:148's clean=false path could retain `Alien`. | Recover a usable existing raw parse only when the clean title is blank; use it as the first query. Keep URL/magnet and metadata-only inputs blocked. |
| F9 | M:159 changes dots to spaces and M:180-186 interprets a terminal 3–4 digit number over 100 as an absolute episode. `1.618` consequently becomes title `1`, type series. | Require alphabetic title evidence before inferring an absolute episode from this weak suffix; preserve numeric/punctuation-only title text and unknown type. Explicit episode markers remain authoritative. |

### Candidate audit and exclusions

| Candidate | Actual branch / evidence | Decision |
| --- | --- | --- |
| `418`, `1917`, `300`, `2012`, `42` with known type | T:119-123 resolves a TMDB ID, not a title. | Preserve intentional ID behavior; changing it removes functionality. Unknown/invalid fallback types are covered by F1. A title with punctuation still takes the title branch. |
| Numeric overflow or zero with known type; explicit TMDB links with overflow/zero | T:114-115,120 rejects unrepresentable/nonpositive IDs without requests. | Preserve validation. F1 only removes this restriction when input is an untyped title. |
| `4:18`, `418.`, `1917.`, `1.618`, `42!` manually | T:119's all-digit predicate fails; T:125 returns ByTitle; T:146 sends the original trimmed string. M:189-191 normalizes comparison keys to digits. | Already routed to search; no manual query cleaning. `418.` was confirmed on-device by the user on 2026-11-23. |
| Plain numeric titles automatically | M:166 excludes a year at offset zero. `1917`, `2012`, `1917.`, `1917.2019.1080p`, `1923 S01E01` retain the leading title; `300` and `42` are not year candidates. | Already handled; regression protection. F7 covers wrappers; F9 covers numeric suffix misrouting. `4:18` has no absolute-episode separator and survives. |
| One-character / exact two-character titles | T:298 accepts equality regardless of length; M:299 and T:71 have no minimum title length. `X`, `It`, `Up` can search and match exactly in both lanes. | No one-character fuzzy matching. F2/F3 affect only manual nonexact results. A single digit with unknown type is F1. |
| `tt1234567`, `tt`, or a title beginning `tt` | T:112-125 has no IMDb-ID branch: all go ByTitle. T:253-268 implements a separate explicit IMDb resolver, not manual parsing. | IMDb syntax support in this TMDB field is a separate feature; do not reinterpret title strings or claim these are dropped. |
| Valid themoviedb.org movie/tv links | T:112-117,157-159 supplies explicit type and ID, tolerating schemes, case, slugs and suffixes. | Already handled; keep precedence. |
| Malformed TMDB links, other websites, URL-shaped titles | If the link regex fails, T:125 sends them as title text; tests at TmdbResolverTest.kt:384-413 intentionally pin non-TMDB ByTitle behavior. | Existing behavior, not a dropped-input defect; no new link grammar or manual query cleaning. Automatic URLs/magnets are intentionally blocked at M:157. |
| Blank or >160-character input | T:111,71 and M:245,299,452 gate requests. Limits apply to trimmed manual input / parsed auto title, not the entire raw release. | Intentional bounded guards; no cap raise or truncation. |
| Punctuation-only / empty normalized title | `...` is ByTitle manually and can reach TMDB; M:189-191 produces an empty comparison key. Auto dots become whitespace at M:159 and are rejected as blank. | No useful numeric title is lost by normalization (digits remain). Keep one-character exact-only and require length >=2 for fuzzy matches so empty contains cannot match everything; broader normalization/validation is outside scope. |
| Bare languages after a year or technical boundary | M:167 cuts the entire suffix already; after F5/F6, `Film (2026) Telugu HQ HDTS` loses the suffix without a language blacklist. M:121-122 already handles trailing dubbed/dual-audio qualifiers. | No independent global language fix: `Film Telugu HDTS` without a year retains Telugu because it precedes the boundary. Removing it lacks safe suffix evidence. Preserve title-internal language words. |
| TRUE, Clean, HQ Clean, unbracketed release-group words | Absent from M:89,120-122; M:170-178 strips only edition/dub qualifiers. `418 TRUE`, `Film Clean`, `Film-GROUP` remain polluted when no reliable delimiter exists. Groups after a recognized boundary are already cut; square-bracket groups are removed at M:159. | Do not blacklist ordinary words or arbitrary groups. F6 fixes tails only when a real technical boundary exists. Ambiguous no-year/no-source cases need additional evidence, not speculative removal. |
| HQ, CAM, TS, TC, WEB, HD as title words | Existing M:89 lacks most of these; blindly adding them would introduce false boundaries. | F6 requires context for weak forms; bare HQ never cuts. Preserve `Cam`, `The Web`, `HQ Clean`, and language-bearing titles. |
| Whole square-bracket title `[REC]`; mixed parenthetical title text | M:159 strips nonyear brackets in both clean/raw paths. M:160 strips parentheticals; M:148 can retain them only if the primary is nonblank. | F8 narrowly recovers an existing usable raw parse for blank primaries. Inferring `[REC]` versus `[GROUP]`, or changing general parenthetical/title ordering, is deferred to a separately specified ambiguity policy. |
| Year-like title suffix `Class of 1984`; `Room 237` | M:165-167 takes plausible trailing years as release years; M:180-186 infers absolute episodes for alphabetic titles ending in >100. M tests:58-72 deliberately preserve anime detection and future-year sequel text. | Known ambiguity, excluded: blanket changes would break ordinary release-year / anime recognition. F7 handles objectively misplaced/embedded/sole years; F9 only handles numeric-only prefixes. |
| Future years and multiple years | M:164 uses current year +1; M:166 already picks the last eligible bare year. | Keep 1900..current-year+1 (within current 19xx/20xx recognition); no stale research cutoff. F5 adds standalone grouped preference, not arbitrary years inside `(Hindi Dubbed 2024)`. |
| `parseReleaseDetails` | M:91-108 extracts episode and a limited resolution label; it does not dispatch searches. Sharing releaseTag means boundary expansion could accidentally affect display labels. | Preserve existing display-label behavior; use separate classification where needed. Extra quality badges are not part of this fix. |

## Change

1. Implement F1–F3 in the manual resolver, retaining raw manual queries, exact
   auto matching, returned order, and the five-candidate cap. No extra search
   pass or ranking change.
2. Implement F4–F7 as small pure parser helpers: raw banner detection → retain
   eligible year candidates → scan bounded technical tokens → select title
   boundary → normalize title punctuation. Keep small reusable precompiled
   regexes, not per-token pipelines or dependencies. Clean/raw variants share
   structural decisions without adding variants.
3. Implement F8–F9 as narrow fallback/absolute-episode guards. Preserve existing
   edition/article variants and their deduplication except when replacing an
   unusable first query with its usable existing raw parse.
4. Add focused parser and fake-transport regressions for both the new cases and
   preservation cases. Later implementation review precedes compilation;
   focused tests, full suite and pre-PR lint happen only in an authorized step.

### Research-informed boundary policy

Write fresh code informed by the report's verified Radarr website/source
patterns, GuessIt separator/grouped-year rules, and PTT technical families and
suffix-only language handling. Do not copy upstream implementations or treat
unverified report sections as established behavior.

Banner detection is anchored, runs before dots are erased, allows optional
wrappers/www and any 2+ letter TLD (including `.ceo`), and requires a closing
wrapper or a separator run of at least two spaces/hyphens. Preserve dotted
initials, ordinary hyphens and unseparated domain-shaped text; protect the
report's `Naruto-Kun` collision. For the known already-normalized provider
banner, allow only the narrow `www <label> <TLD> - ` shape; do not guess missing
domains from arbitrary words. No diagnostics logging of release names.

Recognize compound forms first: HDTS/HD-TS/TELESYNC, HDCAM/CAMRip, HDTC/TELECINE,
DVDScr/SCR/SCREENER, WEB-DL/WEBRip, BluRay/BDRip/BRRip/HDRip; 3–4 digit p/i
resolutions and 4K/FHD; x/h264/265, HEVC/AVC/Xvid/AV1; AAC/AC3/DDP/DTS/TrueHD/
Atmos and channel forms; decimal MB/GB sizes. Retain existing supported tokens.
Weak short words TS/TC/CAM/WEB/HD/DD/SCR and standalone channel numbers require
an established release year or another adjacent unambiguous technical token,
and a nonempty preceding title. Do not use a technical token at title start
as an empty-title boundary. HQ moves a source boundary left only when directly
adjacent to that recognized source; HQ alone never does. Language names are
metadata only after an established year/technical delimiter; cutting that
suffix suffices here. No blanket language removal from title text.

### 418 download correction

Enrichment reads **download.name, not the file name** (V:951). The actual row
name is `www 1TamilMV ceo - 418 TRUE`, without a release year. Even removing
its banner leaves `418 TRUE`; these parser changes do **not** guarantee its
automatic enrichment. After shipping, the row needs a new hydration attempt:
pull-to-refresh sets bypassMetadataMiss (V:263-264,283), passed through hydration
(V:744-746,951); the resolver bypasses cached misses at M:254. A retry does not
promise a match. Manual `418` with unknown type will instead become searchable.

### Minimum fix versus span model

Choose the minimum pipeline above. A few retained year positions and token
boundaries solve the included defects without a general annotation/conflict
engine, additional libraries, network requests, or unbounded query expansion.
The report's span-annotation model is a separate **optional future proposal**
for overlapping title/metadata evidence and ambiguous grouped text. None of
F1–F9 requires it. It would help represent `[REC]`/group or year-title conflicts,
but cannot establish whether an unsupported `TRUE`/`Clean` suffix is title text
without an explicit evidence policy. Do not bundle that rewrite into this work.

## Scope

Only core parser/resolver implementation and their existing tests in a later
step; this step writes the proposal, tasks, spec and root summary only. Keep
core Android-free, no dependencies, no functionality removed. No UI, application
identity, version, credentials, cache schema or hydration-policy changes.
Non-goals: manual query cleaning, candidate cap increases, ranking, automatic
fuzzy matching, IMDb-input features, arbitrary word/group blacklists, broader
annotation rewrite. No builds/tests/lint or remote git actions in this step.
