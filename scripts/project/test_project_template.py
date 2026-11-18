import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import yaml

from project import inspect_repo


CLI = Path(__file__).with_name("project.py")


class ProjectTemplateTest(unittest.TestCase):
    def test_new_template_contains_order_api_and_three_data_services(self):
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory) / "my-orders"
            for action in ("new", "validate"):
                subprocess.run([sys.executable, str(CLI), action, str(project)], check=True, timeout=30)
            manifest = yaml.safe_load((project / "cloide.yaml").read_text(encoding="utf-8"))
            self.assertEqual("my-orders", manifest["project"])
            self.assertEqual(["orders-db", "orders-cache", "orders-events", "orders-api"], manifest["release"]["order"])
            self.assertEqual({"postgresql", "redis", "rabbitmq", "java-jar"},
                             {service["kind"] for service in manifest["services"].values()})
            self.assertEqual({"api", "README.md", "LICENSE", ".gitignore", "cloide.yaml",
                              "verify_runtime.py", "verify_java_release.py"},
                             {entry.name for entry in project.iterdir()})

    def test_import_detects_preexisting_client_without_adding_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            descriptor = root / "package.json"
            descriptor.write_text(json.dumps({"dependencies": {"react": "19"},
                                               "scripts": {"build": "vite build"}}), encoding="utf-8")
            draft = inspect_repo(root)
            self.assertEqual(["static-tar"], [entry["kind"] for entry in draft["services"].values()])
            self.assertEqual([descriptor], list(root.iterdir()))


if __name__ == "__main__":
    unittest.main()
