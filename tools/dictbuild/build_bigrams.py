#!/usr/bin/env python3
"""
Turns a corpus into the next-word table the keyboard reads.

Run by hand, output committed, for the same reasons `build_dict.py` is - and the two are
meant to be read together; this one borrows that one's vocabulary wholesale.

    ./build_bigrams.py en ../../app/src/main/assets/keyboard/en.bigrams

A third argument overrides LINES, which is what to use when checking a change to this
script: a hundred thousand lines runs in seconds and produces a table that is obviously
wrong in the right ways.

WHY THIS EXISTS AT ALL

The word lists come from hermitdave/FrequencyWords, which is a frequency list: a word and a
count, with the order the words appeared in thrown away. That is everything the trie needs
and nothing prediction needs. `UserDictionary` has therefore been the only source of pairs
in the keyboard, which means a fresh install predicts nothing and stays that way until
somebody has typed enough for it to have watched them - the one part of the keyboard that
started out empty and looked broken rather than new.

So the pairs are counted here instead, from the corpus those frequencies were derived from
in the first place: OpenSubtitles, through OPUS. Same source, same licence, same
attribution, and this time read as text rather than as a bag of words.

SOURCE

    https://object.pouta.csc.fi/OPUS-OpenSubtitles/v2018/mono/en.txt.gz

3.7 GB compressed, and it is not downloaded. gzip is a stream, so reading LINES lines and
walking away costs only the bytes those lines are in - about 170 MB for twenty million,
which is the entire dialogue of some tens of thousands of films and far more than the top
few followers of thirty thousand words needs to settle.

The file is *raw* sentences, one per line, and that matters more than it sounds. The
tokenized OPUS releases split contractions at the apostrophe, which is the wreckage
`build_dict.py` spends a page rebuilding - `don` 4.2 million times and a loose `'t`. Raw
text has `don't` intact, so the commonest predictions in conversational English (`i` ->
`don't`, `it` -> `doesn't`) come out right without any of that.

FILE FORMAT (little-endian throughout)

    header, 24 bytes
        0  magic         4  b"WPKB"
        4  version       1  = 1
        5  reserved      1
        6  entrySize     1  = 3, so a reader can reject a format it does not know
        7  reserved      1
        8  wordCount     4  how many words are in the pool
       12  wordsOffset   4  byte offset of the string blob
       16  startOffset   4  byte offset of the sentence-start list, 0 if there is none
       20  pairCount     4  how many words have followers, for sanity checks

    24                  wordOffsets    u32 * wordCount, into the string blob
    24 + 4*wordCount    followerLists  u32 * wordCount, into the follower blob, 0 for none
    wordsOffset         strings        u8 length, then that many UTF-8 bytes
    ...                 followers      u8 count, then count * (u16 wordId, u8 weight)

Three decisions worth defending.

**The pool is sorted, and ids are indices into it.** A lookup is "what follows this word",
which is a binary search over the word offsets - eighteen probes on thirty thousand words,
comparing UTF-8 bytes in the buffer against the query encoded once. No allocation on the way
in, and the strings only become Kotlin `String`s at the end, for the two or three followers
that are actually returned.

**Followers are u16 ids into that same pool, not strings.** Followers repeat enormously -
`the`, `to`, `a`, `you` follow half the language - so spelling them out at every site was
2.4 MB where pointing at them is 390 KB. The cap on the pool is 65,535 words, checked at
build time.

**UTF-8 in the pool, where the trie uses UTF-16.** The trie is walked character by character
tens of thousands of times per keystroke and a fixed-width code unit is worth real time
there. Nothing here is walked; it is binary-searched once per word, and English in UTF-8 is
half the bytes.
"""

import gzip
import math
import re
import struct
import sys
import urllib.request
from collections import Counter

import build_dict

MAGIC = b"WPKB"
VERSION = 1
ENTRY_SIZE = 3

SOURCE = "https://object.pouta.csc.fi/OPUS-OpenSubtitles/v2018/mono/{0}.txt.gz"

