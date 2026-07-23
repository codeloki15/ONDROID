#!/usr/bin/env python
"""Stream a HF audio dataset to 16 kHz mono wavs WITHOUT torchcodec.

datasets>=4 delegates Audio decode to torchcodec (which needs system FFmpeg).
We instead take the raw bytes (Audio(decode=False)) and decode with soundfile
(libsndfile handles wav/flac/mp3) + polyphase resample to 16 kHz.

Usage: fetch_audio.py <dataset> <split> <outdir> <prefix> <max_count> [config]
"""
import io
import os
import sys
from math import gcd

import numpy as np
import soundfile as sf
from datasets import Audio, load_dataset
from datasets.utils.file_utils import xopen
from scipy.signal import resample_poly


def main() -> None:
    dataset, split, outdir, prefix = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
    maxn = int(sys.argv[5])
    config = sys.argv[6] if len(sys.argv) > 6 else None

    ds = load_dataset(dataset, config, split=split, streaming=True)
    ds = ds.cast_column("audio", Audio(decode=False))
    os.makedirs(outdir, exist_ok=True)

    seen = saved = 0
    for row in ds:
        seen += 1
        try:
            raw = row["audio"]["bytes"]
            if raw is None:
                # Folder-backed datasets stream lazy hf:// paths instead of embedded
                # bytes — xopen resolves hf:// (and chained archive) URLs with auth.
                path = row["audio"].get("path")
                if not path:
                    continue
                with xopen(path, "rb") as f:
                    raw = f.read()
            data, sr = sf.read(io.BytesIO(raw), dtype="float32", always_2d=True)
            mono = data.mean(axis=1)
            if sr != 16000:
                g = gcd(int(sr), 16000)
                mono = resample_poly(mono, 16000 // g, int(sr) // g)
            pcm = (mono * 32767.0).clip(-32768, 32767).astype(np.int16)
            sf.write(os.path.join(outdir, f"{prefix}_{saved}.wav"), pcm, 16000)
            saved += 1
        except Exception as e:  # skip undecodable rows, but surface early ones
            if seen <= 5:
                print(f"skip row {seen}: {e}", flush=True)
        if saved >= maxn:
            break
        if saved and saved % 200 == 0:
            print(f"…{saved} clips", flush=True)

    print(f"{dataset}: saved {saved} clips to {outdir}")
    if saved == 0:
        sys.exit(1)


main()
