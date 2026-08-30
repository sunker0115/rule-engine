"""数据库报告门禁的真实 CLI 回归测试，不连接 Docker 或数据库。"""

from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET


SCRIPT = Path(__file__).resolve().parents[1] / "verify_database_tests.py"
REPORTS = (
    ("rule-app", "surefire", "com.sstlfsj.rule.MySqlDatabaseStartupTest"),
    ("rule-config-svc", "surefire", "com.sstlfsj.rule.config.integration.DecisionDefinitionRoundTripIT"),
    ("rule-eval-svc", "surefire", "com.sstlfsj.rule.eval.integration.EvalIntegrationTest"),
    ("rule-eval-svc", "surefire", "com.sstlfsj.rule.eval.integration.OutcomeIngestionIntegrationTest"),
    ("rule-job-svc", "surefire", "com.sstlfsj.rule.job.integration.ScheduledTaskAnnotationIntegrationTest"),
    ("rule-app", "failsafe", "com.sstlfsj.rule.example.scenario.OrderFraudScenario"),
    ("rule-app", "failsafe", "com.sstlfsj.rule.example.scenario.CreditEvaluationScenario"),
    ("rule-app", "failsafe", "com.sstlfsj.rule.example.scenario.SdkTradingScenario"),
)


class DatabaseReportTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.paths = []
        for module, runner, name in REPORTS:
            path = self.root / module / "target" / f"{runner}-reports" / f"TEST-{name}.xml"
            path.parent.mkdir(parents=True, exist_ok=True)
            suite = ET.Element("testsuite", name=name, tests="1", failures="0", errors="0", skipped="0")
            ET.SubElement(suite, "testcase", name="roundTrip", classname=name, time="0.1")
            ET.ElementTree(suite).write(path, encoding="utf-8", xml_declaration=True)
            self.paths.append(path)

    def run_check(self):
        return subprocess.run(
            [sys.executable, "-B", str(SCRIPT), "--root", str(self.root)],
            cwd=self.root, capture_output=True, text=True, check=False,
        )

    def rewrite_first(self, edit):
        tree = ET.parse(self.paths[0])
        edit(tree.getroot())
        tree.write(self.paths[0], encoding="utf-8", xml_declaration=True)

    def assert_rejected(self, name=REPORTS[0][2]):
        result = self.run_check()
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn(name, result.stderr)

    def test_accepts_complete_reports(self):
        result = self.run_check()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("8", result.stdout)

    def test_rejects_each_missing_report(self):
        for path, (_, _, name) in zip(self.paths, REPORTS):
            with self.subTest(name=name):
                original = path.read_bytes()
                path.unlink()
                self.assert_rejected(name)
                path.write_bytes(original)

    def test_reports_all_missing_suites(self):
        for path in self.paths:
            path.unlink()
        result = self.run_check()
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        for _, _, name in REPORTS:
            self.assertIn(name, result.stderr)

    def test_rejects_zero_tests(self):
        self.rewrite_first(lambda suite: suite.set("tests", "0"))
        self.assert_rejected()

    def test_rejects_failed_errored_or_skipped_counts(self):
        original = self.paths[0].read_bytes()
        for attribute in ("failures", "errors", "skipped"):
            with self.subTest(attribute=attribute):
                self.paths[0].write_bytes(original)
                self.rewrite_first(lambda suite: suite.set(attribute, "1"))
                self.assert_rejected()

    def test_rejects_missing_count_attributes(self):
        original = self.paths[0].read_bytes()
        for attribute in ("tests", "failures", "errors", "skipped"):
            with self.subTest(attribute=attribute):
                self.paths[0].write_bytes(original)
                self.rewrite_first(lambda suite: suite.attrib.pop(attribute))
                self.assert_rejected()

    def test_rejects_invalid_counts(self):
        original = self.paths[0].read_bytes()
        for attribute in ("tests", "failures", "errors", "skipped"):
            for value in ("-1", "1.5", "invalid", ""):
                with self.subTest(attribute=attribute, value=value):
                    self.paths[0].write_bytes(original)
                    self.rewrite_first(lambda suite: suite.set(attribute, value))
                    self.assert_rejected()

    def test_rejects_malformed_xml(self):
        self.paths[0].write_text("<testsuite", encoding="utf-8")
        self.assert_rejected()

    def test_rejects_wrong_suite_name(self):
        self.rewrite_first(lambda suite: suite.set("name", "unrelated.Suite"))
        self.assert_rejected()

    def test_rejects_wrong_root_element(self):
        self.rewrite_first(lambda suite: setattr(suite, "tag", "testsuites"))
        self.assert_rejected()

    def test_rejects_missing_testcase_content(self):
        self.rewrite_first(lambda suite: suite.remove(suite.find("testcase")))
        self.assert_rejected()

    def test_rejects_failure_elements_hidden_by_summary(self):
        original = self.paths[0].read_bytes()
        for tag in ("failure", "error", "skipped"):
            with self.subTest(tag=tag):
                self.paths[0].write_bytes(original)
                self.rewrite_first(lambda suite: ET.SubElement(suite.find("testcase"), tag))
                self.assert_rejected()


if __name__ == "__main__":
    unittest.main()