# How much of the corpus to read. Twenty million subtitle lines is roughly 150 million words.
#
# The number is set by what the *tail* needs, not the head: `the` -> `same` settles inside
# the first hundred thousand lines, and the thirty-thousandth commonest word appearing often
# enough for its own followers to mean anything is what takes twenty million. Raising it
# further buys very little - the followers stop moving long before the counts do.
LINES = 20_000_000

# Which words may appear in a pair at all: the commonest VOCAB of the shipped list.
#
# Taking the vocabulary from `build_dict` rather than from the corpus is the whole of the
# noise filtering, and it is free. That list has already been through a real English word
# list, a frequency floor and an alphabet check, so a pair can only ever be built out of
# words the keyboard already believes in - and a prediction the trie does not contain would
# be a word the keyboard offers and then tries to correct away.
VOCAB = 40_000

# How many words get a follower list, by how often they were followed by anything.
MAX_PREVIOUS = 30_000

# How many followers each keeps. The bar shows three.
#
# Four rather than three, because the fourth is nearly free (three bytes) and the *learned*
# pairs are merged in on top of these at runtime - a word this person actually uses can push
# a shipped follower down a slot, and having a fourth means the bar still fills.
FOLLOWERS = 4

# And for the empty field, where there is no previous word. See `starters` below.
STARTERS = 8

# How often a pair has to have been seen to ship. Under this it is a coincidence, and the
# corpus has tens of millions of coincidences.
#
# Twelve in twenty million lines, which is about one occurrence in twelve million words. Low,
# and it is the *rare* previous words this decides: `thank` -> `you` was seen two hundred
# thousand times and no floor reachable from here touches it. What a floor of twenty-five
# cost, measured, was eleven thousand words at the thin end of the list having no followers
# at all - which is precisely the half of the language where a prediction is worth having,
# because the common half is guessable and the thin end is not.
MIN_PAIR = 12

# ---------------------------------------------------------------- counting
#
# The counting is the only hard part, and it is hard for one reason: there are far more
# distinct pairs than there is memory. Twenty million lines yield something like fifty
# million distinct pairs inside a forty-thousand-word vocabulary, and a Python dict of fifty
# million entries is several gigabytes.
#
# Zipf's law is also the way out. Most distinct pairs have been seen exactly once, so the
# table is swept periodically and the singletons are dropped, and that alone is enough to
# stop it growing without bound: it comes back to roughly the same size after every sweep
# because the singletons are replaced about as fast as they are removed.
#
# This is lossy, and knowingly: a pair seen once just before a sweep is judged on less
# evidence than one seen once just after. It is allowed to come back, and anything that ends
# up in a word's top four has been seen hundreds of times, so what a sweep can actually cost
# is a pair that was going to scrape MIN_PAIR and now does not.
#
# The floor was tried rising - two, three, four, on the theory that a pair still on one
# occurrence after eight million lines is not a pair. It is a bad theory. It reached ten by
# the end of the run and took eleven thousand words' worth of followers with it, because a
# word that is itself rare accumulates its pairs slowly and a rising floor outruns it. Peak
# table size at a flat floor of one is a couple of million entries, which is nothing.
PRUNE_EVERY = 2_000_000


def tokenizer(language):
    """
    A function splitting a line into the words of [language], lowercased.

    Built from the same alphabet `build_dict` filters on, so the two agree by construction
    about what a character is. Everything else - digits, punctuation, the stray Cyrillic in
    an English subtitle - is a separator.
    """
    letters = "".join(sorted(build_dict.ALPHABETS[language]))
    split = re.compile("[^" + re.escape(letters) + "]+")

    def tokens(line):
        # The typographic apostrophe first: subtitles use both, and `don't` and `don’t` are
        # the same word counted twice if it is left alone.
        for piece in split.split(line.lower().replace("’", "'")):
            # A leading or trailing apostrophe is a quotation mark that survived the split.
            piece = piece.strip("'")
            if piece:
                yield piece

    return tokens


def read_vocabulary(language):
    """The commonest VOCAB words of the shipped list, as a set."""
    words = build_dict.read_words(language)
    if language == "en":
        words = build_dict.restore_contractions(words)
    return set(word for word, _ in words[:VOCAB])


