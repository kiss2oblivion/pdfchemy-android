import unittest
from check_android_emulator_space import MINIMUM_FREE_BYTES, require_headroom


class EmulatorSpaceRegressionTest(unittest.TestCase):
    def test_original_api30_runner_is_rejected_before_boot(self):
        with self.assertRaisesRegex(RuntimeError, 'No tests have started'):
            require_headroom(int(6701.91 * 1024 * 1024))

    def test_headroom_boundary_and_one_byte_below(self):
        with self.assertRaises(RuntimeError):
            require_headroom(MINIMUM_FREE_BYTES - 1)
        require_headroom(MINIMUM_FREE_BYTES)
        require_headroom(MINIMUM_FREE_BYTES + 1)
