#!/usr/bin/env python
"""Atomically write a small validated JSON file.

Use this helper from subagent prompts instead of the interactive write tool.
It validates JSON first, normalizes formatting, then replaces the target file.
"""

from __future__ import annotations

import argparse
import base64
import binascii
import json
import sys
from pathlib import Path
from typing import Any


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Write a validated JSON file atomically.")
    parser.add_argument("--path", required=True, help="Target JSON file path.")
    input_group = parser.add_mutually_exclusive_group()
    input_group.add_argument("--json", help="JSON payload as a command argument.")
    input_group.add_argument("--json-base64", help="Base64-encoded UTF-8 JSON payload.")
    parser.add_argument(
        "--allow-non-object",
        action="store_true",
        help="Allow arrays or scalar JSON values. By default the root must be an object.",
    )
    return parser.parse_args()


def _read_payload(args: argparse.Namespace) -> str:
    if args.json_base64 is not None:
        try:
            raw = base64.b64decode(args.json_base64.encode("ascii"), validate=True)
            return raw.decode("utf-8")
        except (UnicodeEncodeError, binascii.Error, UnicodeDecodeError) as exc:
            raise ValueError(f"Invalid --json-base64 UTF-8 payload: {exc}") from exc
    if args.json is not None:
        return args.json
    return sys.stdin.read()


def _parse_json(payload: str, allow_non_object: bool) -> Any:
    try:
        value = json.loads(payload)
    except json.JSONDecodeError as exc:
        raise ValueError(f"Invalid JSON payload: {exc}") from exc
    if not allow_non_object and not isinstance(value, dict):
        raise ValueError("Invalid JSON payload: root value must be an object.")
    return value


def _atomic_write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    rendered = json.dumps(value, ensure_ascii=False, indent=2) + "\n"
    temp_path = path.with_name(f".{path.name}.tmp")
    temp_path.write_text(rendered, encoding="utf-8")
    temp_path.replace(path)


def main() -> int:
    args = parse_args()
    try:
        payload = _read_payload(args)
        value = _parse_json(payload, args.allow_non_object)
        target = Path(args.path)
        _atomic_write_json(target, value)
    except OSError as exc:
        print(f"Failed to write JSON file: {exc}", file=sys.stderr)
        return 5
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return 2

    print(f"Wrote JSON file to {target}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
