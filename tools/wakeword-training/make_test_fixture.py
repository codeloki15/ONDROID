#!/usr/bin/env python
"""Emit ground-truth fixtures for OpenWakeWordDetectorTest.

The Kotlin detector is a hand port of openwakeword's streaming feature pipeline, and its
load-bearing details (int16-valued float input, the `x/10 + 2` mel transform, the 76-frame
/ 8-stride windowing) all fail *silently* — a wrong one yields a model that simply never
fires. So we record what the reference implementation scores, chunk by chunk, and make the
port match it.

Writes into app/src/test/resources/oww/:
  positive.wav / negative.wav  — 16 kHz mono 16-bit clips
  expected.json                — per-80ms-chunk scores from the Python reference

Usage: make_test_fixture.py [--model trained/hey_omni.onnx]
"""
import argparse
import json
import os
import shutil
import sys

import numpy as np
import soundfile as sf

ROOT = os.path.dirname(os.path.abspath(__file__))
RES = os.path.abspath(os.path.join(ROOT, "..", "..", "app", "src", "test", "resources", "oww"))
CHUNK = 1280


def as_16k_mono_int16(path):
    data, sr = sf.read(path, dtype="float32", always_2d=True)
    mono = data.mean(axis=1)
    if sr != 16000:
        from math import gcd
        from scipy.signal import resample_poly
        g = gcd(int(sr), 16000)
        mono = resample_poly(mono, 16000 // g, int(sr) // g)
    return (mono * 32767.0).clip(-32768, 32767).astype(np.int16)


def pad(x, seconds=1.0, noise=10.0):
    """Pad head and tail so a short clip still fills the model's 16-frame window.

    Padded with a faint noise floor, NOT digital zeros. The melspectrogram ONNX ends in
    librosa's power_to_db(top_db=80), whose floor is set by the max of whatever you pass
    it; digital silence is -inf dB, which makes that floor bind and puts the whole-clip and
    streaming melspectrograms ~104 apart. A real microphone always has a noise floor, so
    padding with one is both more faithful and numerically stable.
    """
    n = int(16000 * seconds)
    rng = np.random.default_rng(0)
    floor = rng.normal(0, noise, n).clip(-32768, 32767).astype(np.int16)
    return np.concatenate((floor, x, floor))


def chunk_scores(feat, classifier, audio):
    """Replay `audio` in 80 ms steps, one score per step — openwakeword's streaming
    algorithm, with the batch-of-1 embedding bug worked around.

    We deliberately do NOT use `openwakeword.Model.predict` here. Its ONNX streaming path
    runs the embedding model with a batch of 1, and onnxruntime returns different (wrong)
    values for that model at batch 1 than at any batch >= 2 — wrong enough that the stock
    `alexa` model scores 0.0000 on openwakeword's own `alexa_test.wav` instead of 1.0000.
    Generating fixtures from `predict` would enshrine that bug as expected behaviour and
    the Kotlin port would be "verified" against a detector that never fires.
    """
    melspec, embedding = feat
    cin = classifier.get_inputs()[0].name
    window = classifier.get_inputs()[0].shape[1]

    def mel(x):
        out = np.squeeze(melspec.run(None, {"input": x[None, ].astype(np.float32)})[0])
        return out / 10 + 2  # aligns the ONNX export with Google's TF implementation

    def embed(w):
        pair = np.repeat(w[None, :, :, None].astype(np.float32), 2, axis=0)
        return np.squeeze(embedding.run(None, {"input_1": pair})[0])[0]

    raw = np.zeros(0, dtype=np.int16)
    melbuf = np.ones((76, 32))
    feats, scores = [], []
    for i in range(0, len(audio) - CHUNK + 1, CHUNK):
        raw = np.concatenate((raw, audio[i:i + CHUNK]))[-160000:]
        melbuf = np.vstack((melbuf, mel(raw[-min(len(raw), CHUNK + 480):])))[-970:]
        feats.append(embed(melbuf[-76:]))
        if len(feats) >= window:
            x = np.array(feats[-window:])[None, ].astype(np.float32)
            scores.append(float(classifier.run(None, {cin: x})[0][0][0]))
        else:
            scores.append(0.0)  # warmup, matching the port's gating
    return scores


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default=os.path.join(ROOT, "trained", "hey_omni.onnx"))
    # Overrides let the port be verified against a stock openwakeword model (e.g.
    # hey_jarvis) before ours has finished training — the pipeline under test is the same.
    ap.add_argument("--positive")
    ap.add_argument("--negative")
    args = ap.parse_args()

    if not os.path.exists(args.model):
        sys.exit(f"model not found: {args.model} — run setup_and_train.sh first")

    import onnxruntime as ort
    import openwakeword

    base = os.path.join(os.path.dirname(openwakeword.__file__), "resources", "models")
    providers = ["CPUExecutionProvider"]
    feat = (
        ort.InferenceSession(os.path.join(base, "melspectrogram.onnx"), providers=providers),
        ort.InferenceSession(os.path.join(base, "embedding_model.onnx"), providers=providers),
    )
    classifier = ort.InferenceSession(args.model, providers=providers)

    os.makedirs(RES, exist_ok=True)

    # A held-out positive (never seen in training) and an adversarial negative, both from
    # the pipeline's own test splits.
    src = {
        "positive": os.path.join(ROOT, "trained", "hey_omni", "positive_test"),
        "negative": os.path.join(ROOT, "trained", "hey_omni", "negative_test"),
    }
    # Pick clips the model handles correctly. This is NOT the model's quality bar —
    # evaluate_model.py measures recall and false positives per hour over the full held-out
    # sets and is what gates a release. These fixtures exist to pin the Kotlin port to the
    # Python reference chunk-for-chunk, and that comparison is only meaningful on a clip
    # that actually produces signal: a negative the model wrongly fires on would make the
    # port's smoke test fail for a reason that has nothing to do with the port.
    picked = {}
    for label, d in src.items():
        override = getattr(args, label)
        if override:
            picked[label] = override
            continue
        wavs = sorted(f for f in os.listdir(d) if f.endswith(".wav"))
        if not wavs:
            sys.exit(f"no clips in {d}")
        want_fire = label == "positive"
        chosen = None
        for w in wavs[:40]:
            audio = pad(as_16k_mono_int16(os.path.join(d, w)))
            peak = max(chunk_scores(feat, classifier, audio))
            if (peak >= 0.5) == want_fire:
                chosen = os.path.join(d, w)
                break
        if chosen is None:
            print(f"[warn] no {label} clip behaved as expected in the first 40 — using the first")
            chosen = os.path.join(d, wavs[0])
        picked[label] = chosen

    expected = {}
    for label, path in picked.items():
        audio = pad(as_16k_mono_int16(path))
        sf.write(os.path.join(RES, f"{label}.wav"), audio, 16000, subtype="PCM_16")
        expected[label] = chunk_scores(feat, classifier, audio)
        print(f"{label}: {len(expected[label])} chunks, max score {max(expected[label]):.4f}")

    with open(os.path.join(RES, "expected.json"), "w") as f:
        json.dump(expected, f, indent=1)

    # The test loads the classifier from test resources so it doesn't depend on the app's
    # asset packaging; the two feature models are already committed under assets/oww.
    shutil.copy(args.model, os.path.join(RES, "hey_omni.onnx"))
    print(f"wrote fixtures to {RES}")


if __name__ == "__main__":
    main()
