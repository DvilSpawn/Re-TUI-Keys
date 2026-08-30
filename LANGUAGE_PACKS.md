# Re:TUI Keys Language Packs

Language packs add a keyboard layout and offline word completions without
installing another app. Each language is published as its own GitHub Release.

If **Language Packs** is not visible in Re:TUI Keys Settings, update to a build
that includes language-pack support.

## Available packs

| Language | Locale | Version | Download | Release notes |
| --- | --- | --- | --- | --- |
| Persian / فارسی | `fa-IR` | 1 | [Download `.retui-lang`](https://github.com/DvilSpawn/Re-TUI-Keys/releases/download/language-pack-fa-IR-v1/persian-fa-IR-v1.retui-lang) | [Persian Language Pack v1](https://github.com/DvilSpawn/Re-TUI-Keys/releases/tag/language-pack-fa-IR-v1) |

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

To submit an official pack:

1. Fork the repository and add the source files under `language-packs/<locale>/`.
2. Build and test the `.retui-lang` archive.
3. Open a pull request describing the layout source, word-list source, license,
   and completion checks performed.
4. After review, the Re:TUI maintainer publishes a dedicated GitHub Release and
   adds the language to the table above.

You can also host a compatible `.retui-lang` file in your own GitHub Release and
import it manually. Only reviewed packs are listed on this page.
