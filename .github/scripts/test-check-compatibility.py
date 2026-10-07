#!/usr/bin/env python3
"""Exercise the artifact/verdict gate, including a verifier CLI zero-exit failure."""
import io
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

GUARD = Path(__file__).with_name("check-compatibility.py")


class CompatibilityGateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.archive = self.root / "biome.zip"
        self.reports = self.root / "reports"
        self.target = "WS-262.10968.77"
        self.verdict = self.reports / self.target / "plugins/com.github.biomejs.intellijbiome/1.10.1/verification-verdict.txt"
        self.verdict.parent.mkdir(parents=True)
        self.verdict.write_text("Compatible. 77 usages of deprecated API. 2 usages of experimental API")
        self.write_plugin()

    def write_plugin(self, since="253", version="1.10.1", extra=False, until="262.*"):
        jar = io.BytesIO()
        upper_bound = f' until-build="{until}"' if until else ""
        with zipfile.ZipFile(jar, "w") as z:
            z.writestr("META-INF/plugin.xml", f'<idea-plugin><id>com.github.biomejs.intellijbiome</id><version>{version}</version><idea-version since-build="{since}"{upper_bound}/></idea-plugin>')
        with zipfile.ZipFile(self.archive, "w") as z:
            z.writestr("intellij-biome/lib/intellij-biome-1.10.1.jar", jar.getvalue())
            if extra:
                z.writestr("other/lib/other.jar", jar.getvalue())

    def run_guard(self, expected=None):
        result = subprocess.run([sys.executable, str(GUARD), "--archive", str(self.archive), "--reports", str(self.reports), "--target", self.target, "--version", "1.10.1"], capture_output=True, text=True)
        if expected is None:
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("WS-262.10968.77", result.stdout)
            self.assertIn("sha256", result.stdout)
        else:
            self.assertEqual(1, result.returncode, result.stderr)
            self.assertIn(expected, result.stderr)

    def test_compatible_exact_archive_and_target_pass(self):
        self.run_guard()

    def test_zero_exit_verifier_with_incompatibility_is_rejected(self):
        # The actual standalone verifier exits zero for this verdict.
        self.verdict.write_text("1 compatibility problem. 77 usages of deprecated API")
        self.run_guard("not compatible")

    def test_nonempty_problem_file_is_rejected_even_with_compatible_verdict(self):
        self.verdict.with_name("compatibility-problems.txt").write_text("NoSuchMethodError")
        self.run_guard("compatibility-problems.txt")

    def test_wrong_target_is_not_accepted(self):
        self.target = "WS-253.28294.332"
        self.run_guard("Missing exact verdict")

    def test_missing_verdict_is_rejected(self):
        self.verdict.unlink()
        self.run_guard("Missing exact verdict")

    def test_duplicate_verdict_is_rejected(self):
        p = self.verdict.parent.parent / "another-version/verification-verdict.txt"
        p.parent.mkdir()
        p.write_text("Compatible")
        self.run_guard("exactly one verdict")

    def test_newer_compilation_descriptor_is_rejected(self):
        self.write_plugin(since="262")
        self.run_guard("since-build")

    def test_wrong_version_is_rejected(self):
        self.write_plugin(version="1.10.2")
        self.run_guard("version")

    def test_verifier_target_excluded_by_descriptor_is_rejected(self):
        self.write_plugin(until="253.*")
        self.run_guard("descriptor excludes")

    def test_verified_stable_range_accepts_pinned_stable(self):
        self.write_plugin(until="262.*")
        self.run_guard()

    def test_unbounded_future_range_is_rejected(self):
        self.write_plugin(until=None)
        self.run_guard("until-build must match verified range")

    def test_unverified_future_major_is_rejected(self):
        self.write_plugin(until="263.*")
        self.run_guard("until-build must match verified range")

    def test_ambiguous_plugin_structure_is_rejected(self):
        self.write_plugin(extra=True)
        self.run_guard("exactly one plugin descriptor")

    def test_invalid_zip_is_rejected(self):
        self.archive.write_bytes(b"not a zip")
        self.run_guard("archive")


if __name__ == "__main__":
    unittest.main()
