#!/usr/bin/env python
"""Restricted MCP writer for Sm@rtESB generated documents.

This stdio MCP server exposes only the file operations needed by the
smartesb-code-reader skill. It avoids shell command-length limits and
interactive write tools by accepting Markdown and JSON content as MCP tool
arguments.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, BinaryIO


PROTOCOL_VERSION = "2024-11-05"
SERVER_NAME = "smartesb-writer"
SERVER_VERSION = "1.0.0"
DEFAULT_MAX_MARKDOWN_BYTES = 8 * 1024

MODULE_SUMMARY_FIELDS = {
    "serviceId",
    "document_link",
    "summary",
    "inputs",
    "variables",
    "main_steps",
    "outputs",
    "external_calls",
    "error_handling",
    "used_by_transactions",
    "risks_or_uncertainties",
}

TRANSACTION_SUMMARY_FIELDS = {
    "transaction_key",
    "document_link",
    "summary",
    "primary_case",
    "alias_count",
    "module_service_ids",
    "important_steps",
    "missing_modules",
    "risks_or_uncertainties",
}


class ToolError(Exception):
    def __init__(self, code: str, message: str, details: dict[str, Any] | None = None) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.details = details or {}

    def to_dict(self) -> dict[str, Any]:
        payload: dict[str, Any] = {"ok": False, "code": self.code, "message": self.message}
        if self.details:
            payload["details"] = self.details
        return payload


@dataclass
class WriterConfig:
    roots: list[Path]
    max_markdown_bytes: int


class StdioJsonRpc:
    """Read newline-delimited JSON-RPC, with Content-Length fallback."""

    def __init__(self, stdin: BinaryIO, stdout: BinaryIO) -> None:
        self.stdin = stdin
        self.stdout = stdout
        self.mode: str | None = None

    def read(self) -> dict[str, Any] | None:
        line = self.stdin.readline()
        if not line:
            return None

        if line.startswith(b"Content-Length:"):
            self.mode = "headers"
            length = self._parse_content_length(line)
            while True:
                header = self.stdin.readline()
                if header in (b"\r\n", b"\n", b""):
                    break
                if header.startswith(b"Content-Length:"):
                    length = self._parse_content_length(header)
            body = self.stdin.read(length)
            return json.loads(body.decode("utf-8"))

        self.mode = self.mode or "newline"
        return json.loads(line.decode("utf-8"))

    def write(self, payload: dict[str, Any]) -> None:
        encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        if self.mode == "headers":
            self.stdout.write(f"Content-Length: {len(encoded)}\r\n\r\n".encode("ascii"))
            self.stdout.write(encoded)
        else:
            self.stdout.write(encoded + b"\n")
        self.stdout.flush()

    @staticmethod
    def _parse_content_length(line: bytes) -> int:
        try:
            return int(line.decode("ascii").split(":", 1)[1].strip())
        except (IndexError, ValueError) as exc:
            raise ValueError(f"Invalid Content-Length header: {line!r}") from exc


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Sm@rtESB restricted writer MCP server.")
    parser.add_argument(
        "--root",
        action="append",
        default=[],
        help="Allowed output root. May be repeated. Relative document paths resolve under the first root.",
    )
    parser.add_argument(
        "--max-markdown-bytes",
        type=int,
        default=int(os.environ.get("SMARTESB_WRITER_MAX_MARKDOWN_BYTES", DEFAULT_MAX_MARKDOWN_BYTES)),
        help="Maximum UTF-8 bytes accepted by one Markdown tool call.",
    )
    return parser.parse_args()


def build_config(args: argparse.Namespace) -> WriterConfig:
    roots = list(args.root)
    env_roots = os.environ.get("SMARTESB_WRITER_ROOTS", "")
    if env_roots:
        roots.extend(part for part in env_roots.split(os.pathsep) if part)
    if not roots:
        raise SystemExit("At least one --root or SMARTESB_WRITER_ROOTS entry is required.")

    resolved_roots = [Path(root).expanduser().resolve(strict=False) for root in roots]
    return WriterConfig(roots=resolved_roots, max_markdown_bytes=args.max_markdown_bytes)


def is_under_root(path: Path, root: Path) -> bool:
    try:
        common = os.path.commonpath([str(path), str(root)])
    except ValueError:
        return False
    return common == str(root)


def resolve_allowed_path(config: WriterConfig, raw_path: str) -> Path:
    if not raw_path or not isinstance(raw_path, str):
        raise ToolError("invalid_path", "path must be a non-empty string.")

    candidate = Path(raw_path).expanduser()
    if not candidate.is_absolute():
        candidate = config.roots[0] / candidate

    resolved = candidate.resolve(strict=False)
    for root in config.roots:
        if is_under_root(resolved, root):
            return resolved

    raise ToolError(
        "path_outside_allowed_roots",
        "Refusing to write outside allowed roots.",
        {"path": str(resolved), "allowed_roots": [str(root) for root in config.roots]},
    )


def sidecar_path(path: Path) -> Path:
    return path.with_name(f".{path.name}.smartesb-writer.json")


def atomic_write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temp_name = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=str(path.parent))
    temp_path = Path(temp_name)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="") as handle:
            handle.write(text)
        temp_path.replace(path)
    except Exception:
        try:
            temp_path.unlink(missing_ok=True)
        except OSError:
            pass
        raise


def read_text(path: Path) -> str:
    if not path.exists():
        return ""
    return path.read_text(encoding="utf-8")


def read_state(path: Path) -> dict[str, Any]:
    state_path = sidecar_path(path)
    if not state_path.exists():
        return {"path": str(path), "seqs": [], "chunks": []}
    try:
        value = json.loads(state_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return {"path": str(path), "seqs": [], "chunks": [], "state_warning": "invalid_sidecar_reset"}
    if not isinstance(value, dict):
        return {"path": str(path), "seqs": [], "chunks": [], "state_warning": "invalid_sidecar_reset"}
    value.setdefault("path", str(path))
    value.setdefault("seqs", [])
    value.setdefault("chunks", [])
    return value


def write_state(path: Path, state: dict[str, Any]) -> None:
    atomic_write(sidecar_path(path), json.dumps(state, ensure_ascii=False, indent=2) + "\n")


def validate_seq(seq: Any) -> str:
    if not isinstance(seq, str) or not seq.strip():
        raise ToolError("invalid_seq", "seq must be a non-empty string.")
    if len(seq) > 160:
        raise ToolError("invalid_seq", "seq must be 160 characters or fewer.")
    return seq.strip()


def validate_markdown_text(config: WriterConfig, text: Any) -> str:
    if not isinstance(text, str):
        raise ToolError("invalid_text", "text must be a string.")
    size = len(text.encode("utf-8"))
    if size > config.max_markdown_bytes:
        raise ToolError(
            "payload_too_large",
            "Markdown payload is too large; split the current section and retry.",
            {"bytes": size, "max_bytes": config.max_markdown_bytes},
        )
    return sanitize_mermaid_chunks(text)


def sanitize_mermaid_source(source: str) -> str:
    sanitized_lines: list[str] = []
    for line in source.splitlines(keepends=True):
        stripped = line.strip()
        if re.fullmatch(r"(?i)(activate|deactivate)\s+[A-Za-z_][A-Za-z0-9_]*", stripped):
            continue
        line = re.sub(r"(->>|-->>)([+-])\s*([A-Za-z_][A-Za-z0-9_]*)", r"\1\3", line)
        sanitized_lines.append(line)
    return "".join(sanitized_lines)


def sanitize_mermaid_chunks(markdown: str) -> str:
    fence_pattern = re.compile(r"(?ims)(^[ \t]*```mermaid[^\n]*\n)(.*?)(^[ \t]*```)")
    markdown = fence_pattern.sub(
        lambda match: match.group(1) + sanitize_mermaid_source(match.group(2)) + match.group(3),
        markdown,
    )
    if markdown.lstrip().startswith("sequenceDiagram"):
        return sanitize_mermaid_source(markdown)
    return markdown


def append_final_newline(text: str) -> str:
    if text and not text.endswith("\n"):
        return text + "\n"
    return text


def record_chunk(state: dict[str, Any], seq: str, text: str, mode: str) -> None:
    state.setdefault("seqs", []).append(seq)
    state.setdefault("chunks", []).append(
        {
            "seq": seq,
            "mode": mode,
            "chars": len(text),
            "bytes": len(text.encode("utf-8")),
            "lines": len(text.splitlines()),
        }
    )


def tool_begin_markdown(config: WriterConfig, args: dict[str, Any]) -> dict[str, Any]:
    target = resolve_allowed_path(config, args.get("path", ""))
    text = append_final_newline(validate_markdown_text(config, args.get("text", "")))
    seq = validate_seq(args.get("seq", "init"))
    overwrite = bool(args.get("overwrite", True))

    state = read_state(target)
    if not overwrite and seq in state.get("seqs", []):
        return {"ok": True, "status": "skipped_duplicate", "path": str(target), "seq": seq}

    if target.exists() and not overwrite:
        raise ToolError("file_exists", "Target Markdown file exists and overwrite=false.", {"path": str(target)})

    atomic_write(target, text)
    new_state = {"path": str(target), "seqs": [], "chunks": []}
    record_chunk(new_state, seq, text, "begin")
    write_state(target, new_state)
    return {
        "ok": True,
        "status": "written",
        "path": str(target),
        "seq": seq,
        "bytes": len(text.encode("utf-8")),
        "lines": len(text.splitlines()),
    }


def tool_append_markdown(config: WriterConfig, args: dict[str, Any]) -> dict[str, Any]:
    target = resolve_allowed_path(config, args.get("path", ""))
    text = append_final_newline(validate_markdown_text(config, args.get("text", "")))
    seq = validate_seq(args.get("seq"))

    state = read_state(target)
    if seq in state.get("seqs", []):
        return {"ok": True, "status": "skipped_duplicate", "path": str(target), "seq": seq}

    existing = read_text(target)
    combined = existing + text
    atomic_write(target, combined)
    state["path"] = str(target)
    record_chunk(state, seq, text, "append")
    write_state(target, state)
    return {
        "ok": True,
        "status": "appended",
        "path": str(target),
        "seq": seq,
        "bytes": len(text.encode("utf-8")),
        "lines": len(text.splitlines()),
        "total_bytes": len(combined.encode("utf-8")),
    }


def validate_summary(kind: str, data: Any) -> dict[str, Any]:
    if not isinstance(data, dict):
        raise ToolError("invalid_json_root", "summary data must be a JSON object.")
    missing: set[str] = set()
    if kind == "module":
        missing = MODULE_SUMMARY_FIELDS - set(data)
    elif kind == "transaction":
        missing = TRANSACTION_SUMMARY_FIELDS - set(data)
    elif kind not in {"index", "generic"}:
        raise ToolError("invalid_kind", "kind must be module, transaction, index, or generic.")
    if missing:
        raise ToolError("missing_summary_fields", "summary data is missing required fields.", {"missing": sorted(missing)})
    return data


def tool_write_summary_json(config: WriterConfig, args: dict[str, Any]) -> dict[str, Any]:
    target = resolve_allowed_path(config, args.get("path", ""))
    kind = args.get("kind", "generic")
    if not isinstance(kind, str):
        raise ToolError("invalid_kind", "kind must be a string.")
    data = validate_summary(kind, args.get("data"))
    rendered = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
    atomic_write(target, rendered)
    return {
        "ok": True,
        "status": "written",
        "path": str(target),
        "kind": kind,
        "bytes": len(rendered.encode("utf-8")),
        "fields": sorted(data.keys()),
    }


def tool_finish_document(config: WriterConfig, args: dict[str, Any]) -> dict[str, Any]:
    document_path = resolve_allowed_path(config, args.get("document_path", ""))
    summary_raw = args.get("summary_path")
    summary_path = resolve_allowed_path(config, summary_raw) if summary_raw else None

    if not document_path.exists():
        raise ToolError("missing_document", "document_path does not exist.", {"document_path": str(document_path)})

    document = document_path.read_text(encoding="utf-8")
    result: dict[str, Any] = {
        "ok": True,
        "document_path": str(document_path),
        "document_bytes": len(document.encode("utf-8")),
        "document_chars": len(document),
        "document_lines": len(document.splitlines()),
        "has_mermaid": "```mermaid" in document.lower(),
        "sidecar_path": str(sidecar_path(document_path)),
        "sidecar_exists": sidecar_path(document_path).exists(),
    }

    if summary_path is not None:
        if not summary_path.exists():
            raise ToolError("missing_summary", "summary_path does not exist.", {"summary_path": str(summary_path)})
        try:
            summary_value = json.loads(summary_path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            raise ToolError("invalid_summary_json", "summary_path is not valid JSON.", {"error": str(exc)}) from exc
        result.update(
            {
                "summary_path": str(summary_path),
                "summary_is_object": isinstance(summary_value, dict),
                "summary_fields": sorted(summary_value.keys()) if isinstance(summary_value, dict) else [],
            }
        )

    return result


def tool_schemas(config: WriterConfig) -> list[dict[str, Any]]:
    return [
        {
            "name": "smartesb_begin_markdown",
            "description": "Initialize a Markdown document under an allowed root. Text must be a short title/overview/input-evidence chunk, not a full document.",
            "inputSchema": {
                "type": "object",
                "additionalProperties": False,
                "properties": {
                    "path": {"type": "string"},
                    "text": {"type": "string", "maxLength": config.max_markdown_bytes},
                    "overwrite": {"type": "boolean", "default": True},
                    "seq": {"type": "string", "default": "init"},
                },
                "required": ["path", "text"],
            },
        },
        {
            "name": "smartesb_append_markdown",
            "description": "Append one small Markdown section/table/Mermaid block using a stable seq id. Split long sections before calling.",
            "inputSchema": {
                "type": "object",
                "additionalProperties": False,
                "properties": {
                    "path": {"type": "string"},
                    "text": {"type": "string", "maxLength": config.max_markdown_bytes},
                    "seq": {"type": "string"},
                },
                "required": ["path", "text", "seq"],
            },
        },
        {
            "name": "smartesb_write_summary_json",
            "description": "Write and validate a small summary JSON object atomically.",
            "inputSchema": {
                "type": "object",
                "additionalProperties": False,
                "properties": {
                    "path": {"type": "string"},
                    "data": {"type": "object"},
                    "kind": {"type": "string", "enum": ["module", "transaction", "index", "generic"], "default": "generic"},
                },
                "required": ["path", "data"],
            },
        },
        {
            "name": "smartesb_finish_document",
            "description": "Check that generated Markdown and optional summary JSON exist and are readable.",
            "inputSchema": {
                "type": "object",
                "additionalProperties": False,
                "properties": {
                    "document_path": {"type": "string"},
                    "summary_path": {"type": "string"},
                },
                "required": ["document_path"],
            },
        },
    ]


def call_tool(config: WriterConfig, name: str, arguments: dict[str, Any]) -> dict[str, Any]:
    if name == "smartesb_begin_markdown":
        return tool_begin_markdown(config, arguments)
    if name == "smartesb_append_markdown":
        return tool_append_markdown(config, arguments)
    if name == "smartesb_write_summary_json":
        return tool_write_summary_json(config, arguments)
    if name == "smartesb_finish_document":
        return tool_finish_document(config, arguments)
    raise ToolError("unknown_tool", f"Unknown tool: {name}")


def rpc_result(request_id: Any, result: Any) -> dict[str, Any]:
    return {"jsonrpc": "2.0", "id": request_id, "result": result}


def rpc_error(request_id: Any, code: int, message: str, data: Any | None = None) -> dict[str, Any]:
    error: dict[str, Any] = {"code": code, "message": message}
    if data is not None:
        error["data"] = data
    return {"jsonrpc": "2.0", "id": request_id, "error": error}


def tool_response(payload: dict[str, Any], is_error: bool = False) -> dict[str, Any]:
    return {
        "content": [{"type": "text", "text": json.dumps(payload, ensure_ascii=False, indent=2)}],
        "isError": is_error,
    }


def handle_request(config: WriterConfig, message: dict[str, Any]) -> dict[str, Any] | None:
    if "id" not in message:
        return None

    request_id = message.get("id")
    method = message.get("method")
    params = message.get("params") or {}

    try:
        if method == "initialize":
            return rpc_result(
                request_id,
                {
                    "protocolVersion": PROTOCOL_VERSION,
                    "capabilities": {"tools": {"listChanged": False}},
                    "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION},
                },
            )
        if method == "ping":
            return rpc_result(request_id, {})
        if method == "tools/list":
            return rpc_result(request_id, {"tools": tool_schemas(config)})
        if method == "tools/call":
            if not isinstance(params, dict):
                raise ToolError("invalid_params", "tools/call params must be an object.")
            name = params.get("name")
            arguments = params.get("arguments") or {}
            if not isinstance(name, str) or not isinstance(arguments, dict):
                raise ToolError("invalid_params", "tools/call requires string name and object arguments.")
            try:
                result = call_tool(config, name, arguments)
                return rpc_result(request_id, tool_response(result))
            except ToolError as exc:
                return rpc_result(request_id, tool_response(exc.to_dict(), is_error=True))
        if method == "resources/list":
            return rpc_result(request_id, {"resources": []})
        if method == "prompts/list":
            return rpc_result(request_id, {"prompts": []})
        return rpc_error(request_id, -32601, f"Method not found: {method}")
    except ToolError as exc:
        return rpc_result(request_id, tool_response(exc.to_dict(), is_error=True))
    except Exception as exc:  # Defensive: never let server die on one bad request.
        return rpc_error(request_id, -32603, "Internal error", {"error": str(exc)})


def serve(config: WriterConfig) -> int:
    transport = StdioJsonRpc(sys.stdin.buffer, sys.stdout.buffer)
    while True:
        try:
            message = transport.read()
        except json.JSONDecodeError as exc:
            transport.write(rpc_error(None, -32700, "Parse error", {"error": str(exc)}))
            continue
        except Exception as exc:
            print(f"smartesb-writer fatal read error: {exc}", file=sys.stderr)
            return 1

        if message is None:
            return 0
        if not isinstance(message, dict):
            transport.write(rpc_error(None, -32600, "Invalid request"))
            continue

        response = handle_request(config, message)
        if response is not None:
            transport.write(response)


def main() -> int:
    args = parse_args()
    config = build_config(args)
    return serve(config)


if __name__ == "__main__":
    raise SystemExit(main())
