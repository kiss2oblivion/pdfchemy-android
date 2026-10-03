import copy
import hashlib
import unittest
from check_secrets import VERSION, is_reviewed, scan_complete


class ReviewedSecretGateTest(unittest.TestCase):
    def setUp(self):
        self.record = {"DetectorName": "fixture", "DecoderName": "PLAIN", "Verified": False, "VerificationError": None,
                       "Raw": "public fixture bytes", "SourceMetadata": {"Data": {"Git": {"file": "old/artifact", "commit": "c" * 40}}}}
        self.reviewed = [{"detector": "fixture", "decoder": "PLAIN", "rawSha256": hashlib.sha256(b"public fixture bytes").hexdigest(),
                          "sources": [{"path": "old/artifact", "commit": "c" * 40}]}]

    def test_exact_historical_unverified_match_is_reviewed(self):
        self.assertTrue(is_reviewed(self.record, self.reviewed))

    def test_verified_match_is_never_suppressed(self):
        self.record["Verified"] = True
        self.assertFalse(is_reviewed(self.record, self.reviewed))

    def test_verification_error_is_never_suppressed(self):
        self.record["VerificationError"] = "provider unavailable"
        self.assertFalse(is_reviewed(self.record, self.reviewed))

    def test_new_bytes_commit_or_location_are_rejected(self):
        for field, value in (("file", "new/artifact"), ("commit", "d" * 40)):
            record = copy.deepcopy(self.record)
            record["SourceMetadata"]["Data"]["Git"][field] = value
            self.assertFalse(is_reviewed(record, self.reviewed))
        self.record["Raw"] += "changed"
        self.assertFalse(is_reviewed(self.record, self.reviewed))

    def test_different_decoder_or_detector_are_rejected(self):
        for field in ("DetectorName", "DecoderName"):
            record = dict(self.record, **{field: "different"})
            self.assertFalse(is_reviewed(record, self.reviewed))

    def test_missing_identity_or_verification_state_is_rejected(self):
        for field in ("Raw", "Verified", "SourceMetadata"):
            record = dict(self.record)
            del record[field]
            self.assertFalse(is_reviewed(record, self.reviewed))


class ScanCompletionTest(unittest.TestCase):
    def setUp(self):
        self.head = "c" * 40
        self.logs = [{"msg": "scanning repo", "head": self.head},
                     {"msg": "finished scanning", "trufflehog_version": VERSION}]

    def test_complete_scan_of_expected_head(self):
        self.assertTrue(scan_complete(0, self.logs, self.head))

    def test_truncated_failed_or_wrong_head_scan_rejected(self):
        self.assertFalse(scan_complete(0, self.logs[:-1], self.head))
        self.assertFalse(scan_complete(2, self.logs, self.head))
        self.assertFalse(scan_complete(0, self.logs, "d" * 40))

    def test_archive_read_error_even_with_success_exit_rejected(self):
        self.assertFalse(scan_complete(0, self.logs + [{"level": "error", "msg": "archive read failed"}], self.head))

    def test_unexpected_scanner_version_rejected(self):
        self.logs[-1]["trufflehog_version"] = "different"
        self.assertFalse(scan_complete(0, self.logs, self.head))


if __name__ == "__main__":
    unittest.main()
