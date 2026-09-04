#!/usr/bin/env python3
"""
Turns a word-frequency list into the packed trie the keyboard reads.

Run by hand, output committed. Deliberately NOT a Gradle task: the word lists change
approximately never, and wiring a multi-hundred-megabyte download and a trie build into
`./gradlew assembleDebug` would tax every build of the app forever for the sake of a file
that gets regenerated once a year.

    ./build_dict.py en  ../../app/src/main/assets/keyboard/en.trie
    ./build_dict.py mk  ../../app/src/main/assets/keyboard/mk.trie

Source is hermitdave/FrequencyWords (OpenSubtitles). The generator code there is MIT; the
word lists themselves are CC BY-SA 3.0, which the app owes an attribution line for.

FILE FORMAT (little-endian throughout)

    header, 16 bytes
        0  magic       4  b"WPKD"
        4  version     1  = 1
        5  reserved    1
        6  edgeSize    1  = 8, so a reader can reject a format it does not know
        7  reserved    1
        8  rootOffset  4  byte offset of the root node
       12  wordCount   4  how many words went in, for sanity checks

    node
        u16 childCount, then childCount edges

    edge, 8 bytes, FIXED so that a node's children can be binary-searched
        0  char        2  UTF-16 code unit
        2  flags       1  bit0 terminal, bit1 has children
        3  freq        1  1..255 log-scaled; 0 when not terminal
        4  childOffset 4  absolute byte offset, 0 when none

Edges within a node are sorted by `char`. Fixed-width edges are the whole reason: a
variable-length encoding would be a little smaller and would force a linear scan of every
node on every lookup, and lookups happen thousands of times per keystroke while walking the
trie under an edit-distance bound.

A plain trie rather than a suffix-merged DAWG. A DAWG's trick is that identical suffixes
share nodes, and per-word frequencies stop suffixes being identical - the merge either fails
or forces the frequencies out into a side table, which costs back what the merge saved.
"""

import math
import struct
import sys
import urllib.request

MAGIC = b"WPKD"
VERSION = 1
EDGE_SIZE = 8

FLAG_TERMINAL = 1
FLAG_CHILDREN = 2

# ---------------------------------------------------------------- what counts as a word
#
# A frequency list alone will not do, and finding that out cost some time. The corpus is
# subtitles, and its long tail is misspellings, character names, OCR damage and fragments of
# other languages - but *frequency cannot separate those from real words*, because plenty of
# the junk is common. `jel` appears 42 times, which a floor would catch; `hel` appears 1,376
# times, which no floor sensible enough to keep `antidisestablishmentarianism` (17) ever will.
#
# That matters beyond a stray suggestion. A keyboard does not correct a word it believes in, so
# every piece of junk in here is a typo that will never be fixed - and the junk is exactly the
# near-misses of real words, because that is what mistyping produces.
#
# So three sources, and a word needs one of them:
#
#   1. It is in a real English word list. `dwyl/english-words` (public domain), 370,105 words,
#      which does the heavy lifting: on its own it accounts for about 139,000 of what the
#      corpus offers and discards essentially all of the noise.
#   2. It is common enough in the corpus to be real whatever a word list thinks. This is what
#      keeps names and slang that no dictionary has caught up with.
#   3. It is on the short list below, for the words a phone needs and a word list compiled
#      before phones cannot have.
WORDLIST = "https://raw.githubusercontent.com/dwyl/english-words/master/words_alpha.txt"

# Frequent enough that the corpus itself vouches for it. Low, because rule 1 has already done
# most of the work and this only has to catch what a word list would not know about.
CORPUS_VOUCHES = 500

# Words a 2015 word list cannot contain, which anyone typing on a phone will want on the first
# day. Not a general escape hatch - everything here is either a thing that did not exist when
# the word list was compiled, or a proper noun common enough to be worth the space.
MODERN = """
emoji wifi smartphone selfie app apps blog vlog podcast hashtag username login logout
bluetooth android iphone ipad google youtube instagram whatsapp netflix spotify uber
facebook twitter tweet retweet unfollow streaming webcam laptop usb pdf url gif jpeg
smartwatch airpods bitcoin crypto wikipedia reddit tiktok snapchat telegram zoom
skopje ohrid macedonian
"""

