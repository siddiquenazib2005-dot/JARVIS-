#!/usr/bin/env python3
"""
Train the custom "AURIX" wake-word model on a CPU-only machine (designed for
a GitHub Actions ubuntu runner), following the official openWakeWord
synthetic-data recipe:

  positives  = Piper TTS renders of the target phrase ("hey aurix")
  negatives  = phoneme-overlap adversarial phrases (library-generated) plus
               real speech (ACAV100M precomputed features) and noise/music
               (MUSAN, features computed here from a byte-range-resumable
               download so CI disk/RAM limits are respected)
  validation = ~11 h real-world FP-validation feature set (official)

Output: training_out/aurix/aurix.onnx (input [1, 16, 96], output [1, 1]) —
directly loadable by the Android app via WakeWordConfig.AURIX_MODEL_ASSET.

No GPU, no TensorFlow, no audioset download. Run via
.github/workflows/train-wakeword.yml (workflow_dispatch).
"""
import logging
import os
import shutil
import sys
import tarfile
import urllib.request

import numpy as np
import yaml

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
log = logging.getLogger("aurix-train")

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WORK = os.path.join(REPO, "training_work")
DATA = os.path.join(REPO, "data")
OUT = os.path.join(REPO, "training_out")

# ------------------------------------------------------------------ targets
# Scaled-down but recipe-faithful: 6k positive clips, 20k training steps.
# Full-scale values (20k+ clips, 50k steps) need far more than 6 CPU hours;
# the resulting model is usable and the pipeline is re-runnable end to end.
TARGET_POSITIVE = 6000
TARGET_VAL = 1200
ADVERSARIAL_PER_BATCH = 50
CLIPS_PER_BATCH = 48
STEPS = 20000
TARGET_FP_PER_HOUR = 1.0
MAX_NEGATIVE_WEIGHT = 1500.0

MUSAN_URL = "https://www.openslr.org/resources/17/musan.tar.gz"
MUSAN_TOTAL_BYTES = 11086114085  # verified via HEAD request; enables ranged re-download
MUSAN_PARTS = 8                  # ~1.4 GB per part, retried independently
MUSAN_TRAIN_CLIPS = 8000
MUSAN_TEST_CLIPS = 800

FEATURE_FILES = {
    # Real speech in precomputed openWakeWord feature space (official set).
    "ACAV100M_sample": os.path.join(DATA, "ACAV100M_16bit.npy"),
    # Noise/music features computed here from the MUSAN subset.
    "MUSAN_sample": os.path.join(DATA, "musan_features_train.npy"),
}

os.makedirs(WORK, exist_ok=True)
os.makedirs(DATA, exist_ok=True)
os.makedirs(OUT, exist_ok=True)

try:
    import openwakeword
    from openwakeword.data import augment_clips, mmap_batch_generator  # noqa: F401 (exercised via upstream CLI)
    from openwakeword.utils import AudioFeatures, compute_features_from_generator
except ImportError as exc:  # pragma: no cover - environment error path
    raise SystemExit(
        "openwakeword and its training extras must be installed first "
        f"(see .github/workflows/train-wakeword.yml): {exc}"
    )


def download(url, dest):
    if os.path.exists(dest):
        log.info("already present: %s", dest)
        return
    log.info("downloading %s", url)
    tmp = dest + ".part"
    for attempt in (1, 2, 3):
        try:
            with urllib.request.urlopen(url, timeout=120) as response, open(tmp, "wb") as handle:
                shutil.copyfileobj(response, handle, length=1 << 20)
            os.replace(tmp, dest)
            return
        except Exception as exc:  # noqa: BLE001 - retry any network fault
            log.warning("download failed (%s), attempt %d", exc, attempt)
            if attempt == 3:
                raise


