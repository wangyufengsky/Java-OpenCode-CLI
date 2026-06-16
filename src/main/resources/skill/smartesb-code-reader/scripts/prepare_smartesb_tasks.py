#!/usr/bin/env python
"""Prepare Sm@rtESB 8583 transaction and module analysis tasks."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import xml.etree.ElementTree as ET
from collections import defaultdict
from functools import lru_cache
from pathlib import Path
from typing import Any


REFERENCE_ATTRS = (
    "target",
    "ref",
    "route",
    "service",
    "serviceId",
)
FALLBACK_REFERENCE_ATTRS = (
    "id",
    "name",
    "value",
)
JAVA_HINT_ATTRS = (
    "class",
    "clazz",
    "className",
    "impl",
    "implementation",
    "ref",
    "service",
    "bean",
    "target",
)
TRANSACTION_REF_SUFFIXES = ("CUPS2ECI",)
FLOW_TAGS = {"from", "process", "to", "choice", "when", "otherwise", "pipeline"}
CONDITION_ATTRS = ("expression", "expression1", "expression2", "condition", "test", "value", "value1", "value2")
BASE_ROOT_TAGS = {"base"}


def strip_ns(tag: Any) -> str:
    if not isinstance(tag, str):
        return ""
    return tag.rsplit("}", 1)[-1] if "}" in tag else tag


def normalize_key(value: str) -> str:
    value = value.replace("\\", "/").strip()
    value = re.sub(r"\.xml$", "", value, flags=re.IGNORECASE)
    value = re.sub(r"[^A-Za-z0-9_.-]+", "_", value)
    value = value.strip("._-")
    return value or "unknown"


def comparable_name(value: str) -> str:
    return re.sub(r"[^a-z0-9]+", "", value.lower())


def as_posix(path: Path | None) -> str | None:
    return path.resolve().as_posix() if path else None


def relative_link(path: Path, from_dir: Path) -> str:
    return os.path.relpath(path.resolve(), start=from_dir.resolve()).replace(os.sep, "/")


@lru_cache(maxsize=None)
def read_xml(path: Path) -> ET.Element:
    parser = ET.XMLParser(target=ET.TreeBuilder(insert_comments=True))
    return ET.parse(path, parser=parser).getroot()


def short_text(value: str | None, limit: int = 160) -> str:
    text = " ".join((value or "").split())
    if len(text) <= limit:
        return text
    return text[: limit - 3] + "..."


def compact_attrs(attrs: dict[str, str], keep_empty: bool = False) -> dict[str, str]:
    result: dict[str, str] = {}
    for key, value in attrs.items():
        if value or keep_empty:
            result[key] = short_text(value, 240)
    return result


def condition_from_node(node: ET.Element) -> str:
    pieces = []
    for attr in CONDITION_ATTRS:
        value = (node.attrib.get(attr) or "").strip()
        if value:
            pieces.append(f"{attr}={value}")
    text = short_text(node.text, 200)
    if text:
        pieces.append(f"text={text}")
    return "; ".join(pieces)


def xml_path_for(node_stack: list[str], tag: str) -> str:
    return "/" + "/".join(node_stack + [tag])


def iter_switches_without_nested(root: ET.Element) -> list[ET.Element]:
    switches: list[ET.Element] = []

    def walk(node: ET.Element) -> None:
        if strip_ns(node.tag) == "switch":
            switches.append(node)
            return
        for child in list(node):
            walk(child)

    walk(root)
    return switches


def iter_mode_cases(root: ET.Element, mode: str) -> list[ET.Element]:
    cases: list[ET.Element] = []
    for node in iter_switches_without_nested(root):
        if node.attrib.get("mode") != mode:
            continue
        for child in list(node):
            if strip_ns(child.tag) == "case":
                cases.append(child)
    return cases


def summarize_service_identify(root: ET.Element, mode: str) -> dict[str, Any]:
    switches: list[dict[str, Any]] = []
    channels: list[dict[str, Any]] = []
    for node in root.iter():
        tag = strip_ns(node.tag)
        if tag == "channel":
            channels.append({"attributes": compact_attrs(node.attrib)})
    for node in iter_switches_without_nested(root):
        direct_cases = [child for child in list(node) if strip_ns(child.tag) == "case"]
        switch_info = {
            "mode": node.attrib.get("mode", ""),
            "attributes": compact_attrs(node.attrib),
            "case_count": len(direct_cases),
            "sample_cases": [case_snapshot(case, idx + 1) for idx, case in enumerate(direct_cases[:10])],
        }
        switches.append(switch_info)
    selected_switches = [item for item in switches if item.get("mode") == mode]
    return {
        "root_tag": strip_ns(root.tag),
        "channel_count": len(channels),
        "channels_sample": channels[:10],
        "switch_count": len(switches),
        "switches": switches,
        "selected_mode": mode,
        "selected_switch_count": len(selected_switches),
        "selected_case_count": sum(item.get("case_count", 0) for item in selected_switches),
    }


def case_snapshot(case: ET.Element, ordinal: int, source: Path | None = None) -> dict[str, Any]:
    snapshot = {
        "ordinal": ordinal,
        "attributes": dict(sorted(case.attrib.items())),
        "text": (case.text or "").strip(),
    }
    if source is not None:
        snapshot["service_identify"] = as_posix(source)
    return snapshot


def transaction_ref_from_case(case: ET.Element) -> str:
    for attr in REFERENCE_ATTRS:
        value = (case.attrib.get(attr) or "").strip()
        if value:
            return value
    text = (case.text or "").strip()
    if text:
        return text
    for attr in FALLBACK_REFERENCE_ATTRS:
        value = (case.attrib.get(attr) or "").strip()
        if value:
            return value
    return ""


def transaction_ref_for_match(ref: str) -> str:
    if not ref:
        return ref
    raw = ref.replace("\\", "/").strip()
    suffix = ""
    body = raw
    if raw.lower().endswith(".xml"):
        body = raw[:-4]
        suffix = raw[-4:]
    for removable in TRANSACTION_REF_SUFFIXES:
        if body.endswith(removable):
            return body[: -len(removable)] + suffix
    return raw


def collect_files(root: Path, suffix: str) -> list[Path]:
    if not root.exists():
        return []
    suffix = suffix.lower()
    return sorted(p for p in root.rglob("*") if p.is_file() and p.suffix.lower() == suffix)


def collect_files_case_insensitive(root: Path, suffix: str) -> list[Path]:
    return collect_files(root, suffix)


def find_xml_candidates(ref: str, xml_files: list[Path], xml_root: Path) -> list[Path]:
    if not ref:
        return []
    raw = ref.replace("\\", "/").strip()
    candidates: list[Path] = []
    direct = Path(raw)
    direct_paths = []
    if direct.is_absolute():
        direct_paths.append(direct)
    else:
        direct_paths.append(xml_root / direct)
        if direct.suffix.lower() != ".xml":
            direct_paths.append(xml_root / f"{raw}.xml")
    for item in direct_paths:
        if item.exists() and item.is_file() and item not in candidates:
            candidates.append(item)

    ref_base = Path(raw).name
    ref_stem = Path(ref_base).stem if ref_base.lower().endswith(".xml") else ref_base
    ref_cmp = comparable_name(ref_stem)
    for path in xml_files:
        if path in candidates:
            continue
        if path.name.lower() == ref_base.lower() or path.stem.lower() == ref_stem.lower():
            candidates.append(path)
            continue
        if comparable_name(path.stem) == ref_cmp:
            candidates.append(path)
    return candidates


def has_proxy_engine(path: Path) -> bool:
    try:
        root = read_xml(path)
    except (ET.ParseError, OSError):
        return False
    return any(strip_ns(node.tag) == "proxyEngine" for node in root.iter())


def is_base_xml_candidate(path: Path) -> bool:
    try:
        root = read_xml(path)
    except (ET.ParseError, OSError):
        return False
    tags = {strip_ns(node.tag).lower() for node in root.iter() if strip_ns(node.tag)}
    if "proxyengine" in tags:
        return False
    return strip_ns(root.tag).lower() in BASE_ROOT_TAGS or bool(BASE_ROOT_TAGS & tags)


def filter_transaction_xml_candidates(candidates: list[Path]) -> tuple[list[Path], list[Path]]:
    transaction_candidates = [path for path in candidates if has_proxy_engine(path)]
    rejected = [path for path in candidates if path not in transaction_candidates]
    return transaction_candidates, rejected


def stable_fallback_key(case: ET.Element) -> str:
    payload = json.dumps(
        {"attributes": dict(sorted(case.attrib.items())), "text": (case.text or "").strip()},
        sort_keys=True,
    )
    return "case_" + hashlib.sha1(payload.encode("utf-8")).hexdigest()[:12]


def transaction_key(ref: str, candidates: list[Path], case: ET.Element, xml_root: Path) -> str:
    if candidates:
        first = candidates[0]
        try:
            rel = first.resolve().relative_to(xml_root.resolve())
            return normalize_key(rel.as_posix())
        except ValueError:
            return normalize_key(first.stem)
    if ref:
        return normalize_key(ref)
    return stable_fallback_key(case)


def transaction_identity(ref: str, candidates: list[Path], case: ET.Element) -> str:
    if candidates:
        return f"xml:{candidates[0].resolve().as_posix()}"
    if ref:
        return f"ref:{ref}"
    return stable_fallback_key(case)


def unique_output_key(base_value: str, identity: str, used: dict[str, str]) -> str:
    base = normalize_key(base_value)
    if used.get(base) in (None, identity):
        used[base] = identity
        return base

    digest = hashlib.sha1(identity.encode("utf-8")).hexdigest()[:8]
    candidate = f"{base}_{digest}"
    counter = 2
    while used.get(candidate) not in (None, identity):
        candidate = f"{base}_{digest}_{counter}"
        counter += 1
    used[candidate] = identity
    return candidate


def first_flow_descendant(node: ET.Element, node_steps: dict[int, int]) -> int | None:
    for child in list(node):
        child_id = id(child)
        if child_id in node_steps:
            return node_steps[child_id]
        nested = first_flow_descendant(child, node_steps)
        if nested is not None:
            return nested
    return None


def next_flow_sibling_descendant(parent_children: list[ET.Element], index: int, node_steps: dict[int, int]) -> int | None:
    for sibling in parent_children[index + 1 :]:
        sibling_id = id(sibling)
        if sibling_id in node_steps:
            return node_steps[sibling_id]
        nested = first_flow_descendant(sibling, node_steps)
        if nested is not None:
            return nested
    return None


def build_flow_edges(root: ET.Element, node_steps: dict[int, int]) -> tuple[list[dict[str, Any]], str]:
    edges: list[dict[str, Any]] = []
    edge_keys: set[tuple[int, int, str]] = set()
    quality = "edge_inferred"

    def add_edge(source: int | None, target: int | None, label: str = "") -> None:
        if source is None or target is None or source == target:
            return
        key = (source, target, label)
        if key in edge_keys:
            return
        edge_keys.add(key)
        edges.append({"from": source, "to": target, "label": label})

    def walk(node: ET.Element, after_node_step: int | None = None) -> None:
        children = [child for child in list(node) if strip_ns(child.tag)]
        flow_children: list[tuple[int, ET.Element]] = []
        for index, child in enumerate(children):
            child_step = node_steps.get(id(child))
            first_descendant = child_step if child_step is not None else first_flow_descendant(child, node_steps)
            if first_descendant is not None:
                flow_children.append((index, child))

        for (_, current), (_, following) in zip(flow_children, flow_children[1:]):
            current_step = node_steps.get(id(current)) or first_flow_descendant(current, node_steps)
            following_step = node_steps.get(id(following)) or first_flow_descendant(following, node_steps)
            if strip_ns(current.tag) not in {"choice", "when", "otherwise"}:
                add_edge(current_step, following_step)

        if strip_ns(node.tag) == "choice":
            choice_step = node_steps.get(id(node))
            for child in children:
                child_tag = strip_ns(child.tag)
                if child_tag not in {"when", "otherwise"}:
                    continue
                branch_step = node_steps.get(id(child)) or first_flow_descendant(child, node_steps)
                label = "否则" if child_tag == "otherwise" else condition_from_node(child) or "条件分支"
                add_edge(choice_step, branch_step, short_text(label, 80))
                branch_exit = last_flow_descendant(child, node_steps) or branch_step
                add_edge(branch_exit, after_node_step)
        elif strip_ns(node.tag) in {"when", "otherwise", "pipeline"}:
            node_step = node_steps.get(id(node))
            child_step = first_flow_descendant(node, node_steps)
            add_edge(node_step, child_step)

        for index, child in enumerate(children):
            child_after = next_flow_sibling_descendant(children, index, node_steps)
            if child_after is None:
                child_after = after_node_step
            walk(child, child_after)

    def last_flow_descendant(node: ET.Element, steps: dict[int, int]) -> int | None:
        found = steps.get(id(node))
        for child in list(node):
            nested = last_flow_descendant(child, steps)
            if nested is not None:
                found = nested
        return found

    walk(root)
    if not edges:
        quality = "linear_draft"
    return edges, quality


def mermaid_label(text: Any, limit: int = 64) -> str:
    label = short_text(str(text or ""), limit)
    replacements = {
        '"': "'",
        "\n": " ",
        "\r": " ",
        "|": "/",
        "{": "(",
        "}": ")",
        "[": "(",
        "]": ")",
        ";": ",",
    }
    for old, new in replacements.items():
        label = label.replace(old, new)
    return label.strip()


def compact_node_text(text: Any, limit: int = 24) -> str:
    value = str(text or "").strip()
    if not value:
        return ""
    value = value.rsplit("/", 1)[-1]
    value = value.rsplit(".", 1)[-1]
    return short_text(value, limit)


def node_kind_label(tag: str, has_service: bool) -> str:
    if has_service:
        return "调用 base"
    labels = {
        "from": "入口",
        "process": "处理节点",
        "pipeline": "管道",
        "choice": "判断分支",
        "when": "条件分支",
        "otherwise": "默认分支",
        "to": "路由",
    }
    return labels.get(tag, tag or "节点")


def node_secondary_text(node: dict[str, Any]) -> str:
    tag = node.get("tag", "")
    service_id = node.get("serviceId", "")
    node_id = node.get("id", "")
    target = node.get("target", "")
    condition = node.get("condition", "")

    if service_id:
        return compact_node_text(service_id, 34)
    if tag == "choice":
        return "按条件选择路径"
    if tag == "when":
        return compact_node_text(condition or node_id or "when", 28)
    if tag == "otherwise":
        return "未命中条件时执行"
    if tag == "from":
        return compact_node_text(node_id or target or "交易入口", 28)
    if tag in {"process", "pipeline", "to"}:
        return compact_node_text(node_id or target or tag, 28)
    return compact_node_text(node_id or target, 28)


def node_title(node: dict[str, Any]) -> str:
    step = node.get("step", "")
    tag = node.get("tag", "")
    has_service = bool(node.get("serviceId"))
    primary = f"{step}. {node_kind_label(tag, has_service)}"
    secondary = node_secondary_text(node)
    return f"{primary}<br/>{secondary}" if secondary else primary


def mermaid_node_definition(node_id: str, node: dict[str, Any]) -> str:
    tag = node.get("tag", "")
    service_id = node.get("serviceId", "")
    label = mermaid_label(node_title(node))

    if tag in {"choice", "when", "otherwise"}:
        return f'  {node_id}{{"{label}"}}'
    if service_id:
        return f'  {node_id}[["{label}"]]'
    if tag == "from":
        return f'  {node_id}(["{label}"])'
    return f'  {node_id}["{label}"]'


def build_status_mermaid(message: str) -> str:
    label = mermaid_label(message, 100)
    return "\n".join(
        [
            "sequenceDiagram",
            "  autonumber",
            "  participant ENTRY as 交易入口",
            "  participant FLOW as 交易编排",
            "  ENTRY->>FLOW: 接收交易请求",
            f"  Note over FLOW: {label}",
            "  FLOW-->>ENTRY: 返回处理结果",
        ]
    )


def sequence_message(text: Any, limit: int = 48) -> str:
    return mermaid_label(text, limit).replace(":", "：")


def participant_label(text: Any, fallback: str) -> str:
    value = compact_node_text(text, 32) or fallback
    return mermaid_label(value, 36).replace(" ", "_")


def build_mermaid(flow_nodes: list[dict[str, Any]], flow_edges: list[dict[str, Any]] | None = None) -> str:
    if not flow_nodes:
        return build_status_mermaid("未解析到流程节点")

    service_participants: dict[str, str] = {}
    for node in flow_nodes:
        service_id = node.get("serviceId", "")
        if service_id and service_id not in service_participants:
            service_participants[service_id] = f"M{len(service_participants) + 1}"

    lines: list[str] = [
        "sequenceDiagram",
        "  autonumber",
        "  participant ENTRY as 交易入口",
        "  participant FLOW as 交易编排",
    ]
    for service_id, alias in service_participants.items():
        lines.append(f"  participant {alias} as {participant_label(service_id, alias)}")
    lines.extend(
        [
            "  ENTRY->>FLOW: 接收交易请求",
            "  Note over ENTRY,FLOW: 完整 XML 路径、条件和类名见下方节点说明表",
        ]
    )

    choice_stack: list[dict[str, Any]] = []

    def close_completed_choices(current_depth: int) -> None:
        while choice_stack and current_depth <= choice_stack[-1]["depth"]:
            frame = choice_stack.pop()
            if frame.get("opened"):
                lines.append("  end")

    for node in flow_nodes:
        tag = node.get("tag", "")
        depth = int(node.get("depth") or 0)
        close_completed_choices(depth)

        if tag == "choice":
            label = sequence_message(node_secondary_text(node) or "按条件选择路径", 44)
            lines.append(f"  FLOW->>FLOW: 判断分支：{label}")
            choice_stack.append({"depth": depth, "opened": False})
            continue

        if tag in {"when", "otherwise"}:
            label = "默认分支" if tag == "otherwise" else sequence_message(node_secondary_text(node) or "条件分支", 44)
            if choice_stack:
                if choice_stack[-1].get("opened"):
                    lines.append(f"  else {label}")
                else:
                    lines.append(f"  alt {label}")
                    choice_stack[-1]["opened"] = True
            else:
                lines.append(f"  FLOW->>FLOW: {node_kind_label(tag, False)}：{label}")
            continue

        service_id = node.get("serviceId", "")
        secondary = sequence_message(node_secondary_text(node), 44)
        kind = sequence_message(node_kind_label(tag, bool(service_id)), 20)
        if service_id:
            alias = service_participants.get(service_id, "FLOW")
            label = secondary or sequence_message(service_id, 44)
            lines.append(f"  FLOW->>{alias}: {kind}：{label}")
            lines.append(f"  {alias}-->>FLOW: 返回处理结果")
        elif tag == "from":
            lines.append(f"  ENTRY->>FLOW: {kind}：{secondary or '交易入口'}")
        else:
            lines.append(f"  FLOW->>FLOW: {kind}：{secondary or tag or '处理'}")

    close_completed_choices(0)
    lines.append("  FLOW-->>ENTRY: 返回交易处理结果")
    return "\n".join(lines)


def extract_transaction_flow(xml_path: Path | None) -> dict[str, Any]:
    if not xml_path:
        return {
            "nodes": [],
            "edges": [],
            "mermaid": build_status_mermaid("交易 XML 缺失"),
            "mermaid_quality": "missing",
            "mermaid_note": "交易 XML 缺失，无法生成流程图。",
            "service_ids_in_order": [],
        }
    try:
        root = read_xml(xml_path)
    except ET.ParseError as exc:
        return {
            "nodes": [],
            "edges": [],
            "mermaid": build_status_mermaid(f"XML 解析失败: {short_text(str(exc), 60)}"),
            "mermaid_quality": "parse_error",
            "mermaid_note": f"交易 XML 解析失败：{short_text(str(exc), 200)}",
            "service_ids_in_order": [],
        }

    nodes: list[dict[str, Any]] = []
    service_ids: list[str] = []
    node_steps: dict[int, int] = {}

    def walk(node: ET.Element, stack: list[str]) -> None:
        tag = strip_ns(node.tag)
        if not tag:
            return
        next_stack = stack + [tag]
        if tag in FLOW_TAGS:
            service_id = (node.attrib.get("serviceId") or "").strip()
            if service_id:
                service_ids.append(service_id)
            step = len(nodes) + 1
            node_steps[id(node)] = step
            nodes.append(
                {
                    "step": step,
                    "tag": tag,
                    "xml_path": "/" + "/".join(next_stack),
                    "depth": len(next_stack),
                    "id": (node.attrib.get("id") or "").strip(),
                    "serviceId": service_id,
                    "target": (node.attrib.get("ref") or node.attrib.get("uri") or node.attrib.get("target") or "").strip(),
                    "condition": condition_from_node(node) if tag in {"case", "when", "choice"} else "",
                    "attributes": compact_attrs(node.attrib),
                    "text": short_text(node.text, 120),
                }
            )
        for child in list(node):
            walk(child, next_stack)

    walk(root, [])
    flow_edges, edge_quality = build_flow_edges(root, node_steps)
    return {
        "nodes": nodes,
        "edges": flow_edges,
        "mermaid": build_mermaid(nodes, flow_edges),
        "mermaid_quality": "linear_draft",
        "mermaid_note": f"脚本生成的是线性时序草图，edges 质量为 {edge_quality}；遇到复杂动态路由时，交易子Agent 必须结合节点说明核对。",
        "service_ids_in_order": service_ids,
    }


def find_base_xml_candidates(service_id: str, xml_files: list[Path]) -> list[Path]:
    service_cmp = comparable_name(service_id)
    candidates: list[Path] = []
    for path in xml_files:
        if not is_base_xml_candidate(path):
            continue
        if path.stem == service_id or path.stem.lower() == service_id.lower():
            candidates.append(path)
            continue
        if comparable_name(path.stem) == service_cmp:
            candidates.append(path)
    if candidates:
        return candidates
    for path in xml_files:
        if not is_base_xml_candidate(path):
            continue
        try:
            text = path.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        if service_id in text:
            candidates.append(path)
    return candidates


def child_text(node: ET.Element, tag_name: str) -> str:
    for child in list(node):
        if strip_ns(child.tag) == tag_name:
            return (child.text or "").strip()
    return ""


def find_biz_candidates(service_id: str, biz_files: list[Path]) -> list[Path]:
    service_cmp = comparable_name(service_id)
    candidates: list[Path] = []
    for path in biz_files:
        if path.stem == service_id or path.stem.lower() == service_id.lower() or comparable_name(path.stem) == service_cmp:
            candidates.append(path)
            continue
        try:
            root = read_xml(path)
        except (ET.ParseError, OSError):
            continue
        root_ids = [
            root.attrib.get("id", ""),
            root.attrib.get("name", ""),
            root.attrib.get("nickName", ""),
            child_text(root, "id"),
            child_text(root, "name"),
            child_text(root, "nickName"),
        ]
        if any(comparable_name(value) == service_cmp for value in root_ids if value):
            candidates.append(path)
    if candidates:
        return candidates
    for path in biz_files:
        try:
            text = path.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        if service_id in text:
            candidates.append(path)
    return candidates


def simple_class_name(value: str) -> str:
    value = value.strip()
    value = re.sub(r"[#:/].*$", "", value)
    value = value.rsplit(".", 1)[-1]
    return re.sub(r"[^A-Za-z0-9_$]", "", value)


def java_hints_from_base_xml(paths: list[Path]) -> set[str]:
    hints: set[str] = set()
    for path in paths:
        try:
            root = read_xml(path)
        except ET.ParseError:
            continue
        for node in root.iter():
            for attr in JAVA_HINT_ATTRS:
                value = (node.attrib.get(attr) or "").strip()
                if not value:
                    continue
                simple = simple_class_name(value)
                if simple:
                    hints.add(simple)
    return hints


def biz_self_ids(root: ET.Element) -> set[str]:
    values = {
        root.attrib.get("id", ""),
        root.attrib.get("name", ""),
        root.attrib.get("nickName", ""),
        child_text(root, "id"),
        child_text(root, "name"),
        child_text(root, "nickName"),
    }
    return {simple_class_name(value) for value in values if simple_class_name(value)}


def adapter_ids_from_biz(paths: list[Path], exclude_ids: set[str] | None = None) -> set[str]:
    adapter_ids: set[str] = set()
    exclude_cmps = {comparable_name(item) for item in (exclude_ids or set()) if item}
    for path in paths:
        try:
            root = read_xml(path)
        except (ET.ParseError, OSError):
            continue
        local_excludes = set(exclude_ids or set()) | biz_self_ids(root)
        local_exclude_cmps = exclude_cmps | {comparable_name(item) for item in local_excludes if item}
        for node in root.iter():
            if strip_ns(node.tag) != "adapter":
                continue
            adapter_id = child_text(node, "id") or node.attrib.get("id", "")
            simple = simple_class_name(adapter_id)
            if comparable_name(simple) in local_exclude_cmps:
                continue
            if simple:
                adapter_ids.add(simple)
    return adapter_ids


def find_java_candidates(
    service_id: str,
    base_xml_candidates: list[Path],
    java_files: list[Path],
    biz_candidates: list[Path] | None = None,
) -> list[Path]:
    candidates: list[Path] = []
    service_cmp = comparable_name(service_id)
    hints = java_hints_from_base_xml(base_xml_candidates)
    if biz_candidates:
        hints.update(adapter_ids_from_biz(biz_candidates, {service_id}))
    hint_cmps = {comparable_name(item) for item in hints}
    for path in java_files:
        stem_cmp = comparable_name(path.stem)
        if path.stem == service_id or path.stem.lower() == service_id.lower() or stem_cmp == service_cmp:
            candidates.append(path)
            continue
        if stem_cmp in hint_cmps:
            candidates.append(path)
    if candidates:
        return candidates
    for path in java_files:
        try:
            text = path.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        if service_id in text:
            candidates.append(path)
    return candidates


def summarize_biz(paths: list[Path], service_id: str = "") -> list[dict[str, Any]]:
    summaries: list[dict[str, Any]] = []
    for path in paths[:5]:
        item: dict[str, Any] = {"path": as_posix(path)}
        try:
            root = read_xml(path)
        except ET.ParseError as exc:
            item["parse_error"] = str(exc)
            summaries.append(item)
            continue
        item["root_tag"] = strip_ns(root.tag)
        item["root_attributes"] = compact_attrs(root.attrib)
        item["id"] = child_text(root, "id") or root.attrib.get("id", "")
        item["nickName"] = child_text(root, "nickName") or root.attrib.get("nickName", "")
        item["type"] = child_text(root, "type") or root.attrib.get("type", "")
        self_ids = biz_self_ids(root)
        if service_id:
            self_ids.add(service_id)
        self_id_cmps = {comparable_name(item) for item in self_ids if item}
        adapters: list[dict[str, Any]] = []
        for node in root.iter():
            if strip_ns(node.tag) != "adapter":
                continue
            adapter_id = child_text(node, "id") or node.attrib.get("id", "")
            adapters.append(
                {
                    "id": adapter_id,
                    "type": child_text(node, "type") or node.attrib.get("type", ""),
                    "description": child_text(node, "description") or node.attrib.get("description", ""),
                    "forced": child_text(node, "forced") or node.attrib.get("forced", ""),
                    "self_reference": comparable_name(adapter_id) in self_id_cmps,
                }
            )
        item["adapter_count"] = len(adapters)
        item["adapters"] = adapters[:30]
        item["adapter_java_hints"] = sorted(adapter_ids_from_biz([path], self_ids))
        summaries.append(item)
    return summaries


def summarize_base_xml(paths: list[Path]) -> list[dict[str, Any]]:
    summaries: list[dict[str, Any]] = []
    for path in paths[:5]:
        item: dict[str, Any] = {"path": as_posix(path)}
        try:
            root = read_xml(path)
        except ET.ParseError as exc:
            item["parse_error"] = str(exc)
            summaries.append(item)
            continue
        item["root_tag"] = strip_ns(root.tag)
        item["root_attributes"] = compact_attrs(root.attrib)
        java_hints = sorted(java_hints_from_base_xml([path]))
        item["java_hints"] = java_hints
        child_tags: list[str] = []
        for child in list(root)[:20]:
            tag = strip_ns(child.tag)
            if tag:
                child_tags.append(tag)
        item["child_tags"] = child_tags
        summaries.append(item)
    return summaries


def combine_service_identify_structures(items: list[dict[str, Any]], mode: str) -> dict[str, Any]:
    return {
        "selected_mode": mode,
        "file_count": len(items),
        "files": items,
        "channel_count": sum(item.get("channel_count", 0) for item in items),
        "switch_count": sum(item.get("switch_count", 0) for item in items),
        "selected_switch_count": sum(item.get("selected_switch_count", 0) for item in items),
        "selected_case_count": sum(item.get("selected_case_count", 0) for item in items),
    }


def build_index_inputs(summary: dict[str, Any], transactions: list[dict[str, Any]], modules: list[dict[str, Any]]) -> dict[str, Any]:
    return {
        "summary": summary,
        "service_identify_structure": summary.get("service_identify_structure", {}),
        "batch_size": summary.get("batch_size"),
        "module_batch_count": summary.get("module_batch_count"),
        "module_batch_paths": summary.get("module_batch_paths", []),
        "transaction_task_count": summary.get("transaction_task_count"),
        "transaction_task_paths": summary.get("transaction_task_paths", []),
        "transactions": [
            {
                "transaction_key": tx["transaction_key"],
                "task_path": tx.get("task_path"),
                "document_link": tx.get("document_link"),
                "summary_link": tx.get("summary_link"),
                "transaction_ref": tx.get("transaction_ref"),
                "transaction_match_ref": tx.get("transaction_match_ref"),
                "primary_case": tx.get("primary_case"),
                "alias_count": len(tx.get("aliases", [])),
                "module_count": len(set(tx.get("module_service_ids", []))),
                "module_service_ids": sorted(set(tx.get("module_service_ids", []))),
                "flow_node_count": len(tx.get("flow_summary", {}).get("nodes", [])),
            }
            for tx in transactions
        ],
        "modules": [
            {
                "serviceId": module["serviceId"],
                "document_link": module.get("document_link"),
                "summary_link": module.get("summary_link"),
                "used_by_transactions": module.get("used_by_transactions", []),
                "base_xml_candidate_count": len(module.get("base_xml_candidates", [])),
                "biz_candidate_count": len(module.get("biz_candidates", [])),
                "java_candidate_count": len(module.get("java_candidates", [])),
            }
            for module in modules
        ],
        "missing_references": summary.get("missing_references", []),
    }


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def clear_generated_task_files(out: Path) -> None:
    task_dir = out / "tasks"
    batch_dir = task_dir / "batches"
    for directory, patterns in (
        (task_dir, ("module-*.json", "transaction-*.json")),
        (batch_dir, ("module-batch-*.json", "transaction-batch-*.json")),
    ):
        if not directory.exists():
            continue
        for pattern in patterns:
            for path in directory.glob(pattern):
                if path.is_file():
                    path.unlink()


def module_batch_summary_item(item: dict[str, Any]) -> dict[str, Any]:
    return {
        "task_path": item.get("task_path"),
        "document_path": item.get("document_path"),
        "document_link": item.get("document_link"),
        "summary_path": item.get("summary_path"),
        "summary_link": item.get("summary_link"),
        "serviceId": item.get("serviceId"),
        "used_by_transactions": item.get("used_by_transactions", []),
        "base_xml_candidate_count": len(item.get("base_xml_candidates", [])),
        "biz_candidate_count": len(item.get("biz_candidates", [])),
        "java_candidate_count": len(item.get("java_candidates", [])),
    }


def write_module_batch_files(out: Path, items: list[dict[str, Any]], batch_size: int) -> list[str]:
    if batch_size < 1:
        raise ValueError("batch_size must be >= 1")
    batch_paths: list[str] = []
    batch_dir = out / "tasks" / "batches"
    for start in range(0, len(items), batch_size):
        batch_items = items[start : start + batch_size]
        batch_index = len(batch_paths) + 1
        batch_path = batch_dir / f"module-batch-{batch_index:03d}.json"
        payload = {
            "kind": "module",
            "batch_index": batch_index,
            "batch_size": batch_size,
            "task_count": len(batch_items),
            "task_paths": [item.get("task_path") for item in batch_items],
            "tasks": [module_batch_summary_item(item) for item in batch_items],
        }
        write_json(batch_path, payload)
        batch_paths.append(as_posix(batch_path) or str(batch_path))
    return batch_paths


def build_tasks(args: argparse.Namespace) -> None:
    service_identify_args = [item for group in args.service_identify for item in group]
    service_identifies = [Path(path) for path in service_identify_args]
    xml_root = Path(args.xml_root)
    biz_root = Path(args.biz_root) if args.biz_root else xml_root
    java_root = Path(args.java_root)
    out = Path(args.out)
    mode = str(args.mode)
    batch_size = int(args.batch_size)

    xml_files = collect_files(xml_root, ".xml")
    biz_files = collect_files_case_insensitive(biz_root, ".biz")
    java_files = collect_files(java_root, ".java")
    cases: list[tuple[Path, int, ET.Element]] = []
    service_identify_structures: list[dict[str, Any]] = []
    for service_identify in service_identifies:
        root = read_xml(service_identify)
        structure = summarize_service_identify(root, mode)
        structure["service_identify"] = as_posix(service_identify)
        service_identify_structures.append(structure)
        for idx, case in enumerate(iter_mode_cases(root, mode), start=1):
            cases.append((service_identify, idx, case))
    service_identify_structure = combine_service_identify_structures(service_identify_structures, mode)

    grouped: dict[str, dict[str, Any]] = {}
    used_transaction_keys: dict[str, str] = {}
    used_module_keys: dict[str, str] = {}
    missing_references: list[dict[str, str]] = []

    for service_identify, idx, case in cases:
        ref = transaction_ref_from_case(case)
        match_ref = transaction_ref_for_match(ref)
        raw_candidates = find_xml_candidates(match_ref, xml_files, xml_root)
        candidates, rejected_candidates = filter_transaction_xml_candidates(raw_candidates)
        identity = transaction_identity(match_ref, candidates, case)
        key_base = transaction_key(match_ref, candidates, case, xml_root)
        snapshot = case_snapshot(case, idx, service_identify)
        if not candidates:
            if rejected_candidates:
                rejected = ", ".join(as_posix(path) or str(path) for path in rejected_candidates)
                detail = f"Matched XML candidates did not contain <proxyEngine>: {rejected}"
            else:
                detail = f"No XML candidate found for match reference: {match_ref or key_base}"
            missing_references.append({"type": "transaction_xml", "reference": ref or key_base, "detail": detail})
        if identity not in grouped:
            key = unique_output_key(key_base, identity, used_transaction_keys)
            tx_dir = out / "transactions" / key
            tx_doc = tx_dir / "analysis.md"
            tx_summary = tx_dir / "summary.json"
            grouped[identity] = {
                "transaction_key": key,
                "primary_case": snapshot,
                "aliases": [],
                "transaction_ref": ref,
                "transaction_match_ref": match_ref,
                "transaction_xml": as_posix(candidates[0]) if candidates else None,
                "transaction_xml_candidates": [as_posix(path) for path in candidates],
                "rejected_transaction_xml_candidates": [as_posix(path) for path in rejected_candidates],
                "document_path": as_posix(tx_doc),
                "document_link": relative_link(tx_doc, out),
                "summary_path": as_posix(tx_summary),
                "summary_link": relative_link(tx_summary, out),
                "task_path": as_posix(out / "tasks" / f"transaction-{key}.json"),
                "module_service_ids": [],
                "module_document_links": {},
            }
        else:
            grouped[identity]["aliases"].append(snapshot)

    modules_by_id: dict[str, dict[str, Any]] = {}
    module_usage: dict[str, set[str]] = defaultdict(set)

    for tx in grouped.values():
        tx_xml = Path(tx["transaction_xml"]) if tx["transaction_xml"] else None
        flow_summary = extract_transaction_flow(tx_xml)
        service_ids = flow_summary["service_ids_in_order"]
        tx["module_service_ids"] = service_ids
        tx["flow_summary"] = flow_summary
        tx["mermaid_draft"] = flow_summary["mermaid"]
        for service_id in set(service_ids):
            module_usage[service_id].add(tx["transaction_key"])
            if service_id in modules_by_id:
                continue
            base_candidates = find_base_xml_candidates(service_id, xml_files)
            biz_candidates = find_biz_candidates(service_id, biz_files)
            java_candidates = find_java_candidates(service_id, base_candidates, java_files, biz_candidates)
            if not base_candidates and not biz_candidates:
                missing_references.append({"type": "base_or_biz_config", "reference": service_id, "detail": "No base XML or biz candidate found"})
            if not java_candidates:
                missing_references.append({"type": "java", "reference": service_id, "detail": "No Java candidate found from serviceId, base XML hints, or biz adapter ids"})
            module_key = unique_output_key(service_id, f"service:{service_id}", used_module_keys)
            module_dir = out / "modules" / module_key
            module_doc = module_dir / "analysis.md"
            module_summary = module_dir / "summary.json"
            modules_by_id[service_id] = {
                "serviceId": service_id,
                "base_xml_candidates": [as_posix(path) for path in base_candidates],
                "base_xml_summary": summarize_base_xml(base_candidates),
                "biz_candidates": [as_posix(path) for path in biz_candidates],
                "biz_summary": summarize_biz(biz_candidates, service_id),
                "java_candidates": [as_posix(path) for path in java_candidates],
                "document_path": as_posix(module_doc),
                "document_link": relative_link(module_doc, out),
                "summary_path": as_posix(module_summary),
                "summary_link": relative_link(module_summary, out),
                "task_path": as_posix(out / "tasks" / f"module-{module_key}.json"),
                "used_by_transactions": [],
            }

    for service_id, tx_keys in module_usage.items():
        modules_by_id[service_id]["used_by_transactions"] = sorted(tx_keys)

    for tx in grouped.values():
        tx_dir = out / "transactions" / tx["transaction_key"]
        links: dict[str, str] = {}
        for service_id in set(tx["module_service_ids"]):
            module = modules_by_id.get(service_id)
            if module and module.get("document_path"):
                links[service_id] = relative_link(Path(module["document_path"]), tx_dir)
        tx["module_document_links"] = links

    transactions = sorted(grouped.values(), key=lambda item: item["transaction_key"])
    modules = sorted(modules_by_id.values(), key=lambda item: item["serviceId"])

    out.mkdir(parents=True, exist_ok=True)
    (out / "tasks").mkdir(parents=True, exist_ok=True)
    clear_generated_task_files(out)
    for tx in transactions:
        write_json(Path(tx["task_path"]), tx)
    for module in modules:
        write_json(Path(module["task_path"]), module)
    module_batch_paths = write_module_batch_files(out, modules, batch_size)
    transaction_task_paths = [tx["task_path"] for tx in transactions]

    summary = {
        "service_identify": as_posix(service_identifies[0]) if len(service_identifies) == 1 else None,
        "service_identifies": [as_posix(path) for path in service_identifies],
        "xml_root": as_posix(xml_root),
        "biz_root": as_posix(biz_root),
        "java_root": as_posix(java_root),
        "out": as_posix(out),
        "mode": mode,
        "raw_case_count": len(cases),
        "deduped_transaction_count": len(transactions),
        "unique_module_count": len(modules),
        "batch_size": batch_size,
        "module_batch_count": len(module_batch_paths),
        "module_batch_paths": module_batch_paths,
        "transaction_task_count": len(transaction_task_paths),
        "transaction_task_paths": transaction_task_paths,
        "missing_references": missing_references,
        "service_identify_structure": service_identify_structure,
    }
    write_json(out / "summary.json", summary)
    write_json(out / "transactions.json", transactions)
    write_json(out / "modules.json", modules)
    write_json(out / "index_inputs.json", build_index_inputs(summary, transactions, modules))

    print("serviceIdentify:")
    for service_identify in service_identifies:
        print(f"  - {service_identify}")
    print(f"xml root: {xml_root}")
    print(f"biz root: {biz_root}")
    print(f"java root: {java_root}")
    print(f"output: {out}")
    print(f"mode: {mode}")
    print(f"raw cases: {len(cases)}")
    print(f"deduped transactions: {len(transactions)}")
    print(f"unique modules: {len(modules)}")
    print(f"module batch size: {batch_size}")
    print(f"module batches: {len(module_batch_paths)}")
    print(f"transaction tasks: {len(transaction_task_paths)}")
    print(f"missing references: {len(missing_references)}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Prepare Sm@rtESB transaction and module analysis tasks.")
    parser.add_argument(
        "--service-identify",
        required=True,
        nargs="+",
        action="append",
        help="One or more serviceIdentify.xml paths. Can also be passed repeatedly.",
    )
    parser.add_argument("--xml-root", required=True, help="Root directory containing Sm@rtESB XML files")
    parser.add_argument("--biz-root", help="Root directory containing Sm@rtESB .biz files; defaults to --xml-root")
    parser.add_argument("--java-root", required=True, help="Root directory containing Java source files")
    parser.add_argument("--out", required=True, help="Output directory for task JSON and later Markdown reports")
    parser.add_argument("--mode", default="8583", help="switch mode to extract, default: 8583")
    parser.add_argument("--batch-size", type=int, default=10, help="Module tasks per module child-agent batch, default: 10")
    return parser.parse_args()


def main() -> None:
    build_tasks(parse_args())


if __name__ == "__main__":
    main()