MIN_COUNT = 3

# The floor for a language with no word list to check against.
#
# Low, and deliberately no higher than English's, even though frequency is the only evidence
# there is here. The instinct is to raise it to compensate - that was tried at 40, and it took
# the language apart. Macedonian is heavily inflected, so its vocabulary is spread across many
# forms of each word with correspondingly small counts per form, and the corpus is a hundredth
# the size of the English one to begin with. At 40, `тастатура` - the Macedonian word for
# *keyboard* - was gone, and so was `скопје`. Both appear about a dozen times.
#
# So this admits some noise, knowingly. A keyboard that occasionally believes in a word that is
# not one occasionally fails to correct a typo. A keyboard missing the capital city and the
# word for itself is not usable in the language at all. If a permissively-licensed Macedonian
# word list turns up, that is the better fix.
MIN_COUNT_NO_WORDLIST = 3

MAX_WORDS = 150_000

# What letters a language is allowed to be spelled with. Anything else - stray Latin in a
# Cyrillic list, digits, punctuation beyond the apostrophe - is corpus dirt.
ALPHABETS = {
    "en": set("abcdefghijklmnopqrstuvwxyz'"),
    "mk": set("абвгдѓежзѕијклљмнњопрстќуфхцчџш"),
}

# ---------------------------------------------------------------- contractions
#
# The corpus splits every contraction at the apostrophe. It does not contain `don't`; it
# contains `don` 4,158,644 times and a separate fragment `'t`. Two things follow, and both
# are bad enough on their own to make an English keyboard feel broken:
#
#   1. `don't`, `can't`, `I'm`, `it's` - some of the commonest words in the language - are
#      absent entirely, so the keyboard would refuse to believe in them and try to correct
#      them into something else.
#   2. `don`, `doesn`, `isn`, `wasn` are left behind as extremely high-frequency entries.
#      They are not words. `don` would rank around tenth in the whole language and would be
#      offered constantly.
#
# So the -n't family is rebuilt from the wreckage and the wreckage is thrown away. The stem's
# own count is a genuinely good estimate of the contraction's frequency - `doesn` appeared
# 471,037 times and essentially every one of those was `doesn't`.
NT_STEMS = {
    "don": "don't", "doesn": "doesn't", "didn": "didn't", "isn": "isn't",
    "wasn": "wasn't", "aren": "aren't", "weren": "weren't", "couldn": "couldn't",
    "wouldn": "wouldn't", "shouldn": "shouldn't", "haven": "haven't",
    "hasn": "hasn't", "hadn": "hadn't", "ain": "ain't", "mustn": "mustn't",
    "needn": "needn't", "shan": "shan't", "mightn": "mightn't", "oughtn": "oughtn't",
}

# `won` and `can` are the exceptions: both are ordinary English words as well as contraction
# stems, so their counts are a blend and the stem must be kept rather than replaced. The
# contraction is added alongside at a share of it.
BLENDED_STEMS = {"won": ("won't", 0.9), "can": ("can't", 0.35)}

