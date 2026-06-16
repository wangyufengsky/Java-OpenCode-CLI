#!/usr/bin/env python3
"""Prepare production package comparison tasks for sub-agents."""

from __future__ import annotations

import argparse
import datetime as dt
import html
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import uuid


DEFAULT_TARGET_TARS = ["ecis.tar", "longwl.tar", "nl.tar", "qrwl.tar"]


class PackageCompareError(RuntimeError):
    """Raised for actionable production package preparation failures."""


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Prepare production package comparison tasks for sub-agents."
    )
    parser.add_argument("--left-branch", required=True, help="Left/old git branch name.")
    parser.add_argument("--right-branch", required=True, help="Right/new git branch name.")
    parser.add_argument(
        "--prepare-only",
        action="store_true",
        help="Required workflow mode. Prepares branches, tasks.json, summary.json, and index.md only.",
    )
    parser.add_argument(
        "--out",
        help="Output directory. Defaults to ./workspace/<left>_vs_<right>_<timestamp> under the current working directory.",
    )
    parser.add_argument(
        "--config",
        help=(
            "Config JSON path. Defaults to config/production-package-compare.json, "
            "then production-package-compare.config.json in the current working directory, "
            "then this skill's config.json."
        ),
    )
    parser.add_argument("--bc-path", help="Optional BCompare.exe path to include in each sub-agent task.")
    parser.add_argument(
        "--template-dir",
        help="Directory containing index.md summary template. Defaults to this skill's templates directory.",
    )
    parser.add_argument(
        "--overwrite",
        action="store_true",
        help="Allow deleting and replacing an existing --out directory. Without it, existing --out paths get a timestamp suffix.",
    )
    args = parser.parse_args(argv)

    try:
        run(args)
    except PackageCompareError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    return 0


def run(args: argparse.Namespace) -> None:
    if not args.prepare_only:
        raise PackageCompareError(
            "production-package-compare is sub-agent only. Run with --prepare-only, then have the main agent spawn sub-agents for tasks.json."
        )

    skill_dir = Path(__file__).resolve().parents[1]
    config_path = resolve_config_path(skill_dir, args.config)
    config = load_config(config_path)
    git_url = str(config["git_url"])
    download_mode = str(config.get("download_mode", "auto")).strip().lower()
    if download_mode not in {"auto", "archive", "clone"}:
        raise PackageCompareError("download_mode must be one of: auto, archive, clone.")
    target_tars = config.get("target_tars") or DEFAULT_TARGET_TARS
    validate_target_tars(target_tars)
    template_dir = resolve_template_dir(skill_dir, args.template_dir)

    out_dir = resolve_output_dir(args.out, args.left_branch, args.right_branch, args.overwrite)
    if args.overwrite:
        remove_tree(out_dir)

    print(f"working directory: {Path.cwd()}")
    print(f"config file: {config_path}")
    print(f"output directory: {out_dir}")

    branches_dir = out_dir / "branches"
    reports_dir = out_dir / "reports"
    branches_dir.mkdir(parents=True, exist_ok=True)
    reports_dir.mkdir(parents=True, exist_ok=True)

    left_dir = branches_dir / safe_dir_name(args.left_branch)
    right_dir = branches_dir / safe_dir_name(args.right_branch)

    export_branch(git_url, args.left_branch, left_dir, download_mode, target_tars)
    export_branch(git_url, args.right_branch, right_dir, download_mode, target_tars)

    tasks, package_statuses = build_task_plan(
        target_tars=target_tars,
        left_dir=left_dir,
        right_dir=right_dir,
        reports_dir=reports_dir,
        left_branch=args.left_branch,
        right_branch=args.right_branch,
        bc_path=args.bc_path,
    )

    summary = {
        "generated_at": dt.datetime.now(dt.timezone.utc).astimezone().isoformat(timespec="seconds"),
        "mode": "sub-agent-prepare-only",
        "git_url": redact_secret(git_url),
        "download_mode": download_mode,
        "left_branch": args.left_branch,
        "right_branch": args.right_branch,
        "output_dir": str(out_dir),
        "branches": {
            "left": str(left_dir),
            "right": str(right_dir),
        },
        "packages": package_statuses,
        "task_count": len(tasks),
        "final_report": str(out_dir / "production-analysis.md"),
    }

    write_json(out_dir / "tasks.json", tasks)
    write_json(out_dir / "summary.json", summary)
    write_summary_index(
        out_dir / "index.md",
        summary=summary,
        tasks=tasks,
        package_statuses=package_statuses,
        template_dir=template_dir,
    )
    print(f"wrote {out_dir / 'tasks.json'}")
    print(f"wrote {out_dir / 'summary.json'}")
    print(f"wrote {out_dir / 'index.md'}")
    print("next step: main agent must spawn one sub-agent per item in tasks.json")