def count_pairs(language, vocabulary, tokens):
    """
    Streams the corpus and returns (pairs, totals, starts).

    `pairs` is keyed on `previous * len(vocabulary) + next`, both indices into the ordering
    `index` fixes below - one integer per pair rather than a tuple of two strings, which is
    the difference between a table that fits in memory and one that does not.
    """
    order = sorted(vocabulary)
    index = {word: i for i, word in enumerate(order)}
    span = len(order)

    pairs = Counter()
    totals = Counter()
    starts = Counter()

    url = SOURCE.format(language)
    sys.stderr.write("streaming %s\n" % url)
    floor = 1
    read = 0

    with urllib.request.urlopen(url) as response:
        with gzip.GzipFile(fileobj=response) as stream:
            for raw in stream:
                read += 1
                if read > LINES:
                    break

                line = raw.decode("utf-8", "ignore")
                previous = None
                first = True
                for word in tokens(line):
                    current = index.get(word)
                    if current is None:
                        # Out of vocabulary. It also breaks the chain rather than being
                        # skipped over: the word on either side of a name were never
                        # adjacent, and pretending they were is how `i` comes to be followed
                        # by whatever tends to come after somebody's name.
                        previous = None
                        first = False
                        continue
                    if first:
                        starts[current] += 1
                        first = False
                    if previous is not None:
                        pairs[previous * span + current] += 1
                        totals[previous] += 1
                    previous = current

                if read % PRUNE_EVERY == 0:
                    before = len(pairs)
                    pairs = Counter({k: v for k, v in pairs.items() if v > floor})
                    sys.stderr.write(
                        "  %d lines, pairs %d -> %d (dropped <= %d)\n"
                        % (read, before, len(pairs), floor)
                    )

    sys.stderr.write("read %d lines, %d pairs kept\n" % (read - 1, len(pairs)))
    return order, span, pairs, totals, starts


def weigh(count, total):
    """
    A pair's strength, on the 1-255 scale everything else in the keyboard uses.

    The quantity is P(next | previous) and it is log-scaled, for the same reason the trie's
    frequencies are: the useful range spans three orders of magnitude - `of` is followed by
    `the` a third of the time and by `us` one time in eight hundred - and a linear scale
    would round all but the first few to nothing.

    The ceiling is the part worth explaining. It is below 255 on purpose, and below where
    `UserDictionary` puts a pair it has watched happen five times. A pair the corpus is sure
    of should beat a pair this person has typed once; a pair this person keeps typing should
    beat anything the corpus has to say. That ordering is the whole policy of shipped data
    against learned data, and setting a number here is how it is expressed.
    """
    if count <= 0 or total <= 0:
        return 1
    p = count / total
    value = int(round(CEILING * (1.0 + math.log10(p) / 3.0)))
    return max(1, min(CEILING, value))


CEILING = 190


def build(order, span, pairs, totals, starts):
    """
    Turns the counts into the lists that get written, and returns them by word.

    Returns (followers, starters), where `followers` maps a previous word to a list of
    (word, weight) best first, and `starters` is the same for the beginning of a line.
    """
    # Regroup: the counter is keyed on a packed pair, and what the file wants is a list per
    # previous word. Sorting the keys means every previous word's pairs arrive together, so
    # this is one pass and no intermediate dict of lists.
    grouped = {}
    for key, count in pairs.items():
        if count < MIN_PAIR:
            continue
        previous, following = divmod(key, span)
        grouped.setdefault(previous, []).append((following, count))

    # Which previous words get to ship, by how much evidence there is behind them.
    ranked = sorted(grouped, key=lambda p: -totals[p])[:MAX_PREVIOUS]

    followers = {}
    for previous in ranked:
        best = sorted(grouped[previous], key=lambda pair: -pair[1])[:FOLLOWERS]
        followers[order[previous]] = [
            (order[following], weigh(count, totals[previous])) for following, count in best
        ]

    total_starts = sum(starts.values())
    starters = [
        (order[word], weigh(count, total_starts))
        for word, count in starts.most_common(STARTERS)
    ]
    return followers, starters


