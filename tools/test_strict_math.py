"""strict_math.py's reading of Java source and its baseline ratchet."""

import unittest

import strict_math


class CallTests(unittest.TestCase):
    def calls(self, text):
        return dict(strict_math.inexact_calls(text)[0])

    def test_inexact_calls_are_counted_and_exact_ones_allowed(self):
        text = "double a = Math.sin(x) + Math.sin(y) * Math.floor(z) + Math.pow(a, b);"
        self.assertEqual(self.calls(text), {"sin": 2, "pow": 1})

    def test_strict_math_constants_and_method_references(self):
        text = "StrictMath.sin(x); double p = Math.PI; f(Math::cos); f(Math::abs);"
        self.assertEqual(self.calls(text), {"cos": 1})

    def test_comments_and_literals_are_not_calls(self):
        text = '// Math.sin(x)\n/* Math.exp(y) */ String s = "Math.pow(a, b)"; char c = \'"\';'
        text += " Math.tanh(t);"
        self.assertEqual(self.calls(text), {"tanh": 1})

    def test_qualified_and_spaced_calls(self):
        self.assertEqual(self.calls("java.lang.Math.sin(x); Math . cos (y);"), {"sin": 1, "cos": 1})

    def test_static_imports_are_reported_by_line(self):
        text = "package a;\n\nimport static java.lang.Math.sin;\n"
        self.assertEqual(strict_math.inexact_calls(text)[1], [3])


class BaselineTests(unittest.TestCase):
    def test_growth_fails_and_shrinkage_is_noted(self):
        baseline = {"A.java": {"sin": 2}, "B.java": {"exp": 1}}
        found = {"A.java": {"sin": 3}, "C.java": {"pow": 1}}
        added, spare = strict_math.compare(found, baseline)
        self.assertEqual(len(added), 2)
        self.assertTrue(added[0].startswith("A.java: Math.sin x1"))
        self.assertEqual(spare, ["B.java: Math.exp 1 -> 0"])

    def test_the_tree_holds_no_calls_beyond_its_baseline(self):
        found, imports = strict_math.scan()
        self.assertEqual(imports, [])
        self.assertEqual(strict_math.compare(found, strict_math.load_baseline())[0], [])


if __name__ == "__main__":
    unittest.main()
