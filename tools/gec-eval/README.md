# Correction quality measurement (issue #41)

Answers one question: **how often do we change text that was already correct?**

That number decides whether we keep improving prompts (#39) or move to a word-tagging
model (#40). Everything argued in those two issues comes from other people's published
results on other people's models. This measures ours.

## Run it

```bash
tools/gec-eval/run.sh              # the only connected device
tools/gec-eval/run.sh <serial>     # a specific one, from `adb devices`
```

Takes a few minutes — fifty sentences at roughly 5–8 s each on a Pixel 10 Pro, less on an
S25 Ultra. **The model must already be downloaded in the app.**

Run it on both phones. They differ enough in speed that it is worth knowing whether they
differ in accuracy too.

It installs the two APKs and drives the run with `am instrument` rather than calling
`connectedDebugAndroidTest`. That is deliberate: AGP uninstalls both APKs when a connected
test finishes, and the uninstall deletes `/sdcard/Android/data/<pkg>/` together with the app's
internal files — so the results file was gone before it could be pulled, and the 1.2 GB model
had to be downloaded again. Driving it by hand leaves both installed.

Raw results land in `tools/gec-eval/results/results-<MODEL>.tsv`, which is worth keeping —
you can re-score it later without re-running the device.

To re-score an existing file without touching the device:

```bash
python tools/gec-eval/score.py tools/gec-eval/results/results-Pixel_10_Pro.tsv --verbose
```

## What it reports

```
  Already-correct sentences we changed anyway: N of 15
```

That is the headline. Then edit counts (fixed / missed / wrongly changed), precision,
recall, F0.5, a per-error-type breakdown, and every wrong change printed in full so you can
see what kind of mistakes they are.

## How to read it

- **Rarely touches the clean sentences** → the problem is smaller than #40 assumes. Prompt
  work (#39) is probably enough.
- **Changes them often** → no prompt fixes that, because it is what a generative model is
  built to do, and #40 becomes the serious option.

Write the number into #41 either way, including if it is inconvenient.

## Why fifteen of the fifty sentences have nothing wrong with them

This is the part that is easy to get wrong, so it is worth stating plainly.

If every test sentence contained a mistake, a model could rewrite everything it sees and
still score well — there is always something there to fix. That would tell us nothing about
the behaviour we are actually worried about.

So fifteen are ordinary, correct messages, several of them casual and full of contractions,
because those are exactly the sentences a model trained on polished prose is tempted to
"improve". For those fifteen the right answer is to change nothing, and every edit is a
mistake of the worst kind — the user's own words, silently altered, in a message they are
about to send.

## A limitation worth knowing

The scorer is a simplified [ERRANT](https://github.com/chrisjbryant/errant). It compares
word-level edit spans, so when the model makes a *partially* correct edit — fixing the
capital in `i go` but not the tense — the span does not match the editor's `I went` and it
is counted as both a miss and a wrong change. That is harsher than ERRANT would be.

**This does not affect the headline number.** On a clean sentence the expected edit set is
empty, so any edit at all is unambiguously wrong — no span matching is involved. The
precision and recall figures on the error sentences are approximate and should be read as
"roughly", not quoted as benchmark scores.

If we later want numbers comparable to published work, run the same TSV through real ERRANT
on a desktop.

## Files

| | |
| --- | --- |
| `androidCMP/src/androidTest/.../GecEvalDataset.kt` | the fifty sentences and their correct versions |
| `androidCMP/src/androidTest/.../GecEvalTest.kt` | runs them on device, writes `results.tsv` |
| `score.py` | scores a results file |
| `run.sh` | builds, installs, runs on device, pulls and scores |

The device test measures and does not assert: a poor correction is the result we are
collecting, not a test failure. Only a model that will not load fails the run.

It also writes nothing to the analytics table — `InferenceEngineImpl` is constructed
directly and the recorder is wired in above it, so a fifty-sentence run does not pollute
your own statistics.