def pack(followers, starters):
    """
    Lays the file out. See the format note at the top.

    Two passes over the pool, because the offsets of the strings have to be known before the
    tables that point at them can be written, and those tables come first in the file so a
    reader can find them without having read anything else.
    """
    pool = set(followers)
    for entries in followers.values():
        pool.update(word for word, _ in entries)
    pool.update(word for word, _ in starters)

    # Sorted by UTF-8 bytes, not by Python's string order, because bytes are what the reader
    # compares. The two agree for English and stop agreeing the moment they are not asked to.
    order = sorted(pool, key=lambda word: word.encode("utf-8"))
    if len(order) > 0xFFFF:
        raise SystemExit("pool of %d words exceeds the u16 id space" % len(order))
    ids = {word: i for i, word in enumerate(order)}

    strings = bytearray()
    string_offsets = []
    for word in order:
        encoded = word.encode("utf-8")
        if len(encoded) > 0xFF:
            raise SystemExit("word longer than a length byte: %r" % word)
        string_offsets.append(len(strings))
        strings.append(len(encoded))
        strings.extend(encoded)

    def entries_of(entries):
        out = bytearray()
        out.append(len(entries))
        for word, weight in entries:
            out.extend(struct.pack("<HB", ids[word], weight))
        return out

    blob = bytearray()
    # None rather than 0 for "this word has no followers". Zero is a legitimate position
    # inside the blob - the first list is at it - and the two are only told apart because
    # every offset written to the file is absolute, and the blob never starts at zero.
    positions = [None] * len(order)
    for word, entries in followers.items():
        positions[ids[word]] = len(blob)
        blob.extend(entries_of(entries))

    start_at = len(blob) if starters else None
    if starters:
        blob.extend(entries_of(starters))

    HEADER = 24
    tables = 8 * len(order)
    words_offset = HEADER + tables
    blob_offset = words_offset + len(strings)

    out = bytearray()
    out.extend(
        struct.pack(
            "<4sBBBBIIII",
            MAGIC,
            VERSION,
            0,
            ENTRY_SIZE,
            0,
            len(order),
            words_offset,
            blob_offset + start_at if start_at is not None else 0,
            len(followers),
        )
    )
    for offset in string_offsets:
        out.extend(struct.pack("<I", words_offset + offset))
    for position in positions:
        out.extend(struct.pack("<I", 0 if position is None else blob_offset + position))
    out.extend(strings)
    out.extend(blob)
    return bytes(out)


def main():
    if len(sys.argv) not in (3, 4):
        sys.stderr.write("usage: build_bigrams.py <language> <output> [lines]\n")
        return 1
    language, destination = sys.argv[1], sys.argv[2]
    global LINES
    if len(sys.argv) == 4:
        LINES = int(sys.argv[3])
    if language not in build_dict.ALPHABETS:
        sys.stderr.write("no alphabet defined for %r\n" % language)
        return 1

    tokens = tokenizer(language)
    vocabulary = read_vocabulary(language)
    sys.stderr.write("vocabulary: %d words\n" % len(vocabulary))

    order, span, pairs, totals, starts = count_pairs(language, vocabulary, tokens)
    followers, starters = build(order, span, pairs, totals, starts)
    sys.stderr.write(
        "%d words have followers, %d starters\n" % (len(followers), len(starters))
    )
    # Printed because they are the only real check on any of this. A table that has counted
    # something other than what it meant to still packs perfectly.
    for sample in ("i", "the", "how", "thank", "see", "good", "what"):
        if sample in followers:
            sys.stderr.write(
                "  %-6s -> %s\n"
                % (sample, ", ".join("%s(%d)" % pair for pair in followers[sample]))
            )
    sys.stderr.write("  ^      -> %s\n" % ", ".join("%s(%d)" % p for p in starters))

    data = pack(followers, starters)
    with open(destination, "wb") as handle:
        handle.write(data)
    sys.stderr.write("wrote %s, %.2f MB\n" % (destination, len(data) / 1e6))
    return 0


if __name__ == "__main__":
    sys.exit(main())
