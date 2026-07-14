#!/usr/bin/env python3
"""Compare Figma baseline and rendered PNGs without writing outside target/."""

import argparse
from pathlib import Path

from PIL import Image, ImageChops


def parse_args():
    parser = argparse.ArgumentParser(description="Pixel-compare two PNGs for task-console visual QA.")
    parser.add_argument("baseline", type=Path)
    parser.add_argument("actual", type=Path)
    parser.add_argument("--tolerance", type=int, default=16, help="per RGB channel tolerance (default: 16)")
    parser.add_argument("--max-diff-ratio", type=float, default=0.02, help="maximum changed-pixel ratio (default: 0.02)")
    return parser.parse_args()


def main():
    args = parse_args()
    baseline = Image.open(args.baseline).convert("RGB")
    actual = Image.open(args.actual).convert("RGB")
    if baseline.size != actual.size:
        raise SystemExit(f"image dimensions differ: baseline={baseline.size}, actual={actual.size}")

    output = Path("target/visual-regression")
    output.mkdir(parents=True, exist_ok=True)
    diff = ImageChops.difference(baseline, actual)
    changed = sum(1 for pixel in diff.get_flattened_data() if any(channel > args.tolerance for channel in pixel))
    ratio = changed / (baseline.width * baseline.height)
    diff.save(output / "diff.png")
    (output / "result.txt").write_text(
        f"changed_pixels={changed}\nchanged_ratio={ratio:.6f}\ntolerance={args.tolerance}\n", encoding="utf-8"
    )
    print(f"changed_ratio={ratio:.6f} ({changed} pixels), tolerance={args.tolerance}")
    if ratio > args.max_diff_ratio:
        raise SystemExit(f"visual regression exceeds max diff ratio {args.max_diff_ratio:.6f}")


if __name__ == "__main__":
    main()
