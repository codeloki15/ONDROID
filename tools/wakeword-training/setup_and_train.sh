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
mkdir -p generated/positive generated/negative
if [ ! -f generated/positive/.done ]; then
  log "generating 3000 'hey omni' samples"
  python piper-sample-generator/generate_samples.py "hey omni" \
    --model piper-sample-generator/models/en_US-libritts_r-medium.pt \
    --max-samples 3000 --batch-size 50 --output-dir generated/positive
  touch generated/positive/.done
fi
if [ ! -f generated/negative/.done ]; then
  log "generating adversarial negatives"
  python piper-sample-generator/generate_samples.py \
    "hey ah me, a mony, heyomi, hail money, hey armani, how many" \
    --model piper-sample-generator/models/en_US-libritts_r-medium.pt \
    --max-samples 1500 --batch-size 50 --output-dir generated/negative
  touch generated/negative/.done
fi

log "sample generation complete — run train.py next"
