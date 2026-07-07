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
                },
                {
                    "dimension": "risk_control",
                    "polarity": "negative",
                    "severity": "medium",
                    "rule_id": "missing_boundary_check",
                    "evidence": "缺少空值保护",
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

        self.assertEqual(score["components_by_dimension"]["code_standard"], -8)
        self.assertEqual(score["components_by_dimension"]["risk_control"], -5)
        self.assertEqual(score["components_by_dimension"]["maintainability"], 5)
        self.assertEqual(score["components_by_dimension"]["reviewability"], 0)
        self.assertEqual(score["quality_adjustment_percent"], -8)

    def test_low_quality_snippets_are_scored_when_findings_miss_them(self):
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

        self.assertEqual(score["components_by_dimension"]["risk_control"], -5)
        self.assertEqual(score["quality_adjustment_percent"], -5)

    def test_changed_regions_are_sorted_by_top_file_priority_before_limit(self):
        author = {
            "top_files": [{"path": "High.java"}, {"path": "Low.java"}],
            "changed_regions": [
                {"file": "Low.java", "line_start": 1, "hunk": "@@ -1 +1 @@\n-  int value = 1;\n+  int value = 2;"},
                {"file": "High.java", "line_start": 1, "hunk": "@@ -1,2 +1,2 @@\n-  int a = 1;\n-  int b = 2;\n+  int a = 10;\n+  int b = 20;"},
            ],
        }

        regions = MODULE.prioritized_changed_regions(author, 1)

        self.assertEqual([region["file"] for region in regions], ["High.java"])


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
            self.assertEqual(index_inputs["quality_summary_marker"], MODULE.QUALITY_SUMMARY_MARKER)
            self.assertTrue((out / "code-contribution-report.md").exists())
            self.assertEqual((out / "code-contribution-report.md").read_text(encoding="utf-8").strip(), MODULE.REPORT_MARKER)

            ranking_by_author = {row["author"]: row for row in summary["ranking"]}
            for task in index_inputs["tasks"]:
                detail_path = pathlib.Path(task["detail_json"])
                report_path = pathlib.Path(task["report_md"])
                quality_path = pathlib.Path(task["quality_summary_json"])
                detail = json.loads(detail_path.read_text(encoding="utf-8"))
                ranking = ranking_by_author[task["author"]]
                expected_relative_path = f"reports/{task['author_key']}/person-report.md"
                expected_markdown_link = f"[person-report.md]({expected_relative_path})"

                self.assertTrue(detail_path.exists())
                self.assertTrue(report_path.exists())
                self.assertTrue(quality_path.exists())
                self.assertEqual(report_path.read_text(encoding="utf-8").strip(), MODULE.AUTHOR_REPORT_MARKER)
                self.assertEqual(quality_path.read_text(encoding="utf-8").strip(), MODULE.QUALITY_SUMMARY_MARKER)
                self.assertEqual(detail["author"], task["author"])
                self.assertEqual(detail["rank"], task["rank"])
                self.assertEqual(detail["summary"]["base_workload_score"], detail["summary"]["workload_score"])
                self.assertEqual(detail["summary"]["quality_adjustment_percent"], 0)
                self.assertEqual(detail["output"]["person_report_md"], task["report_md"])
                self.assertEqual(detail["output"]["quality_summary_json"], task["quality_summary_json"])
                self.assertEqual(detail["output"]["report_marker"], task["report_marker"])
                self.assertEqual(detail["output"]["quality_summary_marker"], task["quality_summary_marker"])
                self.assertEqual(task["report_relative_path"], expected_relative_path)
                self.assertEqual(task["report_markdown_link"], expected_markdown_link)
                self.assertEqual(task["quality_summary_marker"], MODULE.QUALITY_SUMMARY_MARKER)
                self.assertEqual(detail["execution_worklist"], task["execution_worklist"])
                self.assertEqual(
                    [item["action"] for item in task["execution_worklist"]],
                    [
                        "read_detail_json",
                        "read_person_report_template",
                        "inspect_changed_regions",
                        "collect_call_evidence",
                        "draft_quality_summary",
                        "write_quality_summary",
                        "draft_person_report",
                        "write_person_report",
                        "verify_outputs",
                        "final_response",
                    ],
                )
                self.assertEqual([item["step"] for item in task["execution_worklist"]], list(range(1, 11)))
                self.assertEqual(task["execution_worklist"][5]["target_path"], task["quality_summary_json"])
                self.assertEqual(task["execution_worklist"][5]["marker"], MODULE.QUALITY_SUMMARY_MARKER)
                self.assertEqual(task["execution_worklist"][7]["target_path"], task["report_md"])
                self.assertEqual(task["execution_worklist"][7]["marker"], MODULE.AUTHOR_REPORT_MARKER)
                self.assertEqual(
                    task["execution_worklist"][8]["required_paths"],
                    [task["report_md"], task["quality_summary_json"]],
                )
                self.assertFalse(pathlib.Path(task["report_relative_path"]).is_absolute())
                self.assertEqual(detail["output"]["person_report_relative_path"], expected_relative_path)
                self.assertEqual(detail["output"]["person_report_markdown_link"], expected_markdown_link)
                self.assertEqual(ranking["detail_json"], task["detail_json"])
                self.assertEqual(ranking["person_report_md"], task["report_md"])
                self.assertEqual(ranking["quality_summary_json"], task["quality_summary_json"])
                self.assertEqual(ranking["base_workload_score"], ranking["workload_score"])
                self.assertEqual(ranking["quality_adjustment_percent"], 0)
                self.assertEqual(ranking["person_report_marker"], task["report_marker"])
                self.assertEqual(ranking["person_report_relative_path"], task["report_relative_path"])
                self.assertEqual(ranking["person_report_markdown_link"], task["report_markdown_link"])
                self.assertIn("top_files", detail)
                excluded_suffixes = {".md", ".markdown", ".mdown", ".mkd", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".pdf", ".txt"}
                self.assertTrue(excluded_suffixes.isdisjoint(detail["extensions"]))
                self.assertTrue(all(pathlib.Path(path).suffix.lower() not in excluded_suffixes for path in detail["files"]))
                self.assertTrue(all(pathlib.Path(row["path"]).suffix.lower() not in excluded_suffixes for row in detail["top_files"]))


class PromptContractTest(unittest.TestCase):
    def test_mcp_tool_names_match_agentbridge_runtime(self):
        skill_dir = SCRIPT_PATH.parents[1]
        workflow_file = "workflows/mcp-tool-contract.md"
        required_terms = [
            "AgentBridge MCP 工具命名规范",
            "AgentBridge",
            "当前可用读取能力",
            "当前可用写入能力",
            "当前可用写入能力",
            "当前可用搜索能力",
            "当前可用搜索能力",
            "当前可用项目文件列表能力",
        ]

        workflow_content = (skill_dir / workflow_file).read_text(encoding="utf-8")
        for term in required_terms:
            self.assertIn(term, workflow_content, workflow_file)
        self.assertNotIn("intellij-index", workflow_content, workflow_file)
        self.assertNotIn("intellij-idea", workflow_content, workflow_file)
        self.assertNotIn("mcp__intellij", workflow_content, workflow_file)

        prompt_required_terms = [
            "执行前必须先读取以下 workflow",
            "workflows/mcp-tool-contract.md",
            "以 workflow 规则为准",
        ]
        forbidden_prompt_terms = [
            "AgentBridge MCP 工具命名规范",
            "intellij-idea",
            "intellij-index",
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
        self.assertNotIn("当前可用读取能力", skill_md)
        self.assertNotIn("当前可用搜索能力", skill_md)

    def test_quality_rules_reward_public_reused_code_with_risk_balance(self):
        skill_dir = SCRIPT_PATH.parents[1]
        required_terms = [
            "公共代码",
            "工具类代码",
            "调用链",
            "调用点",
            '"dimension": "maintainability"',
            '"dimension": "risk_control"',
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

    def test_low_quality_snippets_must_deduct_quality_score(self):
        skill_dir = SCRIPT_PATH.parents[1]
        files = [
            "workflows/quality-scoring.md",
            "prompts/run-author-report.md",
        ]
        required_terms = [
            "只要写入 `code_snippets`",
            "脚本按 `low_quality_code_snippet` 规则补充一个负向 finding",
            "统一计分结果必须小于 0",
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
            "子 agent 不得写入 `quality_adjustment_percent`",
            "子 agent 不得写入 `components[].score`",
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

    def test_quality_summary_uses_its_own_marker(self):
        skill_dir = SCRIPT_PATH.parents[1]
        required_terms = [
            "quality_summary_marker",
            "只替换 `detail.output.quality_summary_marker`",
            "不得使用 `detail.output.report_marker` 写 `quality_summary_json`",
            "quality-summary.json 专用 marker",
        ]

        for relative_path in [
            "prompts/run-author-report.md",
            "workflows/subagent-contract.md",
            "workflows/report-writing.md",
        ]:
            content = (skill_dir / relative_path).read_text(encoding="utf-8")
            for term in required_terms:
                self.assertIn(term, content, relative_path)

    def test_agentbridge_explore_is_allowed_as_bounded_context_probe(self):
        skill_dir = SCRIPT_PATH.parents[1]
        required_terms = [
            "优先使用 AgentBridge `explore` 做上下文探索",
            "当前作者 session 只消费 `explore` 返回的短证据摘要",
            "`explore` 不得返回完整文件、大段源码或未压缩搜索结果",
            "不得把完整文件中不属于 `detail.changed_regions` 的代码归因给该人员",
        ]

        for relative_path in [
            "prompts/run-author-report.md",
            "workflows/mcp-tool-contract.md",
            "workflows/quality-scoring.md",
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
            "先写 `quality-summary.json`，再写 `person-report.md`",
            "只有确认 `quality-summary.json` 和 `person-report.md` 都写入成功后，最终响应只能是 `完成` 或 `无法完成`",
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
            "无法完成 step=<step> action=<action> path=<path> reason=<reason>",
            "write_person_report",
            "write_quality_summary",
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

    def test_quality_scoring_uses_full_range_not_mechanical_one_point_scores(self):
        skill_dir = SCRIPT_PATH.parents[1]
        files = [
            "workflows/quality-scoring.md",
            "prompts/run-author-report.md",
        ]
        required_terms = [
            "统一分值表",
            "negative",
            "-2",
            "-5",
            "-8",
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
