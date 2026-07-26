#!/usr/bin/env python
"""Run openwakeword's train.py with torchaudio's file I/O backed by soundfile.

torchaudio >= 2.9 removed its native decoding backends and delegates `load`/`info` to
torchcodec, which needs a system FFmpeg. openwakeword's augmentation calls both on every
clip, so `--augment_clips` dies with "TorchCodec is required" partway through a run that
has already spent an hour generating audio.

Everything the pipeline touches is 16-bit PCM WAV, which libsndfile reads directly — so we
substitute soundfile rather than take on an FFmpeg dependency. (fetch_audio.py avoids
torchcodec the same way, for the same reason.) Only `load` and `info` are used by
openwakeword.data, and only `num_frames`/`sample_rate`/`num_channels` are read off `info`.

Usage: train_shim.py --training_config hey_omni.yml --augment_clips
"""
import os
import runpy
import sys
from math import gcd
from types import SimpleNamespace

import numpy as np
import soundfile as sf
import torch
import torchaudio
from scipy.signal import resample_poly


TARGET_SR = 16000


def _load(uri, *args, **kwargs):
    """torchaudio.load → (waveform[channels, frames] float32, sample_rate).

    Resamples to 16 kHz. The piper voice synthesises at its own native rate (22050 Hz for
    libritts_r) with no option to change it, while openwakeword's augmentation hard-rejects
    anything that isn't 16 kHz ("Clip does not have the correct sample rate!"). Every stage
    downstream — melspectrogram, embeddings, the precomputed negative feature banks — is
    16 kHz, so converting on load is the honest place to reconcile that.
    """
    data, sr = sf.read(str(uri), dtype="float32", always_2d=True)
    if sr != TARGET_SR:
        g = gcd(int(sr), TARGET_SR)
        data = resample_poly(data, TARGET_SR // g, int(sr) // g, axis=0)
    return torch.from_numpy(np.ascontiguousarray(data.T, dtype=np.float32)), TARGET_SR


def _info(uri, *args, **kwargs):
    i = sf.info(str(uri))
    return SimpleNamespace(
        num_frames=i.frames,
        sample_rate=i.samplerate,
        num_channels=i.channels,
        bits_per_sample=16,
        encoding=i.subtype,
    )


torchaudio.load = _load
torchaudio.info = _info


# ── DataLoader workers: force fork on macOS ─────────────────────────────────
# train.py builds `DataLoader(IterDataset(batch_generator), num_workers=cpu_count()//2)`,
# and that generator closes over lambdas (train.py ~836). macOS defaults to the "spawn"
# start method, which pickles the dataset to each worker — and a lambda cannot be pickled
# by qualified name, so training dies at step 0 with
#   PicklingError: Can't pickle <function <lambda>>: attribute lookup <lambda> on __main__
# Upstream never hits this because Linux defaults to "fork", which inherits memory instead
# of pickling. We select the same thing explicitly. The workers only walk a numpy memmap,
# so forking a process that has already loaded torch is safe enough here.
#
# Set OWW_DATALOADER_WORKERS=0 to sidestep worker processes entirely if fork misbehaves;
# training then reads batches inline, which is slower but has no multiprocessing at all.
if sys.platform == "darwin":
    import multiprocessing

    multiprocessing.set_start_method("fork", force=True)

_workers_override = os.environ.get("OWW_DATALOADER_WORKERS")
if _workers_override is not None:
    import torch.utils.data as _tud

    _DataLoader = _tud.DataLoader

    class _PatchedDataLoader(_DataLoader):
        def __init__(self, *a, **kw):
            if kw.get("num_workers"):
                kw["num_workers"] = int(_workers_override)
                if not kw["num_workers"]:
                    kw.pop("prefetch_factor", None)  # invalid when num_workers == 0
            super().__init__(*a, **kw)

    _tud.DataLoader = _PatchedDataLoader
    torch.utils.data.DataLoader = _PatchedDataLoader

import openwakeword  # noqa: E402  (imported after patching, so data.py picks up the shim)
import openwakeword.data  # noqa: E402
import openwakeword.utils  # noqa: E402


# ── Make training features reproducible at inference time ───────────────────
# The melspectrogram ONNX ends in ReduceMax -> Sub -> Clip: librosa's
# power_to_db(top_db=80), whose floor is the max of THE WHOLE INPUT TENSOR. Two
# consequences, both of which silently poison training:
#
#   1. openwakeword computes features on batches of clips (embed_clips ->
#      _get_melspectrogram(batch)), so the floor is set by the loudest clip in the batch.
#      The same clip measured alone vs. batched differed by 28.9 here — a random offset
#      on every training example that inference can never reproduce.
#   2. create_fixed_size_clip pads with digital zeros (-inf dB), which guarantees the
#      floor binds. Whole-clip and streaming melspectrograms of the same audio then
#      differed by 104; with any real noise floor they agree exactly (0.000).
#
# Trained that way, the model scored 0.73 recall against its own feature file and 0/64 on
# real audio through any path. Two patches, and the class of bug goes away.

def _embeddings_from_melspec_batch2(self, melspec):
    """Compute one window's embedding the way inference does: as a batch of TWO.

    THIS IS THE ONE THAT MATTERS. Under onnxruntime the embedding model returns
    materially different values for a batch of 1 than for any batch >= 2, and batch >= 2 is
    the correct answer — it is what the pretrained openwakeword models respond to
    (`alexa_v0.1` scores 1.0000 on `alexa_test.wav` with batch 2 and 0.0000 with batch 1).

    openwakeword's own CPU path computes training features ONE WINDOW AT A TIME:

        result = np.array(pool.map(self._get_embeddings_from_melspec, batch, ...))

    ...which lands squarely in the broken batch-1 regime. The CUDA branch right above it
    passes the whole batch instead, so models trained on a GPU — i.e. the released ones —
    got the correct features and CPU-trained models silently do not.

    Left alone, this trains a model against features no phone can reproduce: ours scored
    0.73 recall on its own feature file and 0/64 on real audio through every path we tried.
    OpenWakeWordDetector.embedWindow does exactly the same duplication on device.
    """
    if melspec.shape[0] != 1:
        melspec = melspec[None, ]
    pair = np.repeat(melspec.astype(np.float32), 2, axis=0)
    return self.embedding_model_predict(pair)[0]


openwakeword.utils.AudioFeatures._get_embeddings_from_melspec = _embeddings_from_melspec_batch2

_orig_create_fixed_size_clip = openwakeword.data.create_fixed_size_clip
# ~-70 dBFS, in the [-1, 1] scale this function works in (augment_clips multiplies by
# 32767 only at the very end). Below anything audible, far above the -inf dB of digital
# silence, and about where a real phone microphone's noise floor sits.
NOISE_FLOOR = float(os.environ.get("PAD_NOISE_FLOOR", 3e-4))


def _padded_with_noise_floor(x, n_samples, sr=16000, **kwargs):
    out = _orig_create_fixed_size_clip(x, n_samples, sr, **kwargs)
    arr = np.asarray(out)
    silent = arr == 0
    if silent.any():
        arr = arr.copy()
        arr[silent] = np.random.normal(0, NOISE_FLOOR, int(silent.sum()))
    return arr.astype(np.float32)


openwakeword.data.create_fixed_size_clip = _padded_with_noise_floor

# Guarded so this module can also be imported purely to install the patches above
# (diagnostic scripts need the same soundfile-backed torchaudio that training used).
if __name__ == "__main__":
    train_py = os.path.join(os.path.dirname(openwakeword.__file__), "train.py")
    sys.argv = [train_py] + sys.argv[1:]
    runpy.run_path(train_py, run_name="__main__")
