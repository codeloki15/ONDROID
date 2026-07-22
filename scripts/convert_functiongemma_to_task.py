#!/usr/bin/env python3
"""
Convert google/functiongemma-270m-it (HF safetensors) → MediaPipe `.task` bundle.

FunctionGemma is the Gemma-3 270M architecture, so the standard Gemma-3-270M
LiteRT converter works on its weights. The result is a `.task` (NOT `.litertlm`)
which is what this app's MediaPipe `tasks-genai` engine can load.

Run on your Mac (NOT in the app). Then adb-push the produced .task to the device.

──────────────────────────────────────────────────────────────────────────
SETUP (one-time)
    python3 -m venv ~/fg-convert
    source ~/fg-convert/bin/activate
    pip install -U huggingface-hub litert-torch mediapipe

DOWNLOAD WEIGHTS
    # accept the Gemma license on HF first, then:
    huggingface-cli login
    hf download google/functiongemma-270m-it --local-dir ./_fg_hf

RUN
    python scripts/convert_functiongemma_to_task.py \
        --hf ./_fg_hf \
        --out ./_fg_out

PUSH TO DEVICE (the app looks in externalFilesDir/models)
    adb push ./_fg_out/functiongemma-270m-it_q8.task \
        /sdcard/Android/data/com.locallink.pro/files/models/
──────────────────────────────────────────────────────────────────────────
"""
import argparse
import os


def convert(hf_dir: str, out_dir: str, prefill: int, kv_max: int) -> str:
    from litert_torch.generative.examples.gemma3 import gemma3
    from litert_torch.generative.utilities import converter
    from litert_torch.generative.utilities.export_config import ExportConfig
    from litert_torch.generative.layers import kv_cache

    os.makedirs(out_dir, exist_ok=True)
    print(f"[1/2] Building Gemma-3-270M graph from {hf_dir} …")
    model = gemma3.build_model_270m(hf_dir)

    export_config = ExportConfig()
    export_config.kvcache_layout = kv_cache.KV_LAYOUT_TRANSPOSED
    export_config.mask_as_input = True

    prefix = "functiongemma-270m-it_q8"
    print(f"[1/2] Converting → int8 TFLite (prefill={prefill}, kv_max={kv_max}) …")
    converter.convert_to_tflite(
        model,
        output_path=out_dir,
        output_name_prefix=prefix,
        prefill_seq_len=prefill,
        kv_cache_max_len=kv_max,
        quantize="dynamic_int8",
        export_config=export_config,
    )
    # converter writes "<prefix>_<...>.tflite"; find it
    tflite = next(
        os.path.join(out_dir, f) for f in os.listdir(out_dir) if f.startswith(prefix) and f.endswith(".tflite")
    )
    print(f"      TFLite: {tflite}")
    return tflite


def bundle(tflite: str, hf_dir: str, out_dir: str) -> str:
    from mediapipe.tasks.python.genai import bundler

    task_path = os.path.join(out_dir, "functiongemma-270m-it_q8.task")
    tokenizer = os.path.join(hf_dir, "tokenizer.model")
    if not os.path.exists(tokenizer):
        # Gemma-3 ships tokenizer.model; some mirrors use tokenizer.json — adjust if needed.
        raise FileNotFoundError(f"tokenizer.model not found in {hf_dir} (got: {os.listdir(hf_dir)})")

    print(f"[2/2] Bundling → {task_path} …")
    config = bundler.BundleConfig(
        tflite_model=tflite,
        tokenizer_model=tokenizer,
        start_token="<bos>",
        stop_tokens=["<eos>", "<end_of_turn>"],
        output_filename=task_path,
        prompt_prefix="<start_of_turn>user\n",
        prompt_suffix="<end_of_turn>\n<start_of_turn>model\n",
    )
    bundler.create_bundle(config)
    print(f"      DONE: {task_path}")
    return task_path


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--hf", required=True, help="local dir of downloaded functiongemma-270m-it")
    ap.add_argument("--out", required=True, help="output dir for tflite + .task")
    ap.add_argument("--prefill", type=int, default=1024, help="prefill seq len (default 1024)")
    ap.add_argument("--kv-max", type=int, default=2048, help="kv cache max len (default 2048)")
    args = ap.parse_args()

    tflite = convert(args.hf, args.out, args.prefill, args.kv_max)
    bundle(tflite, args.hf, args.out)
    print("\n✅ Conversion complete. Push the .task to the device:")
    print(
        "   adb push", os.path.join(args.out, "functiongemma-270m-it_q8.task"),
        "/sdcard/Android/data/com.locallink.pro/files/models/",
    )