# The pronoun contractions cannot be rebuilt the same way, because there the stem *is* a real
# word: `i`, `you` and `it` carry their own counts and reveal nothing about how often they
# were followed by an apostrophe. These are therefore estimates - a share of the base word's
# count - and are marked as such. What matters is that they exist and land in roughly the
# right part of the ordering; the suggester weighs edit distance alongside frequency, so
# being out by a factor of two here changes nothing anyone can notice.
PRONOUN_CONTRACTIONS = [
    ("i", "i'm", 0.30), ("i", "i've", 0.05), ("i", "i'll", 0.06), ("i", "i'd", 0.05),
    ("it", "it's", 0.35), ("that", "that's", 0.25), ("he", "he's", 0.15),
    ("she", "she's", 0.15), ("there", "there's", 0.25), ("what", "what's", 0.20),
    ("let", "let's", 0.30), ("who", "who's", 0.10), ("here", "here's", 0.15),
    ("you", "you're", 0.10), ("you", "you've", 0.03), ("you", "you'll", 0.04),
    ("you", "you'd", 0.02), ("we", "we're", 0.12), ("we", "we've", 0.04),
    ("we", "we'll", 0.05), ("we", "we'd", 0.02), ("they", "they're", 0.12),
    ("they", "they've", 0.03), ("they", "they'll", 0.03), ("they", "they'd", 0.02),
    ("he", "he'd", 0.03), ("she", "she'd", 0.03), ("he", "he'll", 0.03),
    ("she", "she'll", 0.03), ("would", "would've", 0.04),
    ("could", "could've", 0.04), ("should", "should've", 0.04),
]


def restore_contractions(kept):
    """
    Rebuilds the contractions the corpus tokenizer destroyed. See the notes above.

    Returns a fresh list, still ordered by count, with the stems that are not words removed
    and the contractions they imply put in their place.
    """
    counts = dict(kept)
    out = [(w, c) for w, c in kept if w not in NT_STEMS]

    for stem, contraction in NT_STEMS.items():
        if stem in counts:
            out.append((contraction, counts[stem]))
    for stem, (contraction, share) in BLENDED_STEMS.items():
        if stem in counts:
            out.append((contraction, int(counts[stem] * share)))
    for base, contraction, share in PRONOUN_CONTRACTIONS:
        if base in counts:
            out.append((contraction, int(counts[base] * share)))

    # Anything added twice keeps its largest estimate, and the whole list is reordered
    # because the new entries were appended rather than inserted.
    best = {}
    for word, count in out:
        if count > best.get(word, 0):
            best[word] = count
    return sorted(best.items(), key=lambda pair: -pair[1])

SOURCE = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/{0}/{0}_full.txt"


def read_wordlist():
    """The English word list from rule 1 above, lowercased, as a set."""
    sys.stderr.write("fetching %s\n" % WORDLIST)
    with urllib.request.urlopen(WORDLIST) as response:
        raw = response.read().decode("utf-8", "ignore")
    return set(word.strip().lower() for word in raw.split() if word.strip())


def read_words(language):
    """Yields (word, count), most frequent first, already filtered."""
    allowed = ALPHABETS[language]
    # Only English has a word list to check against. The Macedonian list has to stand on
    # frequency alone, so its floor is raised instead - see MIN_COUNT_NO_WORDLIST.
    known = read_wordlist() | set(MODERN.split()) if language == "en" else None
    url = SOURCE.format(language)
    sys.stderr.write("fetching %s\n" % url)
    with urllib.request.urlopen(url) as response:
        raw = response.read().decode("utf-8")

    kept = []
    for line in raw.splitlines():
        parts = line.split(" ")
        if len(parts) != 2:
            continue
        word, count = parts[0], parts[1]
        if not count.isdigit():
            continue
        count = int(count)
        floor = MIN_COUNT if known is not None else MIN_COUNT_NO_WORDLIST
        if count < floor:
            # The list is ordered, so the first word below the floor ends the useful part.
            break
        # Length one is allowed, and that is not an oversight. `a` and `i` are words, and `i`
        # is also the stem of four of the commonest contractions in the language - filtering
        # single characters out to be rid of the corpus's stray letters quietly took `i'm`,
        # `i've`, `i'll` and `i'd` with them. The word list is what rejects `b` and `q`; the
        # suggester refuses to offer anything under two characters anyway, so the only thing a
        # one-letter entry is used for is knowing the word exists.
        if not word or len(word) > 32:
            continue
        if not set(word) <= allowed:
            continue
        # A leading or trailing apostrophe is always a tokenizer artefact.
        if word.startswith("'") or word.endswith("'"):
            continue
        # Rules 1 to 3. See the note at the top.
        if known is not None and word not in known and count < CORPUS_VOUCHES:
            continue
        kept.append((word, count))
        if len(kept) >= MAX_WORDS:
            break
    return kept


