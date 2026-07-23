#!/bin/bash
# openWakeWord custom model training for "hey omni" — Phase B of the wake-word fix.
# Runs fully on this Mac; produces hey_omni.onnx for on-device inference.
# Stages are idempotent — re-running resumes where it left off.
set -e
cd "$(dirname "$0")"
ROOT="$(pwd)"
log() { echo "[$(date +%H:%M:%S)] $*"; }

# ── Stage 1: python env ─────────────────────────────────────────────────
if [ ! -d venv ]; then
  log "creating venv"
  python3 -m venv venv
fi
source venv/bin/activate
python -c "import openwakeword" 2>/dev/null || {
  log "installing python deps (openwakeword, torch, piper deps)"
  pip -q install --upgrade pip
  pip -q install openwakeword torch torchaudio onnx onnxruntime piper-phonemize-cross \
      soundfile scipy tqdm requests pyyaml mutagen torchinfo torchmetrics speechbrain \
      audiomentations torch-audiomentations acoustics pronouncing datasets deep-phonemizer
}

# ── Stage 2: piper sample generator (synthetic positives) ───────────────
if [ ! -d piper-sample-generator ]; then
  log "cloning piper-sample-generator"
  git clone --depth 1 https://github.com/rhasspy/piper-sample-generator
fi
if [ ! -f piper-sample-generator/models/en_US-libritts_r-medium.pt ]; then
  log "downloading piper libritts_r voice (~500MB)"
  mkdir -p piper-sample-generator/models
  curl -sL -o piper-sample-generator/models/en_US-libritts_r-medium.pt \
    "https://github.com/rhasspy/piper-sample-generator/releases/download/v2.0.0/en_US-libritts_r-medium.pt"
fi

# ── Stage 3: openwakeword training assets (negatives, RIRs, validation) ─
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
      "negative features (~2GB)")
fetch(f"{base}/validation_set_features.npy",
      "data/validation_set_features.npy",
      "validation features (~300MB)")
EOF

log "setup complete — training starts next"

# ── Stage 4: generate synthetic positives + adversarial negatives ───────
# The repo is a package now (no top-level generate_samples.py): run it as a module
# with PYTHONPATH at the repo root (piper_sample_generator + piper_train live there).
# Its top-level imports need piper-tts even for the .pt generator path.
python -c "import piper" 2>/dev/null || { log "installing piper-tts"; pip -q install piper-tts; }
export PYTHONPATH="$ROOT/piper-sample-generator"
mkdir -p generated/positive generated/negative
if [ ! -f generated/positive/.done ]; then
  log "generating 3000 'hey omni' samples"
  python -m piper_sample_generator "hey omni" \
    --model piper-sample-generator/models/en_US-libritts_r-medium.pt \
    --max-samples 3000 --batch-size 50 --output-dir generated/positive
  touch generated/positive/.done
fi
if [ ! -f generated/negative/.done ]; then
  log "generating adversarial negatives"
  python -m piper_sample_generator \
    "hey ah me, a mony, heyomi, hail money, hey armani, how many" \
    --model piper-sample-generator/models/en_US-libritts_r-medium.pt \
    --max-samples 1500 --batch-size 50 --output-dir generated/negative
  touch generated/negative/.done
fi

log "sample generation complete"

# ── Stage 5: augmentation assets (RIRs + background noise) ──────────────
# Reverb + noise mixing is what makes a synthetic-TTS-trained model survive a real
# phone mic across the room. RIRs: MIT environmental impulse responses. Backgrounds:
# AudioSet clips + FMA music, streamed via HF datasets and saved as 16 kHz wavs.
if [ ! -f mit_rirs/.done ]; then
  log "downloading MIT RIRs"
  mkdir -p mit_rirs
  python - <<'EOF'
import os, numpy as np, scipy.io.wavfile
from datasets import load_dataset, Audio
ds = load_dataset("davidscripka/MIT_environmental_impulse_responses", split="train", streaming=True)
ds = ds.cast_column("audio", Audio(sampling_rate=16000))
n = 0
for row in ds:
    a = row["audio"]
    name = os.path.basename(a.get("path") or f"rir_{n}.wav")
    scipy.io.wavfile.write(os.path.join("mit_rirs", name), 16000,
                           (np.asarray(a["array"]) * 32767).astype(np.int16))
    n += 1
print("RIRs saved:", n)
EOF
  touch mit_rirs/.done
fi

