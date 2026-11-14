#!/usr/bin/env python3
"""Unit tests for safe staging of .love packages."""
import stat
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path

from prepare_game_export import prepare, prepare_love, prepare_icon


class ExportArchiveTest(unittest.TestCase):
    def archive(self, root, members):
        path = root / "sample.love"
        with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as writer:
            for name, value in members:
                writer.writestr(name, value)
        return path

    def test_nested_lua_and_binary_files(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            files = [("main.lua", b"print('love')"),
                     ("scripts/helper.lua", b"return 123"),
                     ("assets/data.bin", b"\x00\xff\x00")]
            source = self.archive(root, files)
            count, total = prepare_love(source, root / "assets")
            self.assertEqual(count, 3)
            self.assertEqual(total, sum(len(data) for _, data in files))
            self.assertEqual((root / "assets/assets/data.bin").read_bytes(), b"\x00\xff\x00")

    def test_rejects_unsafe_paths_without_writing(self):
        for bad in ("../evil", "a/../../evil", "/absolute", "C:/drive",
                    "folder\\evil", "folder//evil"):
            with self.subTest(bad=bad), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                source = self.archive(root, [("main.lua", b"ok"), (bad, b"bad")])
                with self.assertRaises(ValueError):
                    prepare_love(source, root / "assets")
                self.assertFalse((root / "assets").exists())

    def test_rejects_duplicate_entries(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                source = self.archive(root, [("main.lua", b"a"), ("main.lua", b"b")])
            with self.assertRaises(ValueError):
                prepare_love(source, root / "assets")

    def test_rejects_symlink(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / "sample.love"
            link = zipfile.ZipInfo("link")
            link.create_system = 3
            link.external_attr = (stat.S_IFLNK | 0o777) << 16
            with zipfile.ZipFile(source, "w") as writer:
                writer.writestr("main.lua", b"ok")
                writer.writestr(link, b"main.lua")
            with self.assertRaises(ValueError):
                prepare_love(source, root / "assets")

    def test_main_lua_is_required_at_root(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = self.archive(root, [("scripts/main.lua", b"ok")])
            with self.assertRaises(ValueError):
                prepare_love(source, root / "assets")

    def test_does_not_overwrite_existing_assets(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = self.archive(root, [("main.lua", b"ok")])
            target = root / "assets"
            target.mkdir()
            (target / "keep.txt").write_text("keep")
            with self.assertRaises(ValueError):
                prepare_love(source, target)
            self.assertEqual((target / "keep.txt").read_text(), "keep")

    def test_directory_input_still_works(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            game = root / "game"
            game.mkdir()
            (game / "main.lua").write_text("print('ok')")
            count, _ = prepare(game, root / "assets")
            self.assertEqual(count, 1)
            self.assertEqual((root / "assets/main.lua").read_text(), "print('ok')")


    def test_custom_icon_is_only_staged_in_embed_resources(self):
        from PIL import Image
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            original = root / "icon.png"
            Image.new("RGBA", (512, 512), (160, 90, 240, 255)).save(original)
            output = root / "app/src/embed/res"
            files = prepare_icon(original, output)
            self.assertEqual(set(files.keys()), {
                "mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"
            })
            for density, size in [
                ("mdpi", 48), ("hdpi", 72), ("xhdpi", 96),
                ("xxhdpi", 144), ("xxxhdpi", 192),
            ]:
                with Image.open(files[density]) as generated:
                    self.assertEqual(generated.size, (size, size))
                    self.assertEqual(generated.format, "PNG")
            self.assertFalse((root / "app/src/normal").exists())
            self.assertFalse((root / "app/src/main").exists())

    def test_rejects_invalid_or_tiny_icons(self):
        from PIL import Image
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            invalid = root / "icon.png"
            invalid.write_bytes(b"not a real image")
            with self.assertRaises(OSError):
                prepare_icon(invalid, root / "res")
            small = root / "small.png"
            Image.new("RGBA", (32, 32), "purple").save(small)
            with self.assertRaises(ValueError):
                prepare_icon(small, root / "res")

    def test_refuses_to_overwrite_an_existing_launcher_icon(self):
        from PIL import Image
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            icon = root / "icon.png"
            Image.new("RGBA", (512, 512), "purple").save(icon)
            existing = root / "res/drawable-mdpi/love.png"
            existing.parent.mkdir(parents=True)
            existing.write_bytes(b"KEEP")
            with self.assertRaises(ValueError):
                prepare_icon(icon, root / "res")
            self.assertEqual(existing.read_bytes(), b"KEEP")


if __name__ == "__main__":
    unittest.main()
