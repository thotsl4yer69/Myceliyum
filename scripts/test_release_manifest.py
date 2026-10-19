import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


def load(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


release = load("release_manifest")
progress = load("check_release_progression")


class ReleaseManifestTest(unittest.TestCase):
    def test_committed_version_is_required(self):
        self.assertEqual(release.build_version('versionCode = 7201\nversionName = "7.2"'), (7201, "7.2"))
        with self.assertRaises(ValueError):
            release.build_version('versionCode = System.getenv("BUILD_NUMBER")\nversionName = "7.2"')

    def test_apk_version_mismatch_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "app-release.apk"
            apk.write_bytes(b"fixture")
            with patch.object(subprocess, "check_output", return_value="package: name='other.app' versionCode='66' versionName='7.1'"):
                with self.assertRaises(ValueError):
                    release.create_manifest(apk, 'versionCode = 7201\nversionName = "7.2"', "a" * 40)

    def test_signature_verification_failure_is_not_published(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "app-release.apk"
            apk.write_bytes(b"fixture")
            with patch.object(subprocess, "check_output", return_value=f"package: name='{release.PACKAGE}' versionCode='7201' versionName='7.2'"), patch.object(subprocess, "run", side_effect=subprocess.CalledProcessError(1, "apksigner")):
                with self.assertRaises(subprocess.CalledProcessError):
                    release.create_manifest(apk, 'versionCode = 7201\nversionName = "7.2"', "a" * 40)

    def test_republished_version_requires_increment(self):
        previous = {"body": '<!-- MYCELIYUMS-RELEASE {"versionCode":7201} -->'}
        with self.assertRaises(ValueError):
            progress.check(previous, {"versionCode":7201})
        progress.check(previous, {"versionCode":7202})


if __name__ == "__main__":
    unittest.main()
