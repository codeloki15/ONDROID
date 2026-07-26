#!/bin/bash
# openWakeWord custom model training for "hey omni" — a dedicated wake-word model to
# replace the generic sherpa-onnx KeywordSpotter (which is tuned for no voice in
# particular). Runs fully on this Mac; produces trained/hey_omni.onnx for on-device
# inference. Stages are idempotent — re-running resumes where it left off.
set -e
cd "$(dirname "$0")"
ROOT="$(pwd)"
log() { echo "[$(date +%H:%M:%S)] $*"; }

# How many synthetic positives to train on. openWakeWord's own guidance is a 20,000
# minimum; TTS generation runs ~35 clips/sec on MPS, so this costs minutes, not hours.
N_SAMPLES=${N_SAMPLES:-20000}
N_SAMPLES_VAL=${N_SAMPLES_VAL:-4000}

# ── Stage 1: python env ─────────────────────────────────────────────────
if [ ! -d venv ]; then
  log "creating venv"
  python3 -m venv venv
fi
source venv/bin/activate
python -c "import openwakeword" 2>/dev/null || {
  log "installing python deps (openwakeword, torch, piper deps)"
  pip -q install --upgrade pip
  # datasets pinned <4: newer versions delegate audio decode to torchcodec (needs
  # system FFmpeg); 3.x decodes via soundfile, which fetch_audio.py relies on.
  pip -q install openwakeword torch torchaudio onnx onnxruntime piper-phonemize-cross \
      soundfile scipy tqdm requests pyyaml mutagen torchinfo torchmetrics speechbrain \
      audiomentations torch-audiomentations acoustics pronouncing "datasets==3.6.0" deep-phonemizer
}
# piper-sample-generator's top-level imports need piper-tts even on the .pt path.
python -c "import piper" 2>/dev/null || { log "installing piper-tts"; pip -q install piper-tts; }

# ── Stage 2: piper sample generator (synthetic speech) ──────────────────
if [ ! -d piper-sample-generator ]; then
  log "cloning piper-sample-generator"
  git clone --depth 1 https://github.com/rhasspy/piper-sample-generator
fi
PIPER_VOICE="$ROOT/piper-sample-generator/models/en_US-libritts_r-medium.pt"
if [ ! -f "$PIPER_VOICE" ]; then
  log "downloading piper libritts_r voice (~200MB)"
  mkdir -p piper-sample-generator/models
  curl -sL -o "$PIPER_VOICE" \
    "https://github.com/rhasspy/piper-sample-generator/releases/download/v2.0.0/en_US-libritts_r-medium.pt"
fi

# ── Stage 3: openwakeword feature banks (negatives + FP validation) ─────
mkdir -p data
python - <<'EOF'
import os, requests
def fetch(url, dest, desc):
    if os.path.exists(dest):
        print(f"[skip] {desc}")
        return
    print(f"[get ] {desc} -> {dest}")
    r = requests.get(url, stream=True, timeout=60)
    r.raise_for_status()
    tmp = dest + ".part"
    with open(tmp, "wb") as f:
        for chunk in r.iter_content(1 << 20):
            f.write(chunk)
    os.rename(tmp, dest)

base = "https://huggingface.co/datasets/davidscripka/openwakeword_features/resolve/main"
fetch(f"{base}/openwakeword_features_ACAV100M_2000_hrs_16bit.npy",
      "data/openwakeword_features_ACAV100M_2000_hrs_16bit.npy",
      "negative features (~17GB)")
fetch(f"{base}/validation_set_features.npy",
      "data/validation_set_features.npy",
      "validation features (~185MB)")
EOF

