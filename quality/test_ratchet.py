import unittest
from ratchet import compare, fingerprint


def snapshot(findings=(), suppressions=()):
    return {"schemaVersion": 1, "modules": ["app"], "findings": list(findings), "suppressions": list(suppressions)}


def finding(path, shape="same", rule="LongMethod"):
    return {"module": "app", "rule": rule, "path": path, "fingerprint": shape}


class RatchetTest(unittest.TestCase):
    def test_identical_is_green(self):
        self.assertTrue(compare(snapshot([finding("a.kt")]), snapshot([finding("a.kt")]))["ok"])

    def test_resolved_is_green(self):
        self.assertTrue(compare(snapshot([finding("a.kt")]), snapshot())["ok"])

    def test_new_finding_is_red(self):
        self.assertFalse(compare(snapshot(), snapshot([finding("a.kt")]))["ok"])

    def test_equal_total_finding_swap_is_red(self):
        self.assertFalse(compare(snapshot([finding("a.kt", "old")]), snapshot([finding("b.kt", "new")]))["ok"])

    def test_file_rename_is_green(self):
        self.assertTrue(compare(snapshot([finding("a.kt")]), snapshot([finding("b.kt")]))["ok"])

    def test_ambiguous_rename_is_red(self):
        before = [finding("a.kt"), finding("b.kt")]
        after = [finding("c.kt"), finding("d.kt")]
        self.assertFalse(compare(snapshot(before), snapshot(after))["ok"])

    def test_new_or_replacement_suppression_is_red(self):
        old = {"path": "a.kt", "text": '@Suppress("unused")'}
        new = {"path": "b.kt", "text": '@Suppress("unused")'}
        self.assertFalse(compare(snapshot(suppressions=[old]), snapshot(suppressions=[new]))["ok"])

    def test_suppression_can_shrink(self):
        old = {"path": "a.kt", "text": '@Suppress("unused")'}
        self.assertTrue(compare(snapshot(suppressions=[old]), snapshot())["ok"])

    def test_symbol_rename_keeps_shape(self):
        self.assertEqual(fingerprint("LongMethod", "Function 'old' is long", "fun old(value: Int)"),
                         fingerprint("LongMethod", "Function 'new' is long", "fun new(other: Int)"))


if __name__ == "__main__":
    unittest.main()