def load_config(path: Path) -> dict[str, object]:
    if not path.exists():
        raise PackageCompareError(
            f"config file not found: {path}. Create config/production-package-compare.json in the workspace and set git_url."
        )
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise PackageCompareError(f"invalid JSON config {path}: {exc}") from exc
    git_url = str(data.get("git_url", "")).strip()
    if not git_url:
        raise PackageCompareError(f"git_url is required in config file: {path}")
    data["git_url"] = git_url
    return data


def resolve_config_path(skill_dir: Path, explicit_config: str | None) -> Path:
    if explicit_config:
        return Path(explicit_config).expanduser().resolve()
    candidates = [
        Path.cwd() / "config" / "production-package-compare.json",
        Path.cwd() / "production-package-compare.config.json",
        skill_dir / "config.json",
    ]
    for candidate in candidates:
        if candidate.exists():
            return candidate.resolve()
    return candidates[0].resolve()


def validate_target_tars(target_tars: object) -> None:
    if not isinstance(target_tars, list) or not all(isinstance(item, str) for item in target_tars):
        raise PackageCompareError("target_tars must be a JSON string array.")
    unsupported = sorted(set(target_tars) - set(DEFAULT_TARGET_TARS))
    if unsupported:
        raise PackageCompareError(
            "only these target_tars are supported: "
            + ", ".join(DEFAULT_TARGET_TARS)
            + f". Unsupported: {', '.join(unsupported)}"
        )


def resolve_template_dir(skill_dir: Path, explicit_dir: str | None) -> Path:
    template_dir = Path(explicit_dir).expanduser() if explicit_dir else skill_dir / "templates"
    required = template_dir / "index.md"
    if not required.exists():
        raise PackageCompareError(f"missing report template file: {required}")
    return template_dir


def resolve_output_dir(explicit_out: str | None, left_branch: str, right_branch: str, overwrite: bool = False) -> Path:
    timestamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    if explicit_out:
        requested = Path(explicit_out).expanduser().resolve()
        if overwrite or not requested.exists():
            return requested
        return requested.with_name(f"{requested.name}_{timestamp}")
    name = f"{safe_dir_name(left_branch)}_vs_{safe_dir_name(right_branch)}_{timestamp}"
    return (Path.cwd() / "workspace" / name).resolve()


def export_branch(git_url: str, branch: str, dest: Path, download_mode: str, target_tars: list[str]) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    work_dest = dest.parent / f".{dest.name}.tmp-{os.getpid()}-{uuid.uuid4().hex[:8]}"
    remove_tree(work_dest)
    work_dest.mkdir(parents=True, exist_ok=True)

    try:
        if download_mode == "clone":
            clone_branch(git_url, branch, work_dest, target_tars)
            replace_tree(work_dest, dest)
            return

        archive_error = archive_branch(git_url, branch, work_dest, target_tars)
        if archive_error is None:
            replace_tree(work_dest, dest)
            return
        if download_mode == "archive":
            raise archive_error

        remove_tree(work_dest)
        work_dest.mkdir(parents=True, exist_ok=True)
        clone_branch(git_url, branch, work_dest, target_tars, previous_error=archive_error)
        replace_tree(work_dest, dest)
    finally:
        cleanup_tree(work_dest)


def replace_tree(src: Path, dest: Path) -> None:
    remove_tree(dest)
    src.replace(dest)


