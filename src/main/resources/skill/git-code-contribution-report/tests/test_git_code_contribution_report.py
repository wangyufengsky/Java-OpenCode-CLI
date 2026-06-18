import importlib.util
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest


SCRIPT_PATH = pathlib.Path(__file__).resolve().parents[1] / "scripts" / "git_code_contribution_report.py"
SPEC = importlib.util.spec_from_file_location("git_code_contribution_report", SCRIPT_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class CommentFilteringTest(unittest.TestCase):
    def test_java_comment_only_lines_are_not_countable(self):
        state = MODULE.CommentState()

        self.assertFalse(MODULE.is_countable_code_line("src/Demo.java", " // comment", state))
        self.assertFalse(MODULE.is_countable_code_line("src/Demo.java", "/* start", state))
        self.assertFalse(MODULE.is_countable_code_line("src/Demo.java", "still comment", state))
        self.assertFalse(MODULE.is_countable_code_line("src/Demo.java", "end */", state))

    def test_java_code_with_inline_comment_is_countable(self):
        state = MODULE.CommentState()

        self.assertTrue(MODULE.is_countable_code_line("src/Demo.java", "int total = 1; // comment", state))

    def test_python_and_yaml_comment_lines_are_not_countable(self):
        self.assertFalse(MODULE.is_countable_code_line("script.py", "# comment", MODULE.CommentState()))
        self.assertFalse(MODULE.is_countable_code_line("config.yaml", "  # comment", MODULE.CommentState()))
        self.assertTrue(MODULE.is_countable_code_line("config.yaml", "enabled: true # comment", MODULE.CommentState()))


class RankingTest(unittest.TestCase):
    def test_workload_score_uses_commits_files_and_non_comment_lines(self):
        author = {
            "commit_count": 2,
            "file_change_count": 3,
            "non_comment_added": 10,
            "non_comment_deleted": 4,
            "non_comment_net": 6,
        }

        score = MODULE.calculate_workload_score(author)

        self.assertEqual(score, 27.7)

    def test_quality_adjustment_is_bounded_and_applied_to_base_score(self):
        self.assertEqual(MODULE.calculate_adjusted_workload_score(100.0, 40), 130.0)
        self.assertEqual(MODULE.calculate_adjusted_workload_score(100.0, -40), 70.0)
        self.assertEqual(MODULE.calculate_adjusted_workload_score(80.0, 12.5), 90.0)

    def test_quality_findings_are_scored_by_central_rules(self):
        summary = {
            "findings": [
                {
                    "dimension": "code_standard",
                    "polarity": "negative",
                    "severity": "high",
                    "rule_id": "unsafe_format",
                    "evidence": "格式化金额未做边界处理",
                    "source": "scanner",
                    "attribution": "owned_hunk",
                    "owned_hunk_id": "h1",
                },
                {
                    "dimension": "risk_control",
                    "polarity": "negative",
                    "severity": "medium",
                    "rule_id": "missing_boundary_check",
                    "evidence": "缺少空值保护",
                    "source": "scanner",
                    "attribution": "owned_hunk",
                    "owned_hunk_id": "h2",
                },
                {
                    "dimension": "maintainability",
                    "polarity": "positive",
                    "severity": "high",
                    "rule_id": "clear_reuse_boundary",
                    "evidence": "公共能力边界清晰且有调用点",
                },
            ],
            "code_snippets": [],
        }

        score = MODULE.calculate_quality_score(summary)

        self.assertEqual(score["components_by_dimension"]["code_standard"], -2)
        self.assertEqual(score["components_by_dimension"]["risk_control"], -1)
        self.assertEqual(score["components_by_dimension"]["maintainability"], 5)
        self.assertEqual(score["components_by_dimension"]["reviewability"], 0)
        self.assertEqual(score["quality_adjustment_percent"], 2)

    def test_duplicate_negative_scanner_rule_only_deducts_once(self):
        summary = {
            "findings": [
                {
                    "dimension": "maintainability",
                    "polarity": "negative",
                    "severity": "medium",
                    "rule_id": "DataClass",
                    "source": "scanner",
                    "attribution": "owned_hunk",
                    "owned_hunk_id": "h1",
                },
                {
                    "dimension": "maintainability",
                    "polarity": "negative",
                    "severity": "medium",
                    "rule_id": "DataClass",
                    "source": "scanner",
                    "attribution": "owned_hunk",
                    "owned_hunk_id": "h2",
                },
            ],
            "code_snippets": [],
        }

        score = MODULE.calculate_quality_score(summary)

        self.assertEqual(score["quality_adjustment_percent"], -1)
        self.assertEqual(len(score["scored_findings"]), 1)
        self.assertIn("ignored duplicate negative scanner rule: DataClass", score["scoring_notes"])

    def test_low_quality_snippets_do_not_create_fallback_score(self):
        summary = {
            "findings": [],
            "code_snippets": [
                {
                    "file": "src/Demo.java",
                    "line_start": 10,
                    "line_end": 12,
                    "dimension": "risk_control",
                    "severity": "medium",
                    "reason": "缺少边界保护",
                    "suggestion": "增加空值和范围校验",
                    "snippet": "value.toString();",
                }
            ],
        }

        score = MODULE.calculate_quality_score(summary)

        self.assertEqual(score["components_by_dimension"]["risk_control"], 0)
        self.assertEqual(score["quality_adjustment_percent"], 0)


class OutputLayoutTest(unittest.TestCase):
    def test_deleted_file_counts_non_comment_deleted_lines(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo = pathlib.Path(tmp) / "repo"
            out = pathlib.Path(tmp) / "out"
            repo.mkdir()
            subprocess.run(["git", "init", "-q"], cwd=repo, check=True)
            subprocess.run(["git", "config", "user.name", "Alice"], cwd=repo, check=True)
            subprocess.run(["git", "config", "user.email", "alice@example.com"], cwd=repo, check=True)
            (repo / "Removed.java").write_text(
                "class Removed {\n"
                "  // comment only\n"
                "  int first = 1;\n"
                "  int second = 2;\n"
                "}\n",
                encoding="utf-8",
            )
            subprocess.run(["git", "add", "Removed.java"], cwd=repo, check=True)
            subprocess.run(["git", "commit", "-q", "-m", "add removed file"], cwd=repo, check=True)

            subprocess.run(["git", "config", "user.name", "Bob"], cwd=repo, check=True)
            subprocess.run(["git", "config", "user.email", "bob@example.com"], cwd=repo, check=True)
            (repo / "Removed.java").unlink()
            subprocess.run(["git", "add", "Removed.java"], cwd=repo, check=True)
            subprocess.run(["git", "commit", "-q", "-m", "delete removed file"], cwd=repo, check=True)

            subprocess.run(
                [
                    sys.executable,
                    str(SCRIPT_PATH),
                    "--repo",
                    str(repo),
                    "--since",
                    "2000-01-01",
                    "--until",
                    "2099-12-31",
                    "--out",
                    str(out),
                ],
                check=True,
                text=True,
                capture_output=True,
            )

            index_inputs = json.loads((out / "index_inputs.json").read_text(encoding="utf-8"))
            bob_task = next(task for task in index_inputs["tasks"] if task["author"] == "Bob <bob@example.com>")
            bob = json.loads(pathlib.Path(bob_task["detail_json"]).read_text(encoding="utf-8"))["summary"]

            self.assertEqual(bob["added"], 0)
            self.assertEqual(bob["deleted"], 5)
            self.assertEqual(bob["non_comment_added"], 0)
            self.assertEqual(bob["non_comment_deleted"], 4)
            self.assertEqual(bob["non_comment_net"], -4)
            self.assertEqual(bob["non_comment_churn"], 4)

    def test_script_precreates_per_author_details_tasks_and_reports(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo = pathlib.Path(tmp) / "repo"
            out = pathlib.Path(tmp) / "out"
            repo.mkdir()
            subprocess.run(["git", "init", "-q"], cwd=repo, check=True)
            subprocess.run(["git", "config", "user.name", "Alice"], cwd=repo, check=True)
            subprocess.run(["git", "config", "user.email", "alice@example.com"], cwd=repo, check=True)
            (repo / "Demo.java").write_text("class Demo {\n  // comment\n  int a = 1;\n}\n", encoding="utf-8")
            subprocess.run(["git", "add", "Demo.java"], cwd=repo, check=True)
            subprocess.run(["git", "commit", "-q", "-m", "add demo"], cwd=repo, check=True)

            subprocess.run(["git", "config", "user.name", "Bob"], cwd=repo, check=True)
            subprocess.run(["git", "config", "user.email", "bob@example.com"], cwd=repo, check=True)
            (repo / "config.yaml").write_text("# comment\nenabled: true\n", encoding="utf-8")
            subprocess.run(["git", "add", "config.yaml"], cwd=repo, check=True)
            subprocess.run(["git", "commit", "-q", "-m", "add config"], cwd=repo, check=True)

            subprocess.run(["git", "config", "user.name", "Carol"], cwd=repo, check=True)
            subprocess.run(["git", "config", "user.email", "carol@example.com"], cwd=repo, check=True)
            (repo / "README.md").write_text("# Documentation\n\nOnly docs.\n", encoding="utf-8")
            (repo / "docs").mkdir()
            (repo / "docs" / "spec.docx").write_text("word-like document\n", encoding="utf-8")
            (repo / "docs" / "plan.xlsx").write_text("excel-like workbook\n", encoding="utf-8")
            (repo / "docs" / "notes.txt").write_text("plain document\n", encoding="utf-8")
            subprocess.run(["git", "add", "README.md", "docs"], cwd=repo, check=True)
            subprocess.run(["git", "commit", "-q", "-m", "docs only"], cwd=repo, check=True)

            subprocess.run(["git", "config", "user.name", "Dave"], cwd=repo, check=True)
            subprocess.run(["git", "config", "user.email", "dave@example.com"], cwd=repo, check=True)
            (repo / "schema.sql").write_text("-- comment\ncreate table demo(id int);\n", encoding="utf-8")
            subprocess.run(["git", "add", "schema.sql"], cwd=repo, check=True)
            subprocess.run(["git", "commit", "-q", "-m", "add schema"], cwd=repo, check=True)

            subprocess.run(
                [
                    sys.executable,
                    str(SCRIPT_PATH),
                    "--repo",
                    str(repo),
                    "--since",
                    "2000-01-01",
                    "--until",
                    "2099-12-31",
                    "--out",
                    str(out),
                ],
                check=True,
                text=True,
                capture_output=True,
            )

            index_inputs = json.loads((out / "index_inputs.json").read_text(encoding="utf-8"))
            summary = json.loads((out / "summary.json").read_text(encoding="utf-8"))

            self.assertEqual(len(index_inputs["tasks"]), 3)
            self.assertEqual(len(summary["ranking"]), 3)
            self.assertNotIn("Carol <carol@example.com>", {row["author"] for row in summary["ranking"]})
            self.assertIn("Dave <dave@example.com>", {row["author"] for row in summary["ranking"]})
            self.assertIn("*.java", summary["metadata"]["default_include"])
            self.assertIn("*.js", summary["metadata"]["default_include"])
            self.assertIn("*.html", summary["metadata"]["default_include"])
            self.assertIn("*.xml", summary["metadata"]["default_include"])
            self.assertIn("*.yml", summary["metadata"]["default_include"])
            self.assertIn("*.sql", summary["metadata"]["default_include"])
            self.assertIn("*.md", summary["metadata"]["default_exclude"])
            self.assertIn("*.docx", summary["metadata"]["default_exclude"])
            self.assertIn("*.xlsx", summary["metadata"]["default_exclude"])
            self.assertEqual(summary["tasks"], index_inputs["tasks"])
            self.assertEqual(index_inputs["final_report"], str((out / "code-contribution-report.md").resolve()))
            self.assertEqual(index_inputs["final_report_marker"], MODULE.REPORT_MARKER)
            self.assertEqual(index_inputs["author_report_marker"], MODULE.AUTHOR_REPORT_MARKER)
            self.assertEqual(index_inputs["quality_summary_status_required"], "completed")
            self.assertTrue((out / "code-contribution-report.md").exists())
            self.assertEqual((out / "code-contribution-report.md").read_text(encoding="utf-8").strip(), MODULE.REPORT_MARKER)

            ranking_by_author = {row["author"]: row for row in summary["ranking"]}
            for task in index_inputs["tasks"]:
                detail_path = pathlib.Path(task["detail_json"])
                git_path = pathlib.Path(task["git_json"])
                pmd_path = pathlib.Path(task["pmd_json"])
                report_path = pathlib.Path(task["report_md"])
                quality_path = pathlib.Path(task["quality_summary_json"])
                detail = json.loads(detail_path.read_text(encoding="utf-8"))
                git_detail = json.loads(git_path.read_text(encoding="utf-8"))
                pmd_detail = json.loads(pmd_path.read_text(encoding="utf-8"))
                ranking = ranking_by_author[task["author"]]
                expected_relative_path = f"reports/{task['author_key']}/person-report.md"
                expected_markdown_link = f"[person-report.md]({expected_relative_path})"

                self.assertTrue(detail_path.exists())
                self.assertTrue(git_path.exists())
                self.assertTrue(pmd_path.exists())
                self.assertTrue(report_path.exists())
                self.assertTrue(quality_path.exists())
                report_text = report_path.read_text(encoding="utf-8")
                quality_summary = json.loads(quality_path.read_text(encoding="utf-8"))
                self.assertIn("{{WORKLOAD_STRUCTURE_ANALYSIS}}", report_text)
                self.assertIn("{{OVERALL_EVALUATION}}", report_text)
                self.assertNotIn("{{OWNED_CHANGE_ROWS}}", report_text)
                self.assertNotIn("{{LOW_QUALITY_SNIPPETS}}", report_text)
                self.assertEqual(quality_summary["status"], "completed")
                self.assertIn("Python 已根据当前 legacy 统计生成质量摘要", quality_summary["summary"])
                self.assertEqual(detail["author"], task["author"])
                self.assertEqual(detail["rank"], task["rank"])
                self.assertEqual(detail["summary"]["base_workload_score"], detail["summary"]["workload_score"])
                self.assertEqual(detail["summary"]["quality_adjustment_percent"], 0)
                self.assertEqual(detail["inputs"]["git_json"], task["git_json"])
                self.assertEqual(detail["inputs"]["pmd_json"], task["pmd_json"])
                self.assertEqual(len(detail["inputs"]), 2)
                self.assertEqual(git_detail["author"], task["author"])
                self.assertIn("commits", git_detail)
                self.assertIn("owned_hunks", git_detail)
                self.assertEqual(pmd_detail["scanner"], "pmd")
                self.assertEqual(detail["output"]["person_report_md"], task["report_md"])
                self.assertEqual(detail["output"]["quality_summary_json"], task["quality_summary_json"])
                self.assertEqual(detail["output"]["report_placeholders"], task["report_placeholders"])
                self.assertEqual(detail["output"]["quality_summary_status_required"], "completed")
                self.assertEqual(task["report_relative_path"], expected_relative_path)
                self.assertEqual(task["report_markdown_link"], expected_markdown_link)
                self.assertEqual(task["quality_summary_status_required"], "completed")
                self.assertEqual(detail["execution_worklist"], task["execution_worklist"])
                self.assertEqual(
                    [item["action"] for item in task["execution_worklist"]],
                    [
                        "read_detail_json",
                        "read_git_json",
                        "read_pmd_json",
                        "draft_analysis_and_evaluation",
                        "replace_analysis_placeholders",
                        "verify_outputs",
                        "final_response",
                    ],
                )
                self.assertEqual([item["step"] for item in task["execution_worklist"]], list(range(1, 8)))
                self.assertEqual(task["execution_worklist"][4]["target_path"], task["report_md"])
                self.assertEqual(
                    task["execution_worklist"][5]["required_paths"],
                    [task["report_md"], task["quality_summary_json"]],
                )
                self.assertFalse(pathlib.Path(task["report_relative_path"]).is_absolute())
                self.assertEqual(detail["output"]["person_report_relative_path"], expected_relative_path)
                self.assertEqual(detail["output"]["person_report_markdown_link"], expected_markdown_link)
                self.assertEqual(ranking["detail_json"], task["detail_json"])
                self.assertEqual(ranking["git_json"], task["git_json"])
                self.assertEqual(ranking["pmd_json"], task["pmd_json"])
                self.assertEqual(ranking["person_report_md"], task["report_md"])
                self.assertEqual(ranking["quality_summary_json"], task["quality_summary_json"])
                self.assertEqual(ranking["base_workload_score"], ranking["workload_score"])
                self.assertEqual(ranking["quality_adjustment_percent"], 0)
                self.assertEqual(ranking["person_report_placeholders"], task["report_placeholders"])
                self.assertEqual(ranking["person_report_relative_path"], task["report_relative_path"])
                self.assertEqual(ranking["person_report_markdown_link"], task["report_markdown_link"])
                self.assertNotIn("top_files", detail)
                self.assertNotIn("extensions", detail)
                self.assertNotIn("commits", detail)
                excluded_suffixes = {".md", ".markdown", ".mdown", ".mkd", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".pdf", ".txt"}
                self.assertTrue(excluded_suffixes.isdisjoint(git_detail["extensions"]))


class PromptContractTest(unittest.TestCase):
    def test_mcp_tool_names_match_opencode_runtime(self):
        skill_dir = SCRIPT_PATH.parents[1]
        workflow_file = "workflows/mcp-tool-contract.md"
        required_terms = [
            "OpenCode MCP 工具命名规范",
            "intellij-idea_read_file",
            "intellij-idea_get_file_text_by_path",
            "intellij-idea_replace_text_in_file",
            "intellij-idea_replace_text_undoable",
        ]

        workflow_content = (skill_dir / workflow_file).read_text(encoding="utf-8")
        for term in required_terms:
            self.assertIn(term, workflow_content, workflow_file)
        self.assertNotIn("intellij-index_ide_find_key_file", workflow_content, workflow_file)
        self.assertNotIn("intellij-index_ide_read_file", workflow_content, workflow_file)
        self.assertNotIn("mcp__intellij", workflow_content, workflow_file)

        prompt_required_terms = [
            "执行前必须先读取以下 workflow",
            "workflows/mcp-tool-contract.md",
            "以 workflow 规则为准",
        ]
        forbidden_prompt_terms = [
            "OpenCode MCP 工具命名规范",
            "intellij-idea_",
            "intellij-index_",
            "intellij-db",
            "<server-name>_<tool-name>",
        ]
        for relative_path in [
            "prompts/run-author-report.md",
            "prompts/synthesize-report.md",
        ]:
            content = (skill_dir / relative_path).read_text(encoding="utf-8")
            for term in prompt_required_terms:
                self.assertIn(term, content, relative_path)
            for term in forbidden_prompt_terms:
                self.assertNotIn(term, content, relative_path)

        skill_md = (skill_dir / "SKILL.md").read_text(encoding="utf-8")
        self.assertIn("workflows/mcp-tool-contract.md", skill_md)
        self.assertNotIn("intellij-idea_read_file", skill_md)
        self.assertNotIn("intellij-index_ide_find_references", skill_md)

    def test_quality_rules_forbid_subagent_code_reading(self):
        skill_dir = SCRIPT_PATH.parents[1]
        required_terms = [
            "不得读取业务代码",
            "pmdDetail.attributed_findings",
            "pmdDetail.code_snippets",
            "不新增负向扣分 finding",
        ]

        for relative_path in [
            "workflows/quality-scoring.md",
            "prompts/run-author-report.md",
        ]:
            content = (skill_dir / relative_path).read_text(encoding="utf-8")
            for term in required_terms:
                self.assertIn(term, content, relative_path)

    def test_skill_md_delegates_detailed_contracts_to_workflows(self):
        skill_dir = SCRIPT_PATH.parents[1]
        workflows = {
            "workflows/statistics-preparation.md": [
                "统计准备脚本",
                "--author-map",
                "默认统计白名单",
                "Comment Filtering Scope",
            ],
            "workflows/subagent-contract.md": [
                "Sub-Agent Dispatch Procedure",
                "Sub-Agent Contract",
                "单批最多派发 5 个子 agent",
            ],
            "workflows/quality-scoring.md": [
                "子 agent 质量摘要 JSON",
                '"dimension": "code_standard"',
                "quality_adjustment_percent",
            ],
            "workflows/report-writing.md": [
                "Markdown 表格安全规则",
                "Report Requirements",
                "AI Ranking Analysis Rules",
            ],
        }

        for relative_path, terms in workflows.items():
            content = (skill_dir / relative_path).read_text(encoding="utf-8")
            for term in terms:
                self.assertIn(term, content, relative_path)

        skill_md = (skill_dir / "SKILL.md").read_text(encoding="utf-8")
        for relative_path in workflows:
            self.assertIn(relative_path, skill_md)
        bulky_terms = [
            "默认统计白名单",
            "子 agent 质量摘要 JSON",
            "Markdown 表格安全规则",
            "Comment Filtering Scope",
            "Author Map",
        ]
        for term in bulky_terms:
            self.assertNotIn(term, skill_md)

    def test_execution_docs_do_not_contain_development_stage_instructions(self):
        skill_dir = SCRIPT_PATH.parents[1]
        docs = [
            "SKILL.md",
            "agents/openai.yaml",
            "prompts/run-author-report.md",
            "prompts/synthesize-report.md",
            "templates/code-contribution-report.md",
            "templates/person-code-contribution-report.md",
            "workflows/mcp-tool-contract.md",
            "workflows/quality-scoring.md",
            "workflows/report-writing.md",
            "workflows/statistics-preparation.md",
            "workflows/subagent-contract.md",
        ]
        forbidden_terms = [
            "当前 Codex",
            "Codex 环境",
            "旧工具名",
            "开发阶段",
            "我给你",
            "注意力分散",
            "规则漂移",
            "本 prompt 不复制",
            "重新复制",
            "不要写当前",
            "未列入本节",
            "未列入上表的旧",
        ]

        for relative_path in docs:
            content = (skill_dir / relative_path).read_text(encoding="utf-8")
            for term in forbidden_terms:
                self.assertNotIn(term, content, relative_path)

    def test_prompts_require_loading_workflows_before_execution(self):
        skill_dir = SCRIPT_PATH.parents[1]
        expected = {
            "prompts/run-author-report.md": [
                "执行前必须先读取以下 workflow",
                "workflows/mcp-tool-contract.md",
                "workflows/subagent-contract.md",
                "workflows/quality-scoring.md",
                "workflows/report-writing.md",
            ],
            "prompts/synthesize-report.md": [
                "执行前必须先读取以下 workflow",
                "workflows/mcp-tool-contract.md",
                "workflows/subagent-contract.md",
                "workflows/quality-scoring.md",
                "workflows/report-writing.md",
            ],
        }

        for relative_path, terms in expected.items():
            content = (skill_dir / relative_path).read_text(encoding="utf-8")
            for term in terms:
                self.assertIn(term, content, relative_path)

    def test_final_ranking_uses_adjusted_workload_score_order(self):
        skill_dir = SCRIPT_PATH.parents[1]
        files = [
            "prompts/synthesize-report.md",
            "workflows/subagent-contract.md",
            "workflows/report-writing.md",
            "templates/code-contribution-report.md",
        ]
        required_terms = [
            "先计算每个人的质量调整后 `workload_score`",
            "按质量调整后的 `workload_score` 降序排序",
            "脚本初始 `rank` 只能作为初始排名展示",
        ]
        forbidden_terms = [
            "先按 `summary.ranking` 输出排名表",
            "按 `summary.ranking` 生成排名表",
            "先按 `summary.json.ranking` 输出人员排名表",
            "按 `summary.json.ranking` 的排名顺序",
        ]

        for relative_path in files:
            content = (skill_dir / relative_path).read_text(encoding="utf-8")
            for term in required_terms:
                self.assertIn(term, content, relative_path)
            for term in forbidden_terms:
                self.assertNotIn(term, content, relative_path)

    def test_quality_reports_include_bounded_low_quality_code_snippets(self):
        skill_dir = SCRIPT_PATH.parents[1]
        files = [
            "workflows/quality-scoring.md",
            "workflows/report-writing.md",
            "prompts/run-author-report.md",
            "prompts/synthesize-report.md",
            "templates/person-code-contribution-report.md",
            "templates/code-contribution-report.md",
        ]
        required_terms = [
            "低质量代码片段",
            "code_snippets",
            "最多 3 个",
            "每个片段最多 12 行",
            "不得包含密钥、令牌、密码、手机号、身份证号、银行卡号",
            "不得粘贴完整文件",
        ]

        for relative_path in files:
            content = (skill_dir / relative_path).read_text(encoding="utf-8")
            for term in required_terms:
                self.assertIn(term, content, relative_path)

    def test_low_quality_snippets_do_not_create_fallback_score(self):
        skill_dir = SCRIPT_PATH.parents[1]
        files = [
            "workflows/quality-scoring.md",
            "prompts/run-author-report.md",
        ]
        required_terms = [
            "`code_snippets` 由统计脚本或 Java 扫描归因预生成",
            "脚本不会根据片段自动补充扣分 finding",
        ]

        for relative_path in files:
            content = (skill_dir / relative_path).read_text(encoding="utf-8")
            for term in required_terms:
                self.assertIn(term, content, relative_path)

    def test_subagent_outputs_findings_and_does_not_set_final_quality_score(self):
        skill_dir = SCRIPT_PATH.parents[1]
        finding_terms = [
            '"findings"',
            '"polarity"',
            '"severity"',
            '"rule_id"',
            "子 agent 不得写入 `quality-summary.json`",
            "子 agent 不得写入 `quality_adjustment_percent`",
            "主 agent 必须使用脚本统一计算质量分",
            "python <path-to-this-skill>\\scripts\\git_code_contribution_report.py score-quality",
        ]
        forbidden_terms = [
            '"quality_adjustment_percent": 0',
            '{"dimension": "code_standard", "score": 0',
        ]

        for relative_path in [
            "workflows/quality-scoring.md",
            "prompts/run-author-report.md",
            "prompts/synthesize-report.md",
        ]:
            content = (skill_dir / relative_path).read_text(encoding="utf-8")
            for term in finding_terms:
                self.assertIn(term, content, relative_path)
            for term in forbidden_terms:
                self.assertNotIn(term, content, relative_path)

    def test_quality_summary_is_precreated_and_not_written_by_subagent(self):
        skill_dir = SCRIPT_PATH.parents[1]
        required_terms = [
            "`quality-summary.json` 已由统计脚本预生成",
            "不得写入 `detail.output.quality_summary_json`",
            "只替换 `detail.output.report_placeholders`",
        ]

        for relative_path in [
            "prompts/run-author-report.md",
            "workflows/subagent-contract.md",
            "workflows/report-writing.md",
        ]:
            content = (skill_dir / relative_path).read_text(encoding="utf-8")
            for term in required_terms:
                self.assertIn(term, content, relative_path)

    def test_subagent_must_call_write_tools_before_final_text(self):
        skill_dir = SCRIPT_PATH.parents[1]
        required_terms = [
            "禁止在写文件前输出进度说明",
            "不得以 `Let me write`、`Now I will write`、`我将写入` 这类文本结束",
            "必须立即调用 MCP 写入工具",
            "只有确认 `person-report.md` 写入成功且 `quality-summary.json` 已存在后，最终响应只能是 `DONE` 或 `BLOCKED`",
        ]

        for relative_path in [
            "prompts/run-author-report.md",
            "workflows/subagent-contract.md",
            "workflows/mcp-tool-contract.md",
        ]:
            content = (skill_dir / relative_path).read_text(encoding="utf-8")
            for term in required_terms:
                self.assertIn(term, content, relative_path)

    def test_subagent_must_execute_script_generated_worklist(self):
        skill_dir = SCRIPT_PATH.parents[1]
        required_terms = [
            "execution_worklist",
            "按 `step` 升序逐项执行",
            "不得把 worklist 作为最终响应",
            "不得在生成 worklist 后停止",
            "BLOCKED step=<step> action=<action> path=<path> reason=<reason>",
            "replace_analysis_placeholders",
            "verify_outputs",
            "final_response",
        ]

        for relative_path in [
            "prompts/run-author-report.md",
            "workflows/subagent-contract.md",
            "workflows/report-writing.md",
        ]:
            content = (skill_dir / relative_path).read_text(encoding="utf-8")
            for term in required_terms:
                self.assertIn(term, content, relative_path)

    def test_main_agent_must_rerun_missing_author_reports_before_final_report(self):
        skill_dir = SCRIPT_PATH.parents[1]
        files = [
            "SKILL.md",
            "workflows/subagent-contract.md",
            "prompts/synthesize-report.md",
        ]
        required_terms = [
            "生成主报告之前",
            "补跑该人员一次",
            "补跑校验仍未完成时，停止生成主报告",
        ]
        forbidden_terms = [
            "在最终报告“未完成个人报告”中列出，并不要伪造该人员分析",
        ]

        for relative_path in files:
            content = (skill_dir / relative_path).read_text(encoding="utf-8")
            for term in required_terms:
                self.assertIn(term, content, relative_path)
            for term in forbidden_terms:
                self.assertNotIn(term, content, relative_path)

    def test_quality_adjustment_contract_uses_thirty_percent_bound(self):
        skill_dir = SCRIPT_PATH.parents[1]
        files = [
            "SKILL.md",
            "workflows/quality-scoring.md",
            "workflows/subagent-contract.md",
            "workflows/report-writing.md",
            "prompts/run-author-report.md",
            "prompts/synthesize-report.md",
            "templates/code-contribution-report.md",
        ]

        for relative_path in files:
            content = (skill_dir / relative_path).read_text(encoding="utf-8")
            self.assertIn("[-30, 30]", content, relative_path)
            self.assertNotIn("[-15, 15]", content, relative_path)

    def test_quality_scoring_uses_configured_lightweight_negative_penalties(self):
        skill_dir = SCRIPT_PATH.parents[1]
        files = [
            "workflows/quality-scoring.md",
            "prompts/run-author-report.md",
        ]
        required_terms = [
            "统一分值表",
            "negative",
            "0",
            "-1",
            "-2",
            "positive",
            "+1",
            "+3",
            "+5",
        ]

        for relative_path in files:
            content = (skill_dir / relative_path).read_text(encoding="utf-8")
            for term in required_terms:
                self.assertIn(term, content, relative_path)

    def test_quality_components_use_code_standard_instead_of_tests_and_verification(self):
        skill_dir = SCRIPT_PATH.parents[1]
        files = [
            "workflows/quality-scoring.md",
            "prompts/run-author-report.md",
            "templates/person-code-contribution-report.md",
        ]

        for relative_path in files:
            content = (skill_dir / relative_path).read_text(encoding="utf-8")
            self.assertIn("code_standard", content, relative_path)
            self.assertIn("代码规范", content, relative_path)
            self.assertNotIn("tests_and_verification", content, relative_path)
            self.assertNotIn("测试与验证", content, relative_path)

    def test_prompts_require_markdown_table_cell_escaping(self):
        skill_dir = SCRIPT_PATH.parents[1]
        required_terms = [
            "Markdown 表格安全规则",
            "将 `|` 转义为 `\\|`",
            "表格行必须是单个物理行",
            "不要把 marker 放在表格内部",
            "每个表格块必须重复表头和分隔行",
        ]

        for relative_path in [
            "workflows/report-writing.md",
            "prompts/run-author-report.md",
            "prompts/synthesize-report.md",
        ]:
            content = (skill_dir / relative_path).read_text(encoding="utf-8")
            for term in required_terms:
                self.assertIn(term, content, relative_path)


if __name__ == "__main__":
    unittest.main()
