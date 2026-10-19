"""Boundary tests: no Earth Engine account or external network required."""
import importlib.util
import math
from pathlib import Path
import sys
import types
import unittest
from unittest.mock import patch

sys.modules.setdefault("ee", types.ModuleType("ee"))
spec = importlib.util.spec_from_file_location("environment_service", Path(__file__).with_name("main.py"))
service = importlib.util.module_from_spec(spec)
spec.loader.exec_module(service)


class EnvironmentBoundaryTest(unittest.TestCase):
    def setUp(self):
        service.app.config["TESTING"] = True
        self.client = service.app.test_client()
        self.auth = patch.object(service, "BACKEND_TOKEN", "test-token")
        self.auth.start()
        self.addCleanup(self.auth.stop)

    def post(self, body):
        return self.client.post("/env-grid", json=body, headers={"X-Api-Token": "test-token"})

    def test_missing_configuration_fails_closed_and_health_still_works(self):
        with patch.object(service, "BACKEND_TOKEN", ""), patch.object(service, "ensure_ee") as provider:
            self.assertEqual(self.post({"points": [[0, 0]]}).status_code, 503)
            self.assertEqual(self.client.get("/health").status_code, 200)
            provider.assert_not_called()

    def test_wrong_token_is_rejected_before_provider_access(self):
        with patch.object(service, "ensure_ee") as provider:
            self.assertEqual(self.client.post("/env-grid", json={"points": [[0, 0]]}).status_code, 401)
            provider.assert_not_called()

    def test_malformed_bodies_do_not_initialize_provider(self):
        invalid = [None, [], "text", {}, {"points": []}, {"points": "text"}, {"points": [[0]]}, {"points": [[0, 0, 0]]}, {"points": [[True, 0]]}, {"points": [["0", 0]]}]
        with patch.object(service, "ensure_ee") as provider:
            for body in invalid:
                with self.subTest(body=body):
                    self.assertEqual(self.post(body).status_code, 400)
            provider.assert_not_called()

    def test_non_finite_and_out_of_range_coordinates_are_rejected(self):
        with patch.object(service, "ensure_ee") as provider:
            for point in [[math.nan, 0], [0, math.inf], [91, 0], [0, -181]]:
                self.assertEqual(self.post({"points": [point]}).status_code, 400)
            provider.assert_not_called()

    def test_limits_and_valid_coordinates(self):
        with patch.object(service, "ensure_ee"), patch.object(service, "_compute_grid", return_value={"ok": True}) as compute:
            self.assertEqual(self.post({"points": [[-90, -180], [90, 180]]}).status_code, 200)
            self.assertEqual(compute.call_args.args[0], [[-90, -180], [90, 180]])
            self.assertEqual(self.post({"points": [[0, 0]] * 600}).status_code, 200)
            self.assertEqual(self.post({"points": [[0, 0]] * 601}).status_code, 400)

    def test_provider_exception_details_are_redacted(self):
        with patch.object(service, "ensure_ee", side_effect=RuntimeError("PRIVATE_DETAILS")):
            response = self.post({"points": [[0, 0]]})
            self.assertEqual(response.status_code, 502)
            self.assertNotIn("PRIVATE_DETAILS", response.get_data(as_text=True))


if __name__ == "__main__":
    unittest.main()