if [ ! -f audioset_16k/.done ]; then
  log "streaming AudioSet background clips (1500)"
  mkdir -p audioset_16k
  python - <<'EOF' || echo "[warn] audioset stream failed — continuing without it"
import os, numpy as np, scipy.io.wavfile
from datasets import load_dataset, Audio
ds = load_dataset("agkphysics/AudioSet", split="train", streaming=True)
ds = ds.cast_column("audio", Audio(sampling_rate=16000))
n = 0
for row in ds:
    a = row["audio"]
    scipy.io.wavfile.write(os.path.join("audioset_16k", f"as_{n}.wav"), 16000,
                           (np.asarray(a["array"]) * 32767).astype(np.int16))
    n += 1
    if n >= 1500: break
print("AudioSet clips saved:", n)
EOF
  touch audioset_16k/.done
fi

if [ ! -f fma_16k/.done ]; then
  log "streaming FMA music clips (700)"
  mkdir -p fma_16k
  python - <<'EOF' || echo "[warn] fma stream failed — continuing without it"
import os, numpy as np, scipy.io.wavfile
from datasets import load_dataset, Audio
ds = load_dataset("rudraml/fma", name="small", split="train", streaming=True)
ds = ds.cast_column("audio", Audio(sampling_rate=16000))
n = 0
for row in ds:
    a = row["audio"]
    scipy.io.wavfile.write(os.path.join("fma_16k", f"fma_{n}.wav"), 16000,
                           (np.asarray(a["array"]) * 32767).astype(np.int16))
    n += 1
    if n >= 700: break
print("FMA clips saved:", n)
EOF
  touch fma_16k/.done
fi

# ── Stage 6: train hey_omni (openwakeword official pipeline) ────────────
# pip openwakeword's train.py still does `from generate_samples import generate_samples`
# (pre-package layout) — shim the old flat module at the repo root it sys.path-inserts.
cat > piper-sample-generator/generate_samples.py <<'PY'
# Compat shim: upstream became a package; openwakeword's train.py imports the old
# flat module. Re-export the function it needs.
from piper_sample_generator.__main__ import generate_samples  # noqa: F401
PY

# Seed the pipeline's clip layout with our pre-generated samples (seed_ prefix so
# train.py's top-up generation, which numbers from 0, never collides).
mkdir -p trained/hey_omni/positive_train trained/hey_omni/negative_train
if [ ! -f trained/hey_omni/.seeded ]; then
  log "seeding pre-generated clips into training layout"
  for f in generated/positive/*.wav; do [ -e "$f" ] && mv "$f" "trained/hey_omni/positive_train/seed_$(basename "$f")"; done
  for f in generated/negative/*.wav; do [ -e "$f" ] && mv "$f" "trained/hey_omni/negative_train/seed_$(basename "$f")"; done
  touch trained/hey_omni/.seeded
fi

log "writing hey_omni.yml"
python - <<'EOF'
import os, yaml
bgs = [f"./{d}" for d in ("audioset_16k", "fma_16k")
       if os.path.isdir(d) and len([f for f in os.listdir(d) if f.endswith(".wav")]) > 5]
assert bgs, "no background clips downloaded — augmentation would be no-op"
cfg = {
    "model_name": "hey_omni",
    "target_phrase": ["hey omni"],
    "custom_negative_phrases": ["hey ah me", "a mony", "heyomi", "hail money", "hey armani", "how many"],
    "n_samples": 3000,
    "n_samples_val": 500,
    "tts_batch_size": 50,
    "augmentation_batch_size": 16,
    "augmentation_rounds": 2,
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

# Base feature models (melspectrogram + speech embedding) used by the trainer.
python - <<'EOF'
import openwakeword.utils as u
try: u.download_models()
except TypeError: u.download_models([])
EOF

TRAIN="$(python -c 'import openwakeword,os; print(os.path.join(os.path.dirname(openwakeword.__file__), "train.py"))')"
export PYTHONPATH="$ROOT/piper-sample-generator"
log "phase: generate_clips (top-up + val splits)"
python "$TRAIN" --training_config hey_omni.yml --generate_clips
log "phase: augment_clips"
python "$TRAIN" --training_config hey_omni.yml --augment_clips
log "phase: train_model"
python "$TRAIN" --training_config hey_omni.yml --train_model
log "training complete — model at trained/hey_omni.onnx"
ls -la trained/*.onnx 2>/dev/null || true
