# Tasks

- [ ] Resolver (S, low): add and thread the default-false
  `allowPrefix` parameter; enable it only for manual title searches,
  with a minimum of three normalized characters.
- [ ] Documentation (XS, low): describe manual prefix matching and
  the automatic exact gate in both search doc comments.
- [ ] Resolver tests (S, low): cover ordered partial title results
  with configured w185 posters, original-title prefixes, short-query
  rejection, and the default automatic exact gate using FakeTmdb.
- [ ] Picker regression (XS, low): cover an empty Cinemeta catalog
  falling back to TMDB without accepting "Example" for "Example Film".
- [ ] Coordinator review and gates (S, low): review the collected diff
  and run the required checks separately; no build, tests, or lint
  are run during this implementation.