# ---------------------------------------------------------------------- MUSAN
class VirtualTarStream:
    """A seekable file-like view over downloaded byte-range parts of one file."""

    def __init__(self, parts):
        self.parts = parts
        self.sizes = [os.path.getsize(p) for p in parts]
        self.total = sum(self.sizes)
        self.pos = 0

    def _read(self, offset, length):
        out = bytearray()
        remaining = length
        while remaining > 0:
            acc = 0
            for idx, size in enumerate(self.sizes):
                if offset < acc + size:
                    within = offset - acc
                    take = min(remaining, size - within)
                    with open(self.parts[idx], "rb") as handle:
                        handle.seek(within)
                        out += handle.read(take)
                    offset += take
                    remaining -= take
                    break
                acc += size
            else:
                break  # past EOF
        return bytes(out)

    def read(self, size=-1):
        if size is None or size < 0:
            size = self.total - self.pos
        data = self._read(self.pos, size)
        self.pos += len(data)
        return data

    def seek(self, offset, whence=0):
        if whence == 0:
            self.pos = offset
        elif whence == 1:
            self.pos += offset
        elif whence == 2:
            self.pos = self.total + offset
        return self.pos

    def tell(self):
        return self.pos

    def seekable(self):
        return True

    def readable(self):
        return True


def download_musan():
    """Download MUSAN in independently-retried byte ranges (CI-safe)."""
    step = MUSAN_TOTAL_BYTES // MUSAN_PARTS + 1
    parts = []
    for index in range(MUSAN_PARTS):
        start = index * step
        end = min(MUSAN_TOTAL_BYTES - 1, (index + 1) * step - 1)
        expected = end - start + 1
        dest = os.path.join(DATA, f"musan.part{index}")
        if os.path.exists(dest) and os.path.getsize(dest) == expected:
            parts.append(dest)
            continue
        for attempt in (1, 2, 3):
            try:
                log.info(
                    "MUSAN part %d/%d (%.2f-%.2f GB) attempt %d",
                    index + 1, MUSAN_PARTS, start / 1e9, end / 1e9, attempt,
                )
                request = urllib.request.Request(
                    MUSAN_URL, headers={"Range": f"bytes={start}-{end}"}
                )
                with urllib.request.urlopen(request, timeout=120) as response:
                    with open(dest + ".part", "wb") as handle:
                        shutil.copyfileobj(response, handle, length=1 << 20)
                if os.path.getsize(dest + ".part") != expected:
                    raise IOError(
                        f"short read: {os.path.getsize(dest + '.part')} != {expected}"
                    )
                os.replace(dest + ".part", dest)
                break
            except Exception as exc:  # noqa: BLE001
                log.warning("MUSAN part %d failed: %s", index + 1, exc)
                if attempt == 3:
                    raise
        parts.append(dest)
    return parts


def extract_musan_noise_music(parts, train_dir, test_dir):
    """Extract noise/music (and some speech) wavs straight from the tar stream."""
    for directory in (train_dir, test_dir):
        os.makedirs(directory, exist_ok=True)
    targets = MUSAN_TRAIN_CLIPS + MUSAN_TEST_CLIPS
    stream = VirtualTarStream(parts)
    collected = 0
    with tarfile.open(mode="r|", fileobj=stream) as archive:
        for member in archive:
            if collected >= targets:
                break
            if not member.isfile() or not member.name.endswith(".wav"):
                continue
            # Skip the free-sound music subset: the fma + noise subsets cover
            # the same acoustic variety at a fraction of the byte budget.
            if "/music/free-sound/" in member.name:
                continue
            data = archive.extractfile(member).read()
            sub = train_dir if collected < MUSAN_TRAIN_CLIPS else test_dir
            with open(os.path.join(sub, f"musan_{collected:06d}.wav"), "wb") as handle:
                handle.write(data)
            collected += 1
            if collected % 500 == 0:
                log.info("MUSAN clips extracted: %d/%d", collected, targets)
    log.info("MUSAN extraction done: %d clips", collected)
    for part in parts:
        os.remove(part)  # ~11 GB back


