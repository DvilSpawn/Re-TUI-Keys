# Re:TUI Keys Language Packs

Language packs add a keyboard layout and offline word completions without
installing another app. Each language is published as its own GitHub Release.

If **Language Packs** is not visible in Re:TUI Keys Settings, update to a build
that includes language-pack support.

## Available packs

| Language | Locale | Version | Layout | Download | Release notes |
| --- | --- | --- | --- | --- | --- |
| Persian / فارسی | `fa-IR` | 1 | staggered | [Download `.retui-lang`](https://github.com/DvilSpawn/Re-TUI-Keys/releases/download/language-pack-fa-IR-v1/persian-fa-IR-v1.retui-lang) | [Persian Language Pack v1](https://github.com/DvilSpawn/Re-TUI-Keys/releases/tag/language-pack-fa-IR-v1) |
| Lithuanian / Lietuvių | `lt-LT` | 1 | grid, 12 columns | [Download `.retui-lang`](https://github.com/DvilSpawn/Re-TUI-Keys/releases/download/language-pack-lt-LT-v1/lithuanian-lt-LT-v1.retui-lang) | [Lithuanian Language Pack v1](https://github.com/DvilSpawn/Re-TUI-Keys/releases/tag/language-pack-lt-LT-v1) |
| Russian / Русский | `ru-RU` | 1 | grid, 12 columns | [Download `.retui-lang`](https://github.com/DvilSpawn/Re-TUI-Keys/releases/download/language-pack-ru-RU-v1/russian-ru-RU-v1.retui-lang) | [Russian Language Pack v1](https://github.com/DvilSpawn/Re-TUI-Keys/releases/tag/language-pack-ru-RU-v1) |

## Install a pack

1. Download the `.retui-lang` file for the language.
2. Open **Re:TUI Keys Settings**.
3. Expand **Language Packs**.
4. Tap **Import Language Pack** and choose the downloaded file.
5. Use the language key on the keyboard to switch between English and installed packs.

Installing a pack changes the active layout, digits, punctuation, text direction,
normalization rules, and completion dictionary. It does not replace appearance,
feedback, clipboard, sizing, or other keyboard settings.

To remove a pack, tap **DEL** beside it. Re:TUI Keys removes the pack and switches
the keyboard back to English.

## Release structure

Every language has a dedicated release tag:

```text
language-pack-<locale>-v<version>
```

The release contains one `.retui-lang` asset. Updating a language creates a new
versioned release; it does not overwrite an older pack.

## Contribute a language pack

A `.retui-lang` file is a ZIP archive containing these files at its root:

- `manifest.json` — locale metadata, three key rows, punctuation, text direction,
  joiners, and normalization rules.
- `words.tsv` — one `word<TAB>frequency` entry per line, with 10–50,000 unique words.
- `LICENSE` or `NOTICE` — optional but required when the word list or layout needs attribution.

Download and unzip the Persian pack above as the reference structure. Packs must
be data-only: executable files, nested directories, and unknown archive entries
are rejected by the app.

### Upper and lower case

Write the three key rows in lower case. Re:TUI Keys detects a bicameral script
from those rows and then adds a SHIFT key to the bottom row, cases the key caps
with the pack `languageTag` (so `lt`, `tr` and similar behave correctly), and
enables sentence capitalization. Caseless scripts such as Persian get no SHIFT
key and keep the extra room for the joiner key.

Set `"casing": "none"` to force SHIFT off for a layout that uses a bicameral
script but should never produce capitals. `"casing": "lower"` (the default)
keeps the completion dictionary case-insensitive, so `Labas` and `labas` match
the same entry.

A wide bottom row is allowed: SHIFT, the joiner key and BACKSPACE shrink so the
letters keep their width.

### Row style

`"rowStyle"` picks how the three rows are drawn.

`"staggered"` (the default) is the phone-keyboard shape: the middle row is inset,
and the bottom row carries SHIFT on the left and BACKSPACE on the right beside
the letters.

`"grid"` is the ortholinear shape of a reviung41 or corne: every key is the same
size, rows are not inset, and the bottom row starts with BACKSPACE. SHIFT does
not fit in the letter rows, so it joins the special key row (ESC, TAB, CTRL, ALT,
SUPER, DEL) — or the bottom row when that row is hidden, so it is never lost.
Turn on **Portrait special keys** in Settings to keep SHIFT next to the other
modifiers.

Grid rows must line up: the first two rows are equal width and the third has one
key fewer, because BACKSPACE takes that column. A 12-column pack therefore
declares 12, 12 and 11 keys:

```json
{
  "schema": 1,
  "id": "lt-LT",
  "languageTag": "lt-LT",
  "name": "Lithuanian",
  "nativeName": "Lietuvių",
  "switchLabel": "LT",
  "version": 1,
  "rowStyle": "grid",
  "rows": [
    ["q", "w", "e", "r", "t", "y", "u", "i", "o", "p", "ą", "ų"],
    ["a", "s", "d", "f", "g", "h", "j", "k", "l", "ė", "į", "š"],
    ["z", "x", "c", "v", "b", "n", "m", "ž", "č", "ū", "ę"]
  ]
}
```

A grid pack has no separate joiner key; put the joiner character in a row if the
language needs it on the keyboard. `joiners` still governs word boundaries and
completions either way.

### Split keyboard

**Split keyboard** in Settings works with language packs, not just English. Each
row is cut in half and the halves are padded to the same width, so a 12-column
grid pack becomes two banks of six — the reviung41 and corne arrangement. In a
split grid layout SHIFT sits in the bottom row next to the language key.

To submit an official pack:

1. Fork the repository and add the source files under `language-packs/<locale>/`.
2. Build and test the `.retui-lang` archive.
3. Open a pull request describing the layout source, word-list source, license,
   and completion checks performed.
4. After review, the Re:TUI maintainer publishes a dedicated GitHub Release and
   adds the language to the table above.

You can also host a compatible `.retui-lang` file in your own GitHub Release and
import it manually. Only reviewed packs are listed on this page.
