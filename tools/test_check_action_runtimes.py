import unittest
from check_action_runtimes import action_refs, check, runtime


class ActionRuntimeGateTest(unittest.TestCase):
    def test_rejects_mutable_tags_and_missing_pins(self):
        for ref in ['actions/checkout@v7', 'actions/checkout', './local-action']:
            with self.assertRaises(ValueError):
                action_refs('  - uses: ' + ref)

    def test_rejects_old_or_opaque_runtimes(self):
        for value in ['node20', 'node16', 'composite', 'docker']:
            with self.assertRaises(ValueError):
                runtime('runs:\n  using: "' + value + '"\n')

    def test_quoted_and_unquoted_supported_runtime(self):
        for value in ['node24', '"node24"', "'node24'"]:
            self.assertEqual('node24', runtime('runs:\n  using: ' + value + '\n'))

    def test_inspects_pinned_nested_action_and_deduplicates(self):
        ref = 'gradle/actions/wrapper-validation@' + 'a' * 40
        calls = []
        def fetch(url):
            calls.append(url)
            return "runs:\n  using: 'node24'\n"
        result = check('  - uses: ' + ref + '\n  - uses: ' + ref, fetch)
        self.assertEqual(1, len(result))
        self.assertTrue(calls[0].endswith('/' + 'a' * 40 + '/wrapper-validation/action.yml'))


if __name__ == '__main__':
    unittest.main()