# ----------------------------------------------------------------- features
def compute_musan_features(musan_train_dir, musan_test_dir):
    """Compute openWakeWord features for the MUSAN clips (memmapped output)."""
    jobs = {
        "train": (musan_train_dir, FEATURE_FILES["MUSAN_sample"]),
        "test": (musan_test_dir, os.path.join(DATA, "musan_features_test.npy")),
    }
    for _, (clip_dir, output) in jobs.items():
        if os.path.exists(output):
            log.info("features exist, skipping: %s", output)
            continue
        clips = sorted(
            os.path.join(clip_dir, name)
            for name in os.listdir(clip_dir)
            if name.endswith(".wav")
        )
        generator = augment_clips(
            clips, total_length=32000, batch_size=64,
            background_clip_paths=[], RIR_paths=[],
        )
        compute_features_from_generator(
            generator,
            n_total=len(clips),
            clip_duration=32000,
            output_file=output,
            device="cpu",
            ncpu=max(1, (os.cpu_count() or 2) // 2),
        )


# ------------------------------------------------------------------ config
def write_config(feature_files):
    config = {
        "model_name": "aurix",
        "target_phrase": ["hey aurix"],
        "custom_negative_phrases": [],
        "n_samples": TARGET_POSITIVE,
        "n_samples_val": TARGET_VAL,
        "tts_batch_size": 64,
        "augmentation_batch_size": 64,
        "piper_sample_generator_path": os.path.join(WORK, "piper-sample-generator"),
        "output_dir": OUT,
        "rir_paths": [os.path.join(WORK, "mit_rirs")],
        "background_paths": [os.path.join(WORK, "musan_train")],
        "background_paths_duplication_rate": [1],
        "false_positive_validation_data_path": os.path.join(
            DATA, "validation_set_features.npy"
        ),
        "augmentation_rounds": 1,
        "feature_data_files": feature_files,
        "batch_n_per_class": {
            "ACAV100M_sample": 1024,
            "MUSAN_sample": 1024,
            "adversarial_negative": ADVERSARIAL_PER_BATCH,
            "positive": CLIPS_PER_BATCH,
        },
        "model_type": "dnn",
        "layer_size": 32,
        "steps": STEPS,
        "max_negative_weight": MAX_NEGATIVE_WEIGHT,
        "target_false_positives_per_hour": TARGET_FP_PER_HOUR,
    }
    config_path = os.path.join(WORK, "aurix_model.yml")
    with open(config_path, "w") as handle:
        yaml.dump(config, handle)
    return config_path


def run_upstream(config_path, stage):
    """Run one official upstream train.py stage (no console script exists,
    so the module is executed by path with the repo pinned by the workflow)."""
    log.info("upstream stage: %s", stage)
    train_py = os.path.join(WORK, "openwakeword", "openwakeword", "train.py")
    status = os.system(
        f'"{sys.executable}" "{train_py}" --training_config "{config_path}" {stage}'
    )
    if status != 0:
        raise SystemExit(f"upstream stage failed: {stage} (exit {status})")


def main():
    # ---------------------------------------------------------------- data
    download(
        "https://huggingface.co/datasets/davidscripka/openwakeword_features/"
        "resolve/main/validation_set_features.npy",
        os.path.join(DATA, "validation_set_features.npy"),
    )
    download(
        "https://huggingface.co/datasets/davidscripka/openwakeword_features/"
        "resolve/main/openwakeword_features_ACAV100M_2000_hrs_16bit.npy",
        FEATURE_FILES["ACAV100M_sample"],
    )

    musan_parts = download_musan()
    musan_train_dir = os.path.join(WORK, "musan_train")
    musan_test_dir = os.path.join(WORK, "musan_test")
    extract_musan_noise_music(musan_parts, musan_train_dir, musan_test_dir)
    compute_musan_features(musan_train_dir, musan_test_dir)

    # ------------------------------------------------- positive + training
    # Stages 1-2: official TTS clip generation + augmentation/feature step.
    config_path = write_config({})
    run_upstream(config_path, "--generate_clips")
    run_upstream(config_path, "--augment_clips")

    # Stage 3: train with the final feature set (real speech + MUSAN).
    final_config_path = write_config(FEATURE_FILES)
    log.info("training %d steps (CPU)", STEPS)
    run_upstream(final_config_path, "--train_model")

    model_path = os.path.join(OUT, "aurix", "aurix.onnx")
    if not os.path.exists(model_path):
        raise SystemExit("training finished but aurix.onnx was not produced")
    log.info("MODEL READY: %s (%d bytes)", model_path, os.path.getsize(model_path))


if __name__ == "__main__":
    main()