def remove_tree(path: Path) -> None:
    if not path.exists():
        return
    try:
        shutil.rmtree(path)
    except PermissionError as exc:
        raise PackageCompareError(
            f"failed to remove directory because Windows is locking a file: {path}. "
            "Close Explorer/IDE/terminal windows that are using it, or rerun with a fresh output path."
        ) from exc


def cleanup_tree(path: Path) -> None:
    try:
        remove_tree(path)
    except PackageCompareError as exc:
        print(f"warning: {exc}", file=sys.stderr)


def archive_branch(git_url: str, branch: str, dest: Path, target_tars: list[str]) -> PackageCompareError | None:
    command = ["git", "archive", "--format=tar", "--remote", git_url, branch, "--", *target_tars]
    completed = subprocess.run(command, capture_output=True)
    log_path = dest.parent / f"{safe_dir_name(branch)}.git-archive.log"
    write_command_log(log_path, command, stdout=completed.stdout[:4096], stderr=completed.stderr)
    if completed.returncode != 0:
        return PackageCompareError(
            f"git archive failed for branch {branch!r} with exit code {completed.returncode}. See {log_path}."
        )

    with tarfile.open(fileobj=io.BytesIO(completed.stdout), mode="r:") as archive:
        safe_extract_all(archive, dest)
    return None


def clone_branch(
    git_url: str,
    branch: str,
    dest: Path,
    target_tars: list[str],
    previous_error: PackageCompareError | None = None,
) -> None:
    command = [
        "git",
        "-c",
        "core.longpaths=true",
        "clone",
        "--depth",
        "1",
        "--branch",
        branch,
        "--single-branch",
        "--no-checkout",
        git_url,
        str(dest),
    ]
    completed = subprocess.run(command, capture_output=True)
    log_path = dest.parent / f"{safe_dir_name(branch)}.git-clone.log"
    prefix = b""
    if previous_error is not None:
        prefix = (
            b"git archive failed first; falling back to git clone.\n"
            + str(previous_error).encode("utf-8", errors="replace")
            + b"\n\n"
        )
    write_command_log(log_path, command, stdout=completed.stdout, stderr=prefix + completed.stderr)
    if completed.returncode != 0:
        raise PackageCompareError(
            f"git clone failed for branch {branch!r} with exit code {completed.returncode}. See {log_path}."
        )
    checkout_available_tars(dest, branch, target_tars)


def checkout_available_tars(repo_dir: Path, branch: str, target_tars: list[str]) -> None:
    present = list_available_root_tars(repo_dir, target_tars)
    if not present:
        log_path = repo_dir.parent / f"{safe_dir_name(branch)}.git-checkout.log"
        log_path.write_text(
            "No target tar files exist at the branch root. Checked names: "
            + ", ".join(target_tars)
            + "\n",
            encoding="utf-8",
        )
        return
    command = ["git", "-C", str(repo_dir), "-c", "core.longpaths=true", "checkout", "HEAD", "--", *present]
    completed = subprocess.run(command, capture_output=True)
    log_path = repo_dir.parent / f"{safe_dir_name(branch)}.git-checkout.log"
    write_command_log(log_path, command, stdout=completed.stdout, stderr=completed.stderr)
    if completed.returncode != 0:
        raise PackageCompareError(
            f"git checkout of target tar files failed for branch {branch!r} with exit code {completed.returncode}. See {log_path}."
        )


def list_available_root_tars(repo_dir: Path, target_tars: list[str]) -> list[str]:
    command = ["git", "-C", str(repo_dir), "ls-tree", "--name-only", "HEAD", "--", *target_tars]
    completed = subprocess.run(command, capture_output=True, text=True)
    if completed.returncode != 0:
        return []
    names = {line.strip() for line in completed.stdout.splitlines() if line.strip()}
    return [name for name in target_tars if name in names]


def write_command_log(path: Path, command: list[str], stdout: bytes, stderr: bytes) -> None:
    redacted_command = [redact_secret(part) for part in command]
    path.write_bytes(
        b"command: "
        + " ".join(redacted_command).encode("utf-8", errors="replace")
        + b"\n\nstdout:\n"
        + stdout
        + b"\n\nstderr:\n"
        + redact_secret(stderr.decode("utf-8", errors="replace")).encode("utf-8", errors="replace")
    )


