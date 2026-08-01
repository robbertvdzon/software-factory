import unittest
from ratchet import SCHEMA_VERSION, compare, fingerprint


def snapshot(findings=(), suppressions=(), schema_version=SCHEMA_VERSION):
    return {"schemaVersion": schema_version, "modules": ["app"], "findings": list(findings),
            "suppressions": list(suppressions)}


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


class FingerprintNoiseTest(unittest.TestCase):
    def test_length_metric_change_keeps_fingerprint(self):
        line = "    fun render(model: ViewModel): String {"
        self.assertEqual(fingerprint("LongMethod", "The function render is too long (89). The maximum length is 60.", line),
                         fingerprint("LongMethod", "The function render is too long (94). The maximum length is 60.", line))

    def test_return_count_metric_change_keeps_fingerprint(self):
        line = "    fun resolve(key: String): Int {"
        self.assertEqual(fingerprint("ReturnCount", "Function resolve has 3 return statements which exceeds the limit of 2.", line),
                         fingerprint("ReturnCount", "Function resolve has 4 return statements which exceeds the limit of 2.", line))

    def test_complexity_metric_change_keeps_fingerprint(self):
        line = "    fun dispatch(command: Command) {"
        self.assertEqual(fingerprint("CyclomaticComplexMethod", "The function dispatch appears to be too complex (complexity: 23).", line),
                         fingerprint("CyclomaticComplexMethod", "The function dispatch appears to be too complex (complexity: 25).", line))

    def test_parameter_list_change_keeps_fingerprint(self):
        message = "The function build(a: Int, b: String) has too many parameters. The current maximum is 5."
        added = "The function build(a: Int, b: String, c: Long) has too many parameters. The current maximum is 5."
        renamed = "The function build(first: Int, second: String) has too many parameters. The current maximum is 5."
        removed = "The function build(a: Int) has too many parameters. The current maximum is 5."
        line = "fun build(a: Int, b: String) = Unit"
        base = fingerprint("LongParameterList", message, line)
        for variant in (added, renamed, removed):
            self.assertEqual(base, fingerprint("LongParameterList", variant, line))

    def test_nested_parentheses_in_parameter_list_collapse(self):
        line = "fun build(a: Int, b: (Int) -> Unit) = Unit"
        self.assertEqual(
            fingerprint("LongParameterList", "The function build(a: Int, b: (Int) -> Unit) has too many parameters.", line),
            fingerprint("LongParameterList", "The function build(a: Int) has too many parameters.", line))

    def test_two_functions_in_one_file_stay_distinct(self):
        self.assertNotEqual(
            fingerprint("ReturnCount", "Function resolve has 3 return statements which exceeds the limit of 2.",
                        "    fun resolve(key: String): Int {"),
            fingerprint("ReturnCount", "Function lookup has 3 return statements which exceeds the limit of 2.",
                        "    fun resolve(key: String): Int {"))

    def test_finding_in_previously_clean_file_is_red(self):
        before = [finding("a.kt", "shape-a")]
        after = [finding("a.kt", "shape-a"), finding("clean.kt", "shape-b")]
        result = compare(snapshot(before), snapshot(after))
        self.assertFalse(result["ok"])
        self.assertEqual([entry[2] for entry in result["new"]], ["clean.kt"])


class SchemaVersionTest(unittest.TestCase):
    def test_schema_version_one_baseline_fails_with_remint_hint(self):
        with self.assertRaises(ValueError) as caught:
            compare(snapshot(schema_version=1), snapshot())
        self.assertIn("Re-mint", str(caught.exception))
        self.assertIn("schemaVersion", str(caught.exception))

    def test_missing_schema_version_fails(self):
        current = snapshot()
        del current["schemaVersion"]
        with self.assertRaises(ValueError):
            compare(snapshot(), current)


if __name__ == "__main__":
    unittest.main()
