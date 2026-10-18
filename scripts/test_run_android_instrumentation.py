import unittest

from run_android_instrumentation import validate_result


def passing_result(count=2):
    output = []
    for index in range(1, count + 1):
        for code in (1, 0):
            output.extend((f"INSTRUMENTATION_STATUS: numtests={count}",
                           "INSTRUMENTATION_STATUS: class=security.RealExportTest",
                           f"INSTRUMENTATION_STATUS: test=export{index}",
                           f"INSTRUMENTATION_STATUS: current={index}",
                           f"INSTRUMENTATION_STATUS_CODE: {code}"))
    output.extend((f"OK ({count} tests)", "INSTRUMENTATION_CODE: -1"))
    return "\n".join(output)


class NativeResultGateTest(unittest.TestCase):
    def test_complete_positive_run(self):
        self.assertEqual(2, validate_result(passing_result()))

    def test_windows_newlines(self):
        self.assertEqual(2, validate_result(passing_result().replace("\n", "\r\n")))

    def test_failure_crash_and_skips_never_pass(self):
        for marker in ("FAILURES!!!", "INSTRUMENTATION_FAILED: error",
                       "INSTRUMENTATION_RESULT: shortMsg=Process crashed."):
            with self.subTest(marker=marker), self.assertRaises(ValueError):
                validate_result(passing_result() + "\n" + marker)
        for code in (-1, -2, -3, -4):
            with self.subTest(code=code), self.assertRaises(ValueError):
                validate_result(passing_result().replace("STATUS_CODE: 0", f"STATUS_CODE: {code}", 1))

    def test_zero_or_missing_tests_never_pass(self):
        for output in ("", passing_result(0), "INSTRUMENTATION_CODE: -1",
                       "OK (2 tests)\nINSTRUMENTATION_CODE: -1"):
            with self.subTest(output=output), self.assertRaises(ValueError):
                validate_result(output)

    def test_missing_completion_or_wrong_count_never_pass(self):
        for output in (passing_result().replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0"),
                       passing_result().replace("OK (2 tests)", "OK (3 tests)"),
                       passing_result().replace("INSTRUMENTATION_STATUS_CODE: 0", "", 1),
                       passing_result().replace("current=2", "current=1"),
                       passing_result() + "\nINSTRUMENTATION_CODE: -1"):
            with self.subTest(output=output), self.assertRaises(ValueError):
                validate_result(output)


if __name__ == "__main__":
    unittest.main()