# ── Stage 4: augmentation assets (RIRs + background noise) ──────────────
# Reverb + noise mixing is what makes a synthetic-TTS-trained model survive a real
# phone mic across the room. Idempotency is by clip COUNT, not a .done marker: these
# are network streams that partially fail, and a marker file would freeze a half-empty
# directory in place (an empty ./mit_rirs is what stalled the previous run).
have() { ls "$1"/*.wav 2>/dev/null | wc -l | tr -d ' '; }

if [ "$(have mit_rirs)" -lt 200 ]; then
  log "downloading MIT RIRs"
  python fetch_audio.py davidscripka/MIT_environmental_impulse_responses train mit_rirs rir 100000
fi
if [ "$(have audioset_16k)" -lt 1400 ]; then
  log "streaming AudioSet background clips"
  python fetch_audio.py agkphysics/AudioSet train audioset_16k as 1500 \
    || echo "[warn] audioset stream incomplete — continuing with what landed"
fi
log "augmentation assets: rirs=$(have mit_rirs) audioset=$(have audioset_16k)"
if [ "$(have mit_rirs)" -lt 50 ] || [ "$(have audioset_16k)" -lt 200 ]; then
  echo "[fatal] too few augmentation clips — training would produce a model that only"
  echo "        works on clean TTS audio. Re-run to retry the downloads." >&2
  exit 1
fi

# ── Stage 5: training config ────────────────────────────────────────────
log "writing hey_omni.yml (n_samples=$N_SAMPLES)"
N_SAMPLES=$N_SAMPLES N_SAMPLES_VAL=$N_SAMPLES_VAL python - <<'EOF'
import os, yaml
bgs = [f"./{d}" for d in ("audioset_16k", "fma_16k")
       if os.path.isdir(d) and len([f for f in os.listdir(d) if f.endswith(".wav")]) > 5]
assert bgs, "no background clips — augmentation would be a no-op"
cfg = {
    "model_name": "hey_omni",
    "target_phrase": ["hey omni"],
    # Confusables observed to trip the current KWS, plus whatever phoneme overlap finds.
    "custom_negative_phrases": ["hey ah me", "a mony", "heyomi", "hail money",
                                "hey armani", "how many", "hey mommy", "hey on me"],
    "n_samples": int(os.environ["N_SAMPLES"]),
    "n_samples_val": int(os.environ["N_SAMPLES_VAL"]),
    "tts_batch_size": 50,
    "augmentation_batch_size": 16,
    # 1 round: >1 exists to squeeze variety out of a small clip set, and at 20k unique
    # clips it buys little while doubling the (CPU-bound) augmentation time.
    "augmentation_rounds": 1,
    "piper_sample_generator_path": "./piper-sample-generator",
    "output_dir": "./trained",
    "rir_paths": ["./mit_rirs"],
    "background_paths": bgs,
    "background_paths_duplication_rate": [1] * len(bgs),
    "false_positive_validation_data_path": "./data/validation_set_features.npy",
    "feature_data_files": {"ACAV100M_sample": "./data/openwakeword_features_ACAV100M_2000_hrs_16bit.npy"},
    "batch_n_per_class": {"ACAV100M_sample": 1024, "adversarial_negative": 50, "positive": 50},
    "model_type": "dnn",
    "layer_size": 32,
    "steps": 50000,
    "max_negative_weight": 1500,
    "target_false_positives_per_hour": 0.2,
}
yaml.safe_dump(cfg, open("hey_omni.yml", "w"))
print("config written; backgrounds:", bgs)
EOF

# ── Stage 6: compat shim for openwakeword's TTS import ──────────────────
# pip openwakeword's train.py does `from generate_samples import generate_samples`
# (the pre-package layout) and calls it WITHOUT a `model` argument. Upstream became a
# package and made `model` required, so a bare re-export raises TypeError on the first
# call — the shim has to bind the voice path back in, the way the flat module's default
# used to.
cat > piper-sample-generator/generate_samples.py <<PY
# Compat shim: upstream became a package and made \`model\` a required argument;
# openwakeword's train.py still imports the old flat module and omits it.
import functools
from piper_sample_generator.__main__ import generate_samples as _generate_samples

generate_samples = functools.partial(
    _generate_samples, model="$PIPER_VOICE"
)
PY

export PYTHONPATH="$ROOT/piper-sample-generator"

# Base feature models (melspectrogram + speech embedding) used by the trainer.
python - <<'EOF'
import openwakeword.utils as u
try: u.download_models()
except TypeError: u.download_models([])
EOF

# ── Stage 7: train ──────────────────────────────────────────────────────
TRAIN="$(python -c 'import openwakeword,os; print(os.path.join(os.path.dirname(openwakeword.__file__), "train.py"))')"
mkdir -p trained

log "phase: generate_clips"
python "$TRAIN" --training_config hey_omni.yml --generate_clips
log "phase: augment_clips (computes openwakeword features)"
python "$TRAIN" --training_config hey_omni.yml --augment_clips
log "phase: train_model"
# The last thing train.py does is convert the ONNX to tflite via onnx_tf, which is
# unmaintained and does not install on modern Python/TF. The ONNX export happens FIRST,
# so a failure there costs us nothing — but it exits non-zero, which `set -e` would
# treat as a training failure. Tolerate it, then assert on the artifact we actually want.
python "$TRAIN" --training_config hey_omni.yml --train_model || \
  log "[warn] train.py exited non-zero (expected: tflite conversion) — checking ONNX"

if [ ! -f trained/hey_omni.onnx ]; then
  echo "[fatal] training did not produce trained/hey_omni.onnx" >&2
  exit 1
fi

log "training complete"
ls -la trained/hey_omni.onnx
