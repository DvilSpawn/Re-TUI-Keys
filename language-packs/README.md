# Re:TUI Keys language packs

Publish each file from `dist/` as a separate GitHub release asset. Users
download the `.retui-lang` file and import it from Re:TUI Keys Settings.

Run `./build.sh` to repack every language directory into `dist/`, or
`./build.sh lt-LT` for one. Edit `<locale>/manifest.json`, rebuild, re-import.

Every pack is a ZIP with these root entries:

- `manifest.json`: schema, locale, key rows, punctuation, normalization rules.
- `words.tsv`: one `word<TAB>frequency` entry per line, up to 50,000 words.
- `LICENSE` or `NOTICE`: optional source attribution.

Packs are data-only. The app rejects unknown files, duplicate entries, invalid
layouts, oversized data, unsupported schemas, and malformed language IDs.

Write key rows in lower case. A bicameral script is detected from the rows and
gets a SHIFT key cased with the pack `languageTag`; `"casing": "none"` turns
that off for caseless scripts such as Persian.

`"rowStyle": "grid"` draws an ortholinear layout — uniform key sizes, BACKSPACE
in the first column of the bottom row, SHIFT moved to the special key row. Its
rows must be N, N and N-1 keys wide.
