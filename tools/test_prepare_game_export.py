#!/usr/bin/env python3
"""Unit tests for safe staging of .love packages."""
import stat
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path

from prepare_game_export import prepare, prepare_love


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


if __name__ == "__main__":
    unittest.main()
