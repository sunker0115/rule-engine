"""检查必要数据库测试确实执行成功；仅使用标准库并只读测试报告。"""

import argparse
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


REQUIRED_REPORTS = (
    ("rule-app", "surefire", "com.sstlfsj.rule.MySqlDatabaseStartupTest"),
    ("rule-config-svc", "surefire", "com.sstlfsj.rule.config.integration.DecisionDefinitionRoundTripIT"),
    ("rule-eval-svc", "surefire", "com.sstlfsj.rule.eval.integration.EvalIntegrationTest"),
    ("rule-eval-svc", "surefire", "com.sstlfsj.rule.eval.integration.OutcomeIngestionIntegrationTest"),
    ("rule-job-svc", "surefire", "com.sstlfsj.rule.job.integration.ScheduledTaskAnnotationIntegrationTest"),
    ("rule-app", "failsafe", "com.sstlfsj.rule.example.scenario.OrderFraudScenario"),
    ("rule-app", "failsafe", "com.sstlfsj.rule.example.scenario.CreditEvaluationScenario"),
    ("rule-app", "failsafe", "com.sstlfsj.rule.example.scenario.SdkTradingScenario"),
)


def check_reports(root: Path) -> list[str]:
    """返回所有缺失或未通过的必要报告诊断；空列表表示通过。"""
    problems = []
    for module, runner, name in REQUIRED_REPORTS:
        path = root / module / "target" / f"{runner}-reports" / f"TEST-{name}.xml"
        try:
            suite = ET.parse(path).getroot()
            if suite.tag != "testsuite" or suite.get("name") != name:
                raise ValueError("testsuite 根元素或名称不匹配")
            counts = {key: int(suite.attrib[key]) for key in ("tests", "failures", "errors", "skipped")}
            if any(value < 0 for value in counts.values()):
                raise ValueError(f"计数不能为负数：{counts}")
            if counts["tests"] == 0 or any(counts[key] for key in ("failures", "errors", "skipped")):
                raise ValueError(f"必须有测试执行且无失败、错误或跳过：{counts}")
            cases = suite.findall("testcase")
            if len(cases) != counts["tests"]:
                raise ValueError("testcase 数量与 tests 计数不一致")
            if any(case.find(tag) is not None for case in cases for tag in ("failure", "error", "skipped")):
                raise ValueError("testcase 包含失败、错误或跳过记录")
        except (OSError, ET.ParseError, KeyError, ValueError) as error:
            problems.append(f"{name}: 报告缺失或无效：{error}")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1], help="仓库根目录")
    problems = check_reports(parser.parse_args().root)
    if problems:
        for problem in problems:
            print(problem, file=sys.stderr)
        return 1
    print(f"数据库验证通过：{len(REQUIRED_REPORTS)} 类测试报告齐全，均已执行且无失败、错误或跳过。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
