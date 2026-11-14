#!/usr/bin/env python3
"""Tests for the portable CodeLove APK build-request format."""
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
import zipfile

from game_build_request import FORMAT, prepare_request


def build_request(path: Path, *, game: bytes = None, icon: bytes = None,
                  meta_changes=None, extra=None):
    if game is None:
        with tempfile.TemporaryFile() as temp:
            with zipfile.ZipFile(temp, "w", zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("main.lua", b"function love.draw() end")
                archive.writestr("scripts/data.txt", b"asset included")
            temp.seek(0)
            game = temp.read()
    metadata = {
        "format": FORMAT,
        "name": "Game Sample",
        "package": "com.example.gamesample",
        "version_name": "1.2.0",
        "version_code": 12,
        "game_love": {"size": len(game), "sha256": hashlib.sha256(game).hexdigest()},
    }
    if icon is not None:
        metadata["icon_png"] = {
            "size": len(icon), "sha256": hashlib.sha256(icon).hexdigest()
        }
    if meta_changes:
        metadata.update(meta_changes)
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as request:
        request.writestr("request.json", json.dumps(metadata))
        request.writestr("game.love", game)
        if icon is not None:
            request.writestr("icon.png", icon)
        if extra is not None:
            request.writestr(extra, b"surprise")
    return metadata


class RequestTest(unittest.TestCase):
    def test_stages_game_and_verified_metadata(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source = root / "request.zip"
            metadata = build_request(source)
            request, count, size = prepare_request(
                source, root / "assets", root / "resources")
            self.assertEqual(request, metadata)
            self.assertEqual(count, 2)
            self.assertEqual((root / "assets/scripts/data.txt").read_bytes(),
                             b"asset included")
            self.assertGreater(size, 0)

    def test_stages_icon(self):
        from PIL import Image
        import io
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            buffer = io.BytesIO()
            Image.new("RGBA", (512, 512), (160, 90, 240, 255)).save(
                buffer, "PNG")
            build_request(root / "request.zip", icon=buffer.getvalue())
            metadata, count, _ = prepare_request(
                root / "request.zip", root / "assets", root / "resources")
            self.assertEqual(count, 2)
            self.assertIn("icon_png", metadata)
            self.assertTrue((root / "resources/drawable-xxxhdpi/love.png").is_file())

    def test_rejects_extra_members_before_writing(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            build_request(root / "request.zip", extra="../outside")
            with self.assertRaises(ValueError):
                prepare_request(root / "request.zip", root / "assets", root / "res")
            self.assertFalse((root / "assets").exists())

    def test_rejects_hash_mismatch(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            build_request(root / "request.zip", meta_changes={
                "game_love": {"sha256": "0" * 64, "size": 295}
            })
            with self.assertRaises(ValueError):
                prepare_request(root / "request.zip", root / "assets", root / "res")

    def test_rejects_unsupported_schema(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            build_request(root / "request.zip", meta_changes={"format": "other"})
            with self.assertRaises(ValueError):
                prepare_request(root / "request.zip", root / "assets", root / "res")

    def test_rejects_numeric_version_as_boolean(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            build_request(root / "request.zip", meta_changes={"version_code": True})
            with self.assertRaises(ValueError):
                prepare_request(root / "request.zip", root / "assets", root / "res")

    def test_rejects_missing_main_lua(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            import io
            game = io.BytesIO()
            with zipfile.ZipFile(game, "w") as archive:
                archive.writestr("scripts/start.lua", "return true")
            build_request(root / "request.zip", game=game.getvalue())
            with self.assertRaises(ValueError):
                prepare_request(root / "request.zip", root / "assets", root / "res")

    def test_does_not_overwrite_existing_assets(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            build_request(root / "request.zip")
            assets = root / "assets"
            assets.mkdir()
            (assets / "keep.txt").write_text("KEEP")
            with self.assertRaises(ValueError):
                prepare_request(root / "request.zip", assets, root / "res")
            self.assertEqual((assets / "keep.txt").read_text(), "KEEP")


if __name__ == "__main__":
    unittest.main()
