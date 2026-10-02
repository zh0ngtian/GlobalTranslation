import hashlib
import http.client
import importlib.util
import json
from pathlib import Path
import tempfile
import threading
import unittest
import zipfile


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "apk-server.py"
SPEC = importlib.util.spec_from_file_location("apk_server", SCRIPT)
apk_server = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(apk_server)


class FixedChannelTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        apk_server.STATE = self.root / "state"
        apk_server.FILES = apk_server.STATE / "files"
        apk_server.CHANNELS = apk_server.STATE / "channels"
        apk_server.FILES.mkdir(parents=True)
        apk_server.CHANNELS.mkdir(parents=True)
        (apk_server.STATE / "config.json").write_text(
            json.dumps({"base_url": "http://192.168.123.79:8765"})
        )
        self.httpd = apk_server.ThreadingHTTPServer(
            ("127.0.0.1", 0), apk_server.Handler
        )
        self.httpd.base_url = "http://192.168.123.79:8765"
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(timeout=2)
        self.temporary.cleanup()

    def make_apk(self, name, payload):
        path = self.root / name
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"manifest")
            archive.writestr("assets/payload.txt", payload)
        return path

    def request(self, method, path):
        connection = http.client.HTTPConnection(
            "127.0.0.1", self.httpd.server_port, timeout=2
        )
        connection.request(method, path)
        response = connection.getresponse()
        body = response.read()
        headers = dict(response.getheaders())
        connection.close()
        return response.status, headers, body

    def test_publish_channel_exposes_fixed_manifest_and_apk(self):
        source = self.make_apk("CodexMobile-v0.2.31.apk", b"first")
        expected_sha256 = hashlib.sha256(source.read_bytes()).hexdigest()

        published = apk_server.publish_channel(
            "codex-mobile", source, "0.2.31", "固定局域网更新渠道"
        )

        self.assertEqual(published["version"], "0.2.31")
        status, headers, body = self.request(
            "GET", "/channels/codex-mobile/latest.json"
        )
        self.assertEqual(status, 200)
        self.assertEqual(headers["Content-Type"], "application/json; charset=utf-8")
        self.assertEqual(headers["Access-Control-Allow-Origin"], "*")
        manifest = json.loads(body)
        self.assertEqual(
            manifest,
            {
                "version": "0.2.31",
                "tag": "v0.2.31",
                "notes": "固定局域网更新渠道",
                "pageUrl": "http://192.168.123.79:8765/channels/codex-mobile/latest.json",
                "downloadUrl": "http://192.168.123.79:8765/channels/codex-mobile/latest.apk",
                "sha256": expected_sha256,
                "size": source.stat().st_size,
                "publishedAt": manifest["publishedAt"],
                "releases": [
                    {
                        "version": "0.2.31",
                        "notes": "固定局域网更新渠道",
                        "publishedAt": manifest["publishedAt"],
                    }
                ],
            },
        )

        status, headers, body = self.request(
            "GET", "/channels/codex-mobile/latest.apk"
        )
        self.assertEqual(status, 200)
        self.assertEqual(
            headers["Content-Type"], "application/vnd.android.package-archive"
        )
        self.assertEqual(body, source.read_bytes())

        status, headers, body = self.request(
            "HEAD", "/channels/codex-mobile/latest.apk"
        )
        self.assertEqual(status, 200)
        self.assertEqual(int(headers["Content-Length"]), source.stat().st_size)
        self.assertEqual(body, b"")

    def test_republish_replaces_fixed_channel_without_temporary_expiry(self):
        first = self.make_apk("CodexMobile-v0.2.31.apk", b"first")
        second = self.make_apk("CodexMobile-v0.2.32.apk", b"second")
        apk_server.publish_channel("codex-mobile", first, "0.2.31", "first")
        apk_server.publish_channel("codex-mobile", second, "0.2.32", "second")

        status, _, manifest_body = self.request(
            "GET", "/channels/codex-mobile/latest.json"
        )
        self.assertEqual(status, 200)
        manifest = json.loads(manifest_body)
        self.assertEqual(manifest["version"], "0.2.32")
        self.assertEqual(
            [(item["version"], item["notes"]) for item in manifest["releases"]],
            [("0.2.31", "first"), ("0.2.32", "second")],
        )
        status, _, apk_body = self.request(
            "GET", "/channels/codex-mobile/latest.apk"
        )
        self.assertEqual(status, 200)
        self.assertEqual(apk_body, second.read_bytes())

        apk_server.records()
        self.assertTrue((apk_server.CHANNELS / "codex-mobile.json").is_file())
        self.assertTrue((apk_server.CHANNELS / "codex-mobile.apk").is_file())

    def test_republish_same_version_updates_history_without_duplicate(self):
        first = self.make_apk("CodexMobile-v0.2.31.apk", b"first")
        replacement = self.make_apk("CodexMobile-v0.2.31-hotfix.apk", b"replacement")
        apk_server.publish_channel("codex-mobile", first, "0.2.31", "first")
        manifest = apk_server.publish_channel(
            "codex-mobile", replacement, "0.2.31", "replacement"
        )

        self.assertEqual(
            [(item["version"], item["notes"]) for item in manifest["releases"]],
            [("0.2.31", "replacement")],
        )

    def test_publish_migrates_legacy_manifest_into_release_history(self):
        previous = self.make_apk("CodexMobile-v0.2.30.apk", b"previous")
        current = self.make_apk("CodexMobile-v0.2.31.apk", b"current")
        legacy = apk_server.publish_channel(
            "codex-mobile", previous, "0.2.30", "legacy"
        )
        legacy.pop("releases")
        (apk_server.CHANNELS / "codex-mobile.json").write_text(
            json.dumps(legacy, ensure_ascii=False)
        )

        manifest = apk_server.publish_channel(
            "codex-mobile", current, "0.2.31", "current"
        )

        self.assertEqual(
            [(item["version"], item["notes"]) for item in manifest["releases"]],
            [("0.2.30", "legacy"), ("0.2.31", "current")],
        )


if __name__ == "__main__":
    unittest.main()
