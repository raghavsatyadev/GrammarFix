#!/usr/bin/env python3
"""Scores the output of GecEvalTest (issue #41).

Reads the results.tsv the on-device test wrote and answers one question above all
others: how often do we change text that was already correct?

Everything is compared at the word level. For each sentence we work out the edits a
human editor would make (input -> expected) and the edits the model actually made
(input -> actual), then compare the two sets:

  fixed            an edit the model made that the editor also made
  missed           an edit the editor made that the model did not
  wrongly changed  an edit the model made that the editor did not

Run: python tools/gec-eval/score.py <results.tsv> [--verbose]
"""

import sys
import difflib
from collections import defaultdict


def words(text):
    """Whitespace tokens. Punctuation stays attached, because punctuation and
    capitalisation are two of the error types we are measuring — stripping them
    would hide exactly the mistakes we care about."""
    return text.split()


def edits(source, target):
    """The set of word-level changes turning source into target.

    An edit is (start, end, replacement): the span of source it covers and what it
    becomes. Two edits are the same edit when they touch the same span and produce
    the same words, which is what lets us compare the model against the editor.
    """
    a, b = words(source), words(target)
    matcher = difflib.SequenceMatcher(a=a, b=b, autojunk=False)
    found = set()
    for tag, i1, i2, j1, j2 in matcher.get_opcodes():
        if tag != "equal":
            found.add((i1, i2, " ".join(b[j1:j2])))
    return found


def read(path):
    rows = []
    with open(path, encoding="utf-8") as handle:
        header = handle.readline()
        if not header.startswith("id\t"):
            sys.exit(f"{path} does not look like a results file (no header row)")
        for line in handle:
            line = line.rstrip("\n")
            if not line:
                continue
            parts = line.split("\t")
            if len(parts) < 6:
                sys.exit(f"malformed row: {line[:80]}")
            rows.append(
                {
                    "id": int(parts[0]),
                    "category": parts[1],
                    "input": parts[2],
                    "expected": parts[3],
                    "actual": parts[4],
                    "latency": int(parts[5]),
                }
            )
    return rows


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    verbose = "--verbose" in sys.argv
    if not args:
        sys.exit(__doc__)
    rows = read(args[0])

    fixed = missed = wrong = 0
    failures = []
    clean_rows, clean_touched = [], []
    by_category = defaultdict(lambda: {"gold": 0, "fixed": 0, "wrong": 0})
    offenders = []
    latencies = []

    for row in rows:
        is_clean = row["input"] == row["expected"]
        if is_clean:
            clean_rows.append(row)
        if not row["actual"]:
            failures.append(row)
            continue
        latencies.append(row["latency"])

        gold = edits(row["input"], row["expected"])
        pred = edits(row["input"], row["actual"])

        hit = gold & pred
        bad = pred - gold
        gap = gold - pred

        fixed += len(hit)
        wrong += len(bad)
        missed += len(gap)

        stats = by_category[row["category"]]
        stats["gold"] += len(gold)
        stats["fixed"] += len(hit)
        stats["wrong"] += len(bad)

        if is_clean and pred:
            clean_touched.append(row)
        if bad:
            offenders.append((row, sorted(bad)))

    total_clean = len(clean_rows)
    touched = len(clean_touched)

    print()
    print("=" * 72)
    print("  THE NUMBER THAT DECIDES #41")
    print("=" * 72)
    if total_clean:
        pct = 100.0 * touched / total_clean
        print(f"  Already-correct sentences we changed anyway: {touched} of {total_clean}  ({pct:.0f}%)")
    else:
        print("  No clean sentences in this sample set — the result cannot answer #41.")
    print()

    for row in clean_touched:
        print(f"   #{row['id']}")
        print(f"     they wrote : {row['input']}")
        print(f"     we changed : {row['actual']}")
    if total_clean and not touched:
        print("   None. The model left every correct sentence alone.")
    print()

    print("=" * 72)
    print("  EDIT COUNTS")
    print("=" * 72)
    print(f"  fixed            {fixed}")
    print(f"  missed           {missed}")
    print(f"  wrongly changed  {wrong}")

    precision = fixed / (fixed + wrong) if (fixed + wrong) else 0.0
    recall = fixed / (fixed + missed) if (fixed + missed) else 0.0
    if precision or recall:
        f05 = (1 + 0.25) * precision * recall / (0.25 * precision + recall)
    else:
        f05 = 0.0
    print()
    print(f"  precision  {precision:.3f}   (of the changes we made, how many were right)")
    print(f"  recall     {recall:.3f}   (of the errors present, how many we caught)")
    print(f"  F0.5       {f05:.3f}   (the standard score; weights precision over recall)")
    print()
    if latencies:
        latencies.sort()
        mid = latencies[len(latencies) // 2]
        print(f"  latency    median {mid} ms, slowest {latencies[-1]} ms")
    if failures:
        print(f"  FAILED     {len(failures)} sentences returned nothing: "
              f"{', '.join('#' + str(r['id']) for r in failures)}")
    print()

    print("=" * 72)
    print("  BY ERROR TYPE")
    print("=" * 72)
    print(f"  {'category':<16}{'caught':>8}{'of':>4}{'errors':>8}{'  wrong changes':>16}")
    for name in sorted(by_category):
        stats = by_category[name]
        print(f"  {name:<16}{stats['fixed']:>8}{'/':>4}{stats['gold']:>8}{stats['wrong']:>16}")
    print()

    if offenders:
        print("=" * 72)
        print("  EVERY WRONG CHANGE, SO YOU CAN SEE WHAT KIND THEY ARE")
        print("=" * 72)
        for row, bad in offenders if verbose else offenders[:12]:
            print(f"  #{row['id']} [{row['category']}]")
            print(f"     in   : {row['input']}")
            print(f"     want : {row['expected']}")
            print(f"     got  : {row['actual']}")
            for _, _, replacement in bad:
                print(f"       -> introduced: {replacement!r}")
            print()
        if not verbose and len(offenders) > 12:
            print(f"  ... {len(offenders) - 12} more; re-run with --verbose to see them all")
            print()


if __name__ == "__main__":
    main()
