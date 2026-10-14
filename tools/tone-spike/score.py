"""Scores a tone-spike results file (issue #32).

    python tools/tone-spike/score.py tools/tone-spike/results/results-Pixel_10_Pro.tsv

For each prompt variant it reports:
  - top-1 accuracy: the model's first label is one the human labeller accepted;
  - any-hit rate: at least one of its labels is accepted;
  - per-label precision and recall over every label the model emitted;
  - latency median and max;
  - every sample whose top label was wrong.

A rewrite-baseline file next to the results, if present, is summarised for comparison.
"""

import csv
import os
import statistics
import sys


def load(path):
    with open(path, encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f, delimiter="\t", quoting=csv.QUOTE_NONE))


def labels(field):
    return [x for x in field.split(",") if x]


def score_variant(name, rows):
    n = len(rows)
    top1 = any_hit = empty = 0
    tp, fn, support, emitted, accepted = {}, {}, {}, {}, {}
    wrong = []
    for r in rows:
        want, got = labels(r["expected"]), labels(r["predicted"])
        if not got:
            empty += 1
        if got and got[0] in want:
            top1 += 1
        else:
            wrong.append(r)
        if set(got) & set(want):
            any_hit += 1
        # Recall is against the dominant label only: a secondary label the model skipped is not a
        # miss. Precision is over every label it emitted: accepted if the labeller listed it at all.
        primary = want[0]
        support[primary] = support.get(primary, 0) + 1
        if primary in got:
            tp[primary] = tp.get(primary, 0) + 1
        else:
            fn[primary] = fn.get(primary, 0) + 1
        for g in got:
            emitted[g] = emitted.get(g, 0) + 1
            if g in want:
                accepted[g] = accepted.get(g, 0) + 1

    lat = [int(r["latency_ms"]) for r in rows]
    toks = [int(r["prompt_tokens"]) for r in rows if r["prompt_tokens"]]

    print(f"\n=== {name} ({n} samples) ===")
    print(f"  top-1 accuracy : {top1}/{n} = {top1 / n:.0%}")
    print(f"  any-label hit  : {any_hit}/{n} = {any_hit / n:.0%}")
    print(f"  no valid label : {empty}/{n}")
    print(
        f"  latency        : median {statistics.median(lat):.0f} ms, "
        f"max {max(lat)} ms, min {min(lat)} ms"
    )
    if toks:
        print(f"  prompt tokens  : median {statistics.median(toks):.0f}")
    print("\n  label        recall (dominant)   precision (all emitted)")
    for lab in sorted(set(support) | set(emitted)):
        s = support.get(lab, 0)
        t = tp.get(lab, 0)
        e = emitted.get(lab, 0)
        rec = f"{t}/{s}" if s else "-"
        prec = f"{accepted.get(lab, 0)}/{e}" if e else "-"
        print(f"  {lab:<12} {rec:<19} {prec}")
    if wrong:
        print("\n  top label wrong:")
        for r in wrong:
            print(f"    #{r['id']:>2} want {r['expected']:<22} got {r['predicted'] or '-':<22} raw: {r['raw']}")


def compare_cache(rows, variants):
    """For each X_COLD / X_CACHED pair, lists every sample whose labels or raw answer differ.

    The KV-cached path decodes the prompt's fixed opening and the message in separate batches,
    which can change a greedy answer by a token (see grammarfix_llama.cpp). This is where that shows.
    """
    for cold in [v for v in variants if v.endswith("_COLD")]:
        cached = cold[: -len("_COLD")] + "_CACHED"
        if cached not in variants:
            continue
        a = {r["id"]: r for r in rows if r["variant"] == cold}
        b = {r["id"]: r for r in rows if r["variant"] == cached}
        ids = [i for i in a if i in b]
        same_labels = [i for i in ids if a[i]["predicted"] == b[i]["predicted"]]
        same_raw = [i for i in ids if a[i]["raw"] == b[i]["raw"]]
        lat_a = statistics.median(int(a[i]["latency_ms"]) for i in ids)
        lat_b = statistics.median(int(b[i]["latency_ms"]) for i in ids)
        print(f"\n=== {cold} vs {cached} ===")
        print(f"  same labels     : {len(same_labels)}/{len(ids)}")
        print(f"  same raw answer : {len(same_raw)}/{len(ids)}")
        print(f"  median latency  : {lat_a:.0f} ms -> {lat_b:.0f} ms")
        for i in ids:
            if a[i]["raw"] != b[i]["raw"]:
                print(f"    #{i:>2} want {a[i]['expected']}")
                print(f"        cold  : {a[i]['raw']}")
                print(f"        cached: {b[i]['raw']}")


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    path = sys.argv[1]
    rows = load(path)
    variants = []
    for r in rows:
        if r["variant"] not in variants:
            variants.append(r["variant"])
    for v in variants:
        score_variant(v, [r for r in rows if r["variant"] == v])
    compare_cache(rows, variants)

    baseline = os.path.join(os.path.dirname(path), os.path.basename(path).replace("results-", "rewrite-baseline-"))
    if os.path.exists(baseline) and baseline != path:
        lat = [int(r["latency_ms"]) for r in load(baseline)]
        if lat:
            print(f"\n=== Formal rewrite on the same device ({len(lat)} samples) ===")
            print(f"  latency: median {statistics.median(lat):.0f} ms, max {max(lat)} ms")


if __name__ == "__main__":
    main()
