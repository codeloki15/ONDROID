#!/usr/bin/env python
"""Measure a trained hey_omni model and refuse to pass off a dead one.

openwakeword's `auto_train` optimises hard for false positives: it DOUBLES the weight on
negative examples after any sequence that misses the FP target, and will happily hand back
a model with 0.0 false positives per hour and 0.14 recall — a wake word that ignores you
six times out of seven. Nothing downstream notices; the app would simply never wake.

So the run is gated here, on the exported ONNX (the artefact that actually ships) rather
than on training-time numbers:

  recall  — fraction of held-out augmented "hey omni" clips that score >= threshold
  FP/hr   — false accepts per hour on openwakeword's 11.3-hour validation stream
  reject  — fraction of held-out adversarial negatives correctly ignored

Exit code is non-zero when recall is below --min-recall, so setup_and_train.sh stops
rather than publishing a model that cannot hear its own wake word.
"""
import argparse
import os
import sys

import numpy as np
import onnxruntime as ort

ROOT = os.path.dirname(os.path.abspath(__file__))
VAL_SET_HOURS = 11.3  # duration of openwakeword's false-positive validation set


def _dynamic_batch(path):
    """Return the model with a symbolic batch dimension.

    torch's exporter writes a fixed batch of 1 (it traces a single example and no
    dynamic_axes are requested), which is right for the phone — the detector scores one
    window at a time — but makes evaluating half a million windows one-at-a-time. Relaxing
    dim 0 changes no weights, only the declared shape.
    """
    import onnx

    m = onnx.load(path)
    for vi in list(m.graph.input) + list(m.graph.output):
        d0 = vi.type.tensor_type.shape.dim[0]
        if d0.HasField("dim_value") and d0.dim_value == 1:
            d0.Clear()
            d0.dim_param = "batch"

    # The exporter also folds the batch size into the flatten: Reshape(x, [1, 1536]).
    # Relaxing the declared input shape alone leaves that constant, so inference still
    # rejects anything but a single window.
    inits = {i.name: i for i in m.graph.initializer}
    for node in m.graph.node:
        if node.op_type != "Reshape" or len(node.input) < 2:
            continue
        init = inits.get(node.input[1])
        if init is None:
            continue
        shape = onnx.numpy_helper.to_array(init)
        if shape.ndim == 1 and shape[0] == 1:
            patched = shape.copy()
            patched[0] = -1
            init.CopyFrom(onnx.numpy_helper.from_array(patched, init.name))
    return m.SerializeToString()


def scores(session, x, batch=8192):
    name = session.get_inputs()[0].name
    out = []
    for i in range(0, len(x), batch):
        out.append(session.run(None, {name: x[i:i + batch].astype(np.float32)})[0].reshape(-1))
    return np.concatenate(out) if out else np.zeros(0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default=os.path.join(ROOT, "trained", "hey_omni.onnx"))
    ap.add_argument("--features", default=os.path.join(ROOT, "trained", "hey_omni"))
    ap.add_argument("--fp-data", default=os.path.join(ROOT, "data", "validation_set_features.npy"))
    ap.add_argument("--threshold", type=float, default=0.5)
    ap.add_argument("--min-recall", type=float, default=0.50)
    # A wake word that fires on its own is as useless as one that never fires, and the
    # first version of this gate checked only recall — it happily passed a model with
    # 217 false wakes per hour. Both directions have to be bounded.
    ap.add_argument("--max-fp-per-hour", type=float, default=2.0)
    args = ap.parse_args()

    if not os.path.exists(args.model):
        sys.exit(f"no model at {args.model}")

    sess = ort.InferenceSession(_dynamic_batch(args.model), providers=["CPUExecutionProvider"])
    window = sess.get_inputs()[0].shape[1]

    pos = np.load(os.path.join(args.features, "positive_features_test.npy"))
    neg = np.load(os.path.join(args.features, "negative_features_test.npy"))
    pos_s, neg_s = scores(sess, pos), scores(sess, neg)

    recall = float((pos_s >= args.threshold).mean())
    reject = float((neg_s < args.threshold).mean())

    # Same construction train.py uses: a stride-1 sliding window over the validation
    # stream, so every frame position is a chance to false-fire.
    fp_stream = np.load(args.fp_data)
    windows = np.lib.stride_tricks.sliding_window_view(fp_stream, window, axis=0)
    windows = np.ascontiguousarray(windows.transpose(0, 2, 1))
    fp_s = scores(sess, windows)
    fp_per_hour = float((fp_s >= args.threshold).sum() / VAL_SET_HOURS)

    print(f"model              : {args.model}")
    print(f"classifier window  : {window} x 96")
    print(f"threshold          : {args.threshold}")
    print(f"recall (positives) : {recall:.3f}  ({int((pos_s >= args.threshold).sum())}/{len(pos_s)} held-out clips)")
    print(f"reject (negatives) : {reject:.3f}  ({int((neg_s < args.threshold).sum())}/{len(neg_s)} adversarial clips)")
    print(f"false positives/hr : {fp_per_hour:.2f}  (over {VAL_SET_HOURS}h of speech/noise/music)")

    # A useful operating-point hint when recall is marginal.
    if len(pos_s):
        for t in (0.3, 0.4, 0.5, 0.6, 0.7):
            r = (pos_s >= t).mean()
            f = (fp_s >= t).sum() / VAL_SET_HOURS
            print(f"   threshold {t:.1f} -> recall {r:.3f}, {f:.2f} fp/hr")

    failures = []
    if recall < args.min_recall:
        failures.append(f"recall {recall:.3f} < {args.min_recall} — this model would rarely "
                        f"wake. LOWER MAX_NEGATIVE_WEIGHT and retrain.")
    if fp_per_hour > args.max_fp_per_hour:
        failures.append(f"{fp_per_hour:.2f} false positives/hour > {args.max_fp_per_hour} — this "
                        f"model wakes on its own. RAISE MAX_NEGATIVE_WEIGHT and retrain.")
    if failures:
        print("\nFAIL: " + "\n      ".join(failures), file=sys.stderr)
        sys.exit(1)
    print("\nOK")


if __name__ == "__main__":
    main()
