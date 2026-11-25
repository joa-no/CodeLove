#!/usr/bin/env python3
"""Tests of the SDK-free APK repackaging prototype."""
import io
import tempfile
import unittest
from pathlib import Path
import zipfile

from offline_apk_prototype import inject_game


def make_love(main=b"print('new game')", *, name="main.lua"):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as result:
        result.writestr(name, main)
        result.writestr("scripts/helper.lua", "return 123")
    return buffer.getvalue()


def make_template(path, *, game_entry=True, loose_main=False):
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("AndroidManifest.xml", b"\x03\x00APK-XML")
        archive.writestr("classes.dex", b"dex\n035\x00")
        archive.writestr(
            "lib/arm64-v8a/liblove.so", b"NOT-A-REAL-SO",
            compress_type=zipfile.ZIP_STORED)
        archive.writestr("META-INF/MANIFEST.MF", "old JAR signature manifest")
        archive.writestr("META-INF/OLD.SF", "old JAR signature")
        archive.writestr("META-INF/OLD.RSA", "old signature data")
        archive.writestr("META-INF/androidx.core_core.version", "1.0")
        if game_entry:
            archive.writestr("assets/game.love", make_love(b"OLD TEMPLATE GAME"))
        if loose_main:
            archive.writestr("assets/main.lua", b"OLD UNPACKED GAME")


class OfflineApkTest(unittest.TestCase):
    def test_replaces_game_without_touching_runtime_or_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            template = root / "template.apk"
            game = root / "game.love"
            output = root / "unsigned.apk"
            make_template(template)
            game.write_bytes(make_love())
            before = template.read_bytes()
            count, size = inject_game(template, game, output)
            self.assertEqual(count, 2)
            self.assertGreater(size, 0)
            self.assertEqual(template.read_bytes(), before)
            with zipfile.ZipFile(output) as modified:
                self.assertIsNone(modified.testzip())
                names = modified.namelist()
                self.assertEqual(names.count("assets/game.love"), 1)
                self.assertEqual(modified.read("assets/game.love"), game.read_bytes())
                self.assertEqual(modified.read("AndroidManifest.xml"), b"\x03\x00APK-XML")
                self.assertEqual(modified.read("lib/arm64-v8a/liblove.so"),
                                 b"NOT-A-REAL-SO")
                self.assertIn("META-INF/androidx.core_core.version", names)
                self.assertNotIn("META-INF/OLD.SF", names)
                self.assertNotIn("META-INF/OLD.RSA", names)
                self.assertNotIn("META-INF/MANIFEST.MF", names)

    def test_rejects_template_without_game_slot(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            make_template(root / "template.apk", game_entry=False)
            (root / "game.love").write_bytes(make_love())
            with self.assertRaises(ValueError):
                inject_game(root / "template.apk", root / "game.love",
                            root / "output.apk")
            self.assertFalse((root / "output.apk").exists())

    def test_rejects_main_lua_outside_love_slot(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            make_template(root / "template.apk", loose_main=True)
            (root / "game.love").write_bytes(make_love())
            with self.assertRaises(ValueError):
                inject_game(root / "template.apk", root / "game.love",
                            root / "output.apk")
            self.assertFalse((root / "output.apk").exists())

    def test_rejects_bad_game_without_overwriting_template(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            make_template(root / "template.apk")
            (root / "game.love").write_bytes(make_love(name="../main.lua"))
            with self.assertRaises(ValueError):
                inject_game(root / "template.apk", root / "game.love",
                            root / "output.apk")
            self.assertFalse((root / "output.apk").exists())

    def test_rejects_missing_main(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            make_template(root / "template.apk")
            (root / "game.love").write_bytes(make_love(name="scripts/start.lua"))
            with self.assertRaises(ValueError):
                inject_game(root / "template.apk", root / "game.love",
                            root / "output.apk")

    def test_refuses_overwrite_existing_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            make_template(root / "template.apk")
            (root / "game.love").write_bytes(make_love())
            output = root / "output.apk"
            output.write_bytes(b"KEEP")
            with self.assertRaises(ValueError):
                inject_game(root / "template.apk", root / "game.love", output)
            self.assertEqual(output.read_bytes(), b"KEEP")


if __name__ == "__main__":
    unittest.main()
