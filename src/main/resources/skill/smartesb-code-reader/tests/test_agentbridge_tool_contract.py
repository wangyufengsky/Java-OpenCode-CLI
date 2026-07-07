import unittest
from pathlib import Path


SKILL_DIR = Path(__file__).resolve().parents[1]


class AgentBridgeToolContractTest(unittest.TestCase):
    def test_module_reader_prompts_use_real_agentbridge_tool_names(self) -> None:
        files = [
            SKILL_DIR / "prompts" / "rerun-single-module.md",
            SKILL_DIR / "references" / "xml-workflow.md",
        ]
        combined = "\n".join(path.read_text(encoding="utf-8") for path in files)

        self.assertNotIn("AgentBridge_...", combined)
        self.assertNotIn("ide_find_file", combined)
        self.assertNotIn("ide_当前可用读取能力", combined)
        self.assertNotIn("project_path", combined)

        for tool_name in ("当前可用搜索能力", "当前可用项目文件列表能力", "当前可用搜索能力", "当前可用读取能力"):
            self.assertIn(tool_name, combined)


if __name__ == "__main__":
    unittest.main()