def safe_extract_all(archive: tarfile.TarFile, dest: Path) -> None:
    dest_resolved = dest.resolve()
    for member in archive.getmembers():
        target = (dest / member.name).resolve()
        if os.path.commonpath([str(dest_resolved), str(target)]) != str(dest_resolved):
            raise PackageCompareError(f"git archive contains unsafe path: {member.name}")
    archive.extractall(dest)


def build_task_plan(
    target_tars: list[str],
    left_dir: Path,
    right_dir: Path,
    reports_dir: Path,
    left_branch: str,
    right_branch: str,
    bc_path: str | None,
) -> tuple[list[dict[str, object]], list[dict[str, object]]]:
    tasks: list[dict[str, object]] = []
    package_statuses: list[dict[str, object]] = []
    for tar_name in target_tars:
        package = tar_name.removesuffix(".tar")
        left_tar = left_dir / tar_name
        right_tar = right_dir / tar_name
        out_dir = reports_dir / package
        out_dir.mkdir(parents=True, exist_ok=True)
        missing = []
        if not left_tar.exists():
            missing.append("left")
        if not right_tar.exists():
            missing.append("right")

        status = {
            "package": package,
            "tar_name": tar_name,
            "status": "missing" if missing else "ready",
            "missing": missing,
            "left_tar": str(left_tar),
            "right_tar": str(right_tar),
            "out": str(out_dir),
            "analysis": str(out_dir / "analysis.md"),
            "report": str(out_dir / "index.md"),
            "diff_index": str(out_dir / "diff-index.json"),
        }
        package_statuses.append(status)

        if not missing:
            task = {
                "package": package,
                "tar_name": tar_name,
                "left_branch": left_branch,
                "right_branch": right_branch,
                "left_tar": str(left_tar),
                "right_tar": str(right_tar),
                "out": str(out_dir),
                "analysis": str(out_dir / "analysis.md"),
                "report": str(out_dir / "index.md"),
                "diff_index": str(out_dir / "diff-index.json"),
            }
            if bc_path:
                task["bc_path"] = bc_path
            tasks.append(task)

    return tasks, package_statuses


def write_summary_index(
    path: Path,
    summary: dict[str, object],
    tasks: list[dict[str, object]],
    package_statuses: list[dict[str, object]],
    template_dir: Path,
) -> None:
    rows = []
    for package in package_statuses:
        status = str(package["status"])
        report = rel_link(path.parent, Path(str(package["report"]))) if status == "ready" else ""
        analysis = rel_link(path.parent, Path(str(package["analysis"]))) if status == "ready" else ""
        rows.append(
            "| "
            + f"`{escape_md(package['tar_name'])}` | "
            + f"`{escape_md(status)}` | "
            + f"{escape_md(', '.join(package['missing']))} | "
            + f"{('[report](' + report + ')') if report else ''} | "
            + f"{('[analysis](' + analysis + ')') if analysis else ''} |"
        )
    if not rows:
        rows.append("| `_none` |  |  |  |  |")

    content = render_template(
        template_dir / "index.md",
        {
            "left_branch": str(summary["left_branch"]),
            "right_branch": str(summary["right_branch"]),
            "generated_at": str(summary["generated_at"]),
            "git_url": str(summary["git_url"]),
            "left_dir": str(summary["branches"]["left"]),
            "right_dir": str(summary["branches"]["right"]),
            "package_rows": "\n".join(rows),
        },
    )
    path.write_text(content, encoding="utf-8")


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def safe_dir_name(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9._-]+", "_", value).strip("._-") or "branch"


def redact_secret(value: object) -> str:
    text = str(value)
    return re.sub(r"(https?://[^:/@\s]+:)[^@\s]+(@)", r"\1***\2", text)


def rel_link(root: Path, path: Path) -> str:
    try:
        return path.resolve().relative_to(root.resolve()).as_posix()
    except ValueError:
        return path.as_posix()


def escape_md(value: object) -> str:
    return html.escape(str(value)).replace("|", "\\|")


def render_template(path: Path, values: dict[str, str]) -> str:
    return path.read_text(encoding="utf-8").format(**values).rstrip() + "\n"


if __name__ == "__main__":
    raise SystemExit(main())