def scale(count, largest):
    """
    Squashes a raw occurrence count into one byte.

    Logarithmic, because the raw counts span seven orders of magnitude - `you` appears
    28 million times and the last word kept appears three - and a linear scale would round
    all but the commonest few thousand words to zero. What the suggester needs from this is
    the *order*, plus enough resolution to tell a common word from a rare one.
    """
    if count <= 0:
        return 1
    value = 1 + int(254 * math.log(count) / math.log(largest))
    return max(1, min(255, value))


class Node:
    __slots__ = ("children", "terminal", "freq", "offset")

    def __init__(self):
        self.children = {}
        self.terminal = False
        self.freq = 0
        self.offset = 0


def build_trie(words, largest):
    root = Node()
    for word, count in words:
        node = root
        for ch in word:
            node = node.children.setdefault(ch, Node())
        node.terminal = True
        node.freq = scale(count, largest)
    return root


def pack(root):
    """
    Lays the trie out depth-first, writing each node once its children are placed.
    
    A node's edges carry absolute offsets to their children, so a child has to be at a known
    position before its parent can be written - which means writing the deepest nodes first
    and the root last. The root's own offset goes in the header.
    """
    out = bytearray()

    def emit(node):
        # Place every child subtree first, so their offsets are known.
        for ch in sorted(node.children):
            child = node.children[ch]
            if child.children:
                emit(child)
            else:
                child.offset = 0
        offset = len(out)
        out.extend(struct.pack("<H", len(node.children)))
        for ch in sorted(node.children):
            child = node.children[ch]
            flags = 0
            if child.terminal:
                flags |= FLAG_TERMINAL
            if child.children:
                flags |= FLAG_CHILDREN
            out.extend(struct.pack("<HBBI", ord(ch), flags, child.freq, child.offset))
        node.offset = offset

    emit(root)
    return out, root.offset


def main():
    if len(sys.argv) != 3:
        sys.stderr.write("usage: build_dict.py <language> <output>\n")
        return 1
    language, destination = sys.argv[1], sys.argv[2]
    if language not in ALPHABETS:
        sys.stderr.write("no alphabet defined for %r\n" % language)
        return 1

    words = read_words(language)
    if not words:
        sys.stderr.write("nothing survived filtering\n")
        return 1
    if language == "en":
        before = len(words)
        words = restore_contractions(words)
        sys.stderr.write(
            "contractions: %d words in, %d out\n" % (before, len(words))
        )
    largest = words[0][1]
    sys.stderr.write("kept %d words, commonest %r at %d\n" % (len(words), words[0][0], largest))

    root = build_trie(words, largest)
    body, root_offset = pack(root)

    header = struct.pack(
        "<4sBBBBII", MAGIC, VERSION, 0, EDGE_SIZE, 0, root_offset + 16, len(words)
    )
    # Offsets were computed against the body alone, so every one of them shifts by the
    # header's length. Done here rather than during packing so the packer stays unaware of
    # the header entirely.
    body = shift_offsets(body, 16)

    with open(destination, "wb") as handle:
        handle.write(header)
        handle.write(body)
    sys.stderr.write("wrote %s, %.1f MB\n" % (destination, (len(body) + 16) / 1e6))
    return 0


def shift_offsets(body, delta):
    """Adds [delta] to every non-zero child offset, now that the header is in front."""
    view = memoryview(body)
    position = 0
    total = len(body)
    while position < total:
        (count,) = struct.unpack_from("<H", view, position)
        position += 2
        for _ in range(count):
            (offset,) = struct.unpack_from("<I", view, position + 4)
            if offset:
                struct.pack_into("<I", view, position + 4, offset + delta)
            position += EDGE_SIZE
    return body


if __name__ == "__main__":
    sys.exit(main())
