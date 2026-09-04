#!/usr/bin/env python3
"""
Turns Unicode's own emoji-test.txt into the flat, tab-separated list the keyboard reads.

Run by hand, output committed. Deliberately NOT a Gradle task, for the same reason
../dictbuild/build_dict.py is not one: the source changes on Unicode's own release cadence,
which is roughly once a year, and wiring a network fetch into `./gradlew assembleDebug`
would tax every build of the app forever for the sake of a file that is regenerated rarely
and reviewed as a diff when it is.

    ./build_emoji.py ../../app/src/main/assets/keyboard/emoji.txt

Source: https://unicode.org/Public/emoji/15.1/emoji-test.txt - the file UTS #51 itself
recommends for building a keyboard palette, and in CLDR order rather than codepoint order,
which is why nothing here re-sorts it. It is public domain / Unicode's terms of use; see
https://www.unicode.org/terms_of_use.html.

FILE FORMAT

One emoji per line, three fields separated by a tab:

    <glyph> TAB <categoryIndex> TAB <name>

`glyph` is the emoji itself, already assembled from its codepoints - a plain string rather
than a codepoint list, so the app never has to parse one at load time, only draw it.
`categoryIndex` is 0-8, [CATEGORIES] below in order. `name` is Unicode's own short
description ("grinning face", "flag: Puerto Rico") and is what search matches against;
kept lower-case, as the source already has it, so the runtime does not have to normalise it
on every keystroke of a search.

Only `fully-qualified` entries are kept. `minimally-qualified` and `unqualified` are the
same emoji missing a presentation selector - the same character a keyboard already offers,
spelled with one fewer code point - and keeping them too would put duplicates of a few
hundred emoji in the picker. `component` (skin tones and hair, in isolation) is dropped
outright: nothing here builds compound emoji from parts, so a lone skin-tone swatch is not
a pickable emoji, it is an ingredient.

No glyph-drawability filtering happens here. Whether a given device's system font can
actually render an entry is a question about *that device*, asked at load time with
androidx.core.graphics.PaintCompat.hasGlyph - see EmojiData.kt. Filtering it out at build
time would bake today's phone's limitations into a file every phone reads.
"""

import re
import sys
import urllib.request

SOURCE = "https://unicode.org/Public/emoji/15.1/emoji-test.txt"

# The nine groups worth picking from, in the file's own CLDR order, with "Component"
# dropped - it holds bare skin-tone and hair swatches, not emoji anyone picks on their own.
# Index into this list is what "categoryIndex" in the output means, and EmojiData.kt's
# EmojiCategories must list the same nine names in the same order.
CATEGORIES = [
    "Smileys & Emotion",
    "People & Body",
    "Animals & Nature",
    "Food & Drink",
    "Travel & Places",
    "Activities",
    "Objects",
    "Symbols",
    "Flags",
]

GROUP_RE = re.compile(r"^# group: (.+)$")
ENTRY_RE = re.compile(r"^([0-9A-Fa-f ]+?)\s*;\s*(\S+)\s*#\s*(\S+)\s+(\S+)\s+(.+)$")


def fetch(source):
    sys.stderr.write("fetching %s\n" % source)
    with urllib.request.urlopen(source) as response:
        return response.read().decode("utf-8")


def parse(text):
    """
    Yields (glyph, categoryIndex, name) for every fully-qualified entry in a category we
    keep. A generator rather than a list because the file is walked once, in order, with
    one piece of state - which group the parser is currently under - carried between lines.
    """
    group = None
    for line in text.splitlines():
        header = GROUP_RE.match(line)
        if header:
            group = header.group(1)
            continue

        entry = ENTRY_RE.match(line)
        if not entry or group not in CATEGORIES:
            continue

        codepoints, status, _glyph_in_comment, _version, name = entry.groups()
        if status != "fully-qualified":
            continue

        glyph = "".join(chr(int(cp, 16)) for cp in codepoints.split())
        yield glyph, CATEGORIES.index(group), name.strip().lower()


def main():
    if len(sys.argv) != 2:
        sys.stderr.write("usage: build_emoji.py <output>\n")
        return 1
    destination = sys.argv[1]

    text = fetch(SOURCE)
    rows = list(parse(text))
    if not rows:
        sys.stderr.write("nothing survived parsing\n")
        return 1

    with open(destination, "w", encoding="utf-8") as handle:
        for glyph, category, name in rows:
            handle.write("%s\t%d\t%s\n" % (glyph, category, name))

    counts = {}
    for _glyph, category, _name in rows:
        counts[category] = counts.get(category, 0) + 1
    for i, label in enumerate(CATEGORIES):
        sys.stderr.write("  %-18s %4d\n" % (label, counts.get(i, 0)))
    sys.stderr.write("wrote %s, %d emoji\n" % (destination, len(rows)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
