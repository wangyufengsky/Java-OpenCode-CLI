import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SKILL_DIR = Path(__file__).resolve().parents[1]
SCRIPT = SKILL_DIR / "scripts" / "prepare_rewrite_review_tasks.py"
SKILL_MD = SKILL_DIR / "SKILL.md"


def run_prepare(tmp_path: Path, *args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(SCRIPT), *args],
        cwd=tmp_path,
        text=True,
        capture_output=True,
        check=False,
    )


class PrepareRewriteReviewTasksTest(unittest.TestCase):
    def test_rejects_non_windows_out_path(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            tmp_path = Path(temp_dir)
            result = run_prepare(
                tmp_path,
                "--out",
                str(tmp_path / "review"),
                "--transaction",
                "CaRolloutRepeal=转账撤销",
                "--overwrite",
            )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("--out must be a Windows absolute path", result.stderr)

    def test_emits_windows_paths_when_using_local_out_mirror(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            tmp_path = Path(temp_dir)
            local_out = tmp_path / "local-review"
            result = run_prepare(
                tmp_path,
                "--out",
                r"D:\review-output\smartesb-case",
                "--local-out",
                str(local_out),
                "--transaction",
                "CaRolloutRepeal=转账撤销",
                "--overwrite",
            )

            self.assertEqual(result.returncode, 0, result.stderr)

            task_path = local_out / "tasks" / "transaction-CaRolloutRepeal.json"
            task = json.loads(task_path.read_text(encoding="utf-8"))
            index_inputs = json.loads((local_out / "index_inputs.json").read_text(encoding="utf-8"))

        self.assertEqual(
            task["task_path"],
            r"D:\review-output\smartesb-case\tasks\transaction-CaRolloutRepeal.json",
        )
        self.assertEqual(
            task["output"]["review_md"],
            r"D:\review-output\smartesb-case\reports\CaRolloutRepeal\review.md",
        )
        self.assertEqual(
            task["output"]["summary_json"],
            r"D:\review-output\smartesb-case\reports\CaRolloutRepeal\summary.json",
        )
        self.assertTrue(task["skill"]["summary_schema"].endswith(r"schemas\transaction-summary.schema.json"))
        self.assertTrue(index_inputs["schemas"]["transaction_summary"].endswith(r"schemas\transaction-summary.schema.json"))

    def test_precreates_transaction_and_top_level_outputs(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            tmp_path = Path(temp_dir)
            local_out = tmp_path / "local-review"
            result = run_prepare(
                tmp_path,
                "--out",
                r"D:\review-output\smartesb-case",
                "--local-out",
                str(local_out),
                "--transaction",
                "CaRolloutRepeal=转账撤销",
                "--overwrite",
            )

            self.assertEqual(result.returncode, 0, result.stderr)

            expected_files = [
                local_out / "index.md",
                local_out / "summary.md",
                local_out / "reports" / "CaRolloutRepeal" / "review.md",
                local_out / "reports" / "CaRolloutRepeal" / "mapping-matrix.md",
                local_out / "reports" / "CaRolloutRepeal" / "sections" / "01-findings.md",
                local_out / "reports" / "CaRolloutRepeal" / "sections" / "02-code-chains.md",
                local_out / "reports" / "CaRolloutRepeal" / "sections" / "03-protocol-review.md",
                local_out / "reports" / "CaRolloutRepeal" / "sections" / "04-behavior-review.md",
                local_out / "reports" / "CaRolloutRepeal" / "sections" / "05-verification.md",
                local_out / "reports" / "CaRolloutRepeal" / "sections" / "06-code-standard.md",
                local_out / "reports" / "CaRolloutRepeal" / "summary.json",
            ]

            for path in expected_files:
                self.assertTrue(path.is_file(), f"missing precreated output: {path}")

    def test_task_declares_exact_markers_for_precreated_markdown(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            tmp_path = Path(temp_dir)
            local_out = tmp_path / "local-review"
            result = run_prepare(
                tmp_path,
                "--out",
                r"D:\review-output\smartesb-case",
                "--local-out",
                str(local_out),
                "--transaction",
                "CaRolloutRepeal=转账撤销",
                "--overwrite",
            )

            self.assertEqual(result.returncode, 0, result.stderr)

            task = json.loads(
                (local_out / "tasks" / "transaction-CaRolloutRepeal.json").read_text(encoding="utf-8")
            )
            markers = task["output_markers"]

            self.assertEqual(markers["findings_md"], "<!-- OPENCODE_APPEND:01-findings -->")
            self.assertEqual(markers["review_md"], "<!-- OPENCODE_APPEND:review -->")
            self.assertIn("edit_text", task["skill"]["agentbridge_write_tools"])
            self.assertIn("write_file", task["skill"]["agentbridge_write_tools"])
            self.assertNotIn("create_new_file", json.dumps(task["skill"]))
            self.assertNotIn("intellij-idea", json.dumps(task["skill"]))
            self.assertNotIn("intellij-index", json.dumps(task["skill"]))

            for key, marker in markers.items():
                if not key.endswith("_md"):
                    continue
                logical_path = task["output"][key]
                relative = logical_path.removeprefix(r"D:\review-output\smartesb-case").lstrip("\\")
                local_path = local_out / Path(relative.replace("\\", "/"))
                self.assertIn(marker, local_path.read_text(encoding="utf-8"))

    def test_skill_frontmatter_uses_use_when_trigger_description(self) -> None:
        text = SKILL_MD.read_text(encoding="utf-8")
        frontmatter = text.split("---", 2)[1]
        description = next(
            line.split(":", 1)[1].strip()
            for line in frontmatter.splitlines()
            if line.startswith("description:")
        )

        self.assertTrue(description.startswith("Use when"), description)

    def test_skill_docs_do_not_keep_stale_file_creation_contracts(self) -> None:
        text = SKILL_MD.read_text(encoding="utf-8")

        self.assertNotIn("空报告目录结构", text)
        self.assertNotIn("写完整报告到 `reports\\<transaction>\\review.md`", text)

    def test_skill_docs_use_current_agentbridge_contract_language(self) -> None:
        files = [
            SKILL_MD,
            SKILL_DIR / "prompts" / "rerun-single-transaction.md",
            SKILL_DIR / "prompts" / "run-transaction-review.md",
            SKILL_DIR / "prompts" / "synthesize-index.md",
            SKILL_DIR.parents[1]
            / "smartesb-rewrite-code-review-prompt-pack"
            / "prompts"
            / "synthesize-index.md",
        ]
        combined = "\n".join(path.read_text(encoding="utf-8") for path in files)

        self.assertNotIn("禁止调用 `write_file`", combined)
        self.assertNotIn("不得调用 `write_file`", combined)
        self.assertNotIn("刷新索引", combined)
        self.assertNotIn("索引疑似过期", combined)
        self.assertIn("write_file", combined)
        self.assertIn("项目文件视图", combined)


if __name__ == "__main__":
    unittest.main()
