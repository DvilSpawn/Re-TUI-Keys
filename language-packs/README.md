# Re:TUI Keys language packs

Publish each file from `dist/` as a separate GitHub release asset. Users
download the `.retui-lang` file and import it from Re:TUI Keys Settings.

Every pack is a ZIP with these root entries:

- `manifest.json`: schema, locale, key rows, punctuation, normalization rules.
- `words.tsv`: one `word<TAB>frequency` entry per line, up to 50,000 words.
- `LICENSE` or `NOTICE`: optional source attribution.

Packs are data-only. The app rejects unknown files, duplicate entries, invalid
layouts, oversized data, unsupported schemas, and malformed language IDs.
