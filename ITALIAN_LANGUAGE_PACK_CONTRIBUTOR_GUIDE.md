# Help us build the Italian language pack for Re:TUI Keys

Thank you for helping bring Italian support to Re:TUI Keys. You do not need to
write Android code or build the app. We mainly need your language knowledge:
the correct keyboard layout, useful Italian words, accent behavior, and source
attribution.

## What the finished pack will do

The Italian pack will provide:

- an Italian keyboard layout;
- Italian punctuation and labels;
- accented Italian characters;
- offline word suggestions;
- a dictionary that stays separate from the English dictionary.

Language packs are data-only. They cannot run code or access user data.

## The easiest way to contribute

Send us these files in one folder named `it-IT`:

```text
it-IT/
├── manifest.json
├── words.tsv
├── README.md
└── NOTICE or LICENSE   (required if the word list came from another source)
```

You can send the folder directly to the maintainer. If you use GitHub, you can
instead add it under `language-packs/it-IT/` and open a pull request.

## 1. Review the proposed Italian layout

This is the proposed starting layout:

```text
Q W E R T Y U I O P
 A S D F G H J K L
  Z X C V B N M
```

Please confirm:

1. Does this feel natural for an Italian phone keyboard?
2. Should the apostrophe have a dedicated key?
3. Which accented characters should appear when holding each vowel, and in
   what order? Please pay particular attention to `è` versus `é`.
4. Are standard digits `1 2 3 4 5 6 7 8 9 0` correct?
5. Are comma `,`, period `.`, and question mark `?` correct?

Suggested Italian accent priorities:

```text
A: à á â ä
E: è é ê ë
I: ì í î ï
O: ò ó ô ö
U: ù ú û ü
```

Please correct or reorder them. Do not add accented letters to the three main
rows just to make them available; the maintainer will wire the approved accent
choices into long press.

## 2. Start with this `manifest.json`

```json
{
  "schema": 1,
  "id": "it-IT",
  "languageTag": "it-IT",
  "name": "Italian",
  "nativeName": "Italiano",
  "switchLabel": "IT",
  "version": 1,
  "direction": "ltr",
  "casing": "lower",
  "stripMarks": false,
  "rows": [
    ["q", "w", "e", "r", "t", "y", "u", "i", "o", "p"],
    ["a", "s", "d", "f", "g", "h", "j", "k", "l"],
    ["z", "x", "c", "v", "b", "n", "m"]
  ],
  "digits": ["1", "2", "3", "4", "5", "6", "7", "8", "9", "0"],
  "comma": ",",
  "period": ".",
  "questionMark": "?",
  "spaceLabel": "spazio",
  "joinerLabel": "'",
  "joiners": ["'"],
  "characterMap": {
    "’": "'"
  }
}
```

The apostrophe is treated as part of a word, so suggestions can include forms
such as `l'acqua`, `un'altra`, and `com'è`. Curly apostrophes are normalized to
the straight apostrophe used by the keyboard.

If you recommend changing any value, add a short explanation to `README.md`.

## 3. Create `words.tsv`

Add one Italian word per line, followed by a tab and a frequency from 1 to 255:

```text
di	255
che	254
e	253
il	252
la	251
perché	220
città	210
più	205
l'acqua	180
un'altra	170
```

Rules:

- Use UTF-8 text.
- Include between 10 and 50,000 unique words.
- Keep words to 32 characters or fewer.
- Use lowercase Italian spelling.
- Keep meaningful accents: `e` and `è`, for example, are different entries.
- Use a tab—not spaces—between the word and frequency.
- Higher frequency means the word is suggested earlier.
- Put only words in this file; no definitions or translations are needed.
- Remove duplicates, obvious misspellings, URLs, email addresses, and private
  names or data.

Please include common modern Italian, not only formal or literary vocabulary.
Contractions and elisions are welcome when they are normally written with an
apostrophe.

## 4. Record the source and license

In `README.md`, tell us:

- who created or reviewed the layout;
- where the word list came from;
- what license permits redistribution;
- whether you cleaned, merged, or re-ranked the source data;
- any regional or spelling choices we should know about.

If the word list comes from another project, include its required license text
as `LICENSE` or its attribution as `NOTICE`. Do not copy a word list from a
website unless its license clearly allows redistribution.

If the list is your own original work, say that clearly in `README.md` and tell
us what license you want to use.

## 5. Check these examples

Before sending the files, confirm that the word list can offer useful matches
for prefixes like these:

| Typed prefix | Expected examples |
| --- | --- |
| `buo` | `buongiorno`, `buono` |
| `gra` | `grazie`, `grande` |
| `per` | `per`, `perché` |
| `cit` | `città` |
| `pi` | `più` |
| `l'a` | `l'acqua`, `l'amore` |

Also review a sample of the highest-frequency words. They should be genuinely
useful and correctly accented.

## What happens after you send it

The maintainer will:

1. validate the files and license;
2. package them as `italian-it-IT-v1.retui-lang`;
3. test importing, switching, typing, accents, apostrophes, and suggestions;
4. publish the pack as its own GitHub Release;
5. add Italian to the public Re:TUI Keys language-pack list.

Thank you—native-speaker review is the part we cannot automate, and it is what
will make the pack feel genuinely Italian.
