"""Guard the personal fork's user-facing destinations against upstream link regressions."""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]


def source(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


class ForkLinkTests(unittest.TestCase):
    def test_primary_repository_and_updates_are_owner_controlled(self):
        updates = source(
            "app/src/main/java/com/kienhoang/dualsubreplay/data/AppUpdates.kt"
        )
        footer = source(
            "app/src/main/java/com/kienhoang/dualsubreplay/ui/DrawerFooter.kt"
        )
        self.assertIn(
            'UPDATE_REPOSITORY = "z2846852/dual-sub-replay"', updates
        )
        self.assertIn(
            'PROJECT_REPOSITORY_URL = "https://github.com/$UPDATE_REPOSITORY"',
            footer,
        )
        self.assertIn("uriHandler.openUri(PROJECT_REPOSITORY_URL)", footer)
        self.assertIn(
            'ORIGINAL_PROJECT_REPOSITORY_URL = "https://github.com/hoangkien1703/dual-sub-replay"',
            footer,
        )
        self.assertIn(
            "uriHandler.openUri(ORIGINAL_PROJECT_REPOSITORY_URL)", footer
        )
        self.assertIn('testTag("original_github_repository_link")', footer)

    def test_fork_issues_and_upstream_email_have_explicit_labels(self):
        problems = source(
            "app/src/main/java/com/kienhoang/dualsubreplay/data/ProblemLog.kt"
        )
        self.assertIn('https://github.com/$UPDATE_REPOSITORY/issues/new', problems)
        self.assertIn(
            'PROBLEM_REPORT_EMAIL = "dualsubreplay.help@gmail.com"', problems
        )
        for locale in ("values", "values-zh-rCN"):
            notices = source(
                f"app/src/main/res/{locale}/strings_notices.xml"
            )
            ai = source(f"app/src/main/res/{locale}/strings_ai.xml")
            navigation = source(
                f"app/src/main/res/{locale}/strings_navigation.xml"
            )
            updates = source(
                f"app/src/main/res/{locale}/strings_updates.xml"
            )
            self.assertIn('name="navigation_original_repository"', navigation)
            self.assertIn('name="report_problem_open"', notices)
            self.assertIn('name="report_problem_email"', notices)
            self.assertIn('name="updates_original_play_store_note"', updates)
            self.assertIn('name="ai_report_intro"', ai)
            if locale == "values":
                self.assertIn("original author", notices.lower())
                self.assertIn("original author", ai.lower())
            else:
                self.assertIn("原作者", notices)
                self.assertIn("原作者", ai)


if __name__ == "__main__":
    unittest.main()
