import gzip
import importlib.util
import io
import json
from pathlib import Path
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("inspect_session", Path(__file__).parents[1] / "tools/inspect_session.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def archive(ppg_rows, truncate=False, metadata_extra=None, multichannel=False):
    data = io.BytesIO()
    with zipfile.ZipFile(data, "w") as package:
        metadata = dict(started_elapsed_ms=1000, snapshot_elapsed_ms=121000,
                        battery_start_percent=80, battery_snapshot_percent=79,
                        session_state="completed", mode="test")
        metadata.update(metadata_extra or {})
        package.writestr("metadata.json", json.dumps(metadata))
        header = "source_timestamp_ms,received_wall_ms,received_elapsed_ns,green_adc,green_status"
        if multichannel:
            header += ",ir_adc,ir_status,red_adc,red_status"
        content = header + "\n" + ppg_rows
        compressed = gzip.compress(content.encode())
        package.writestr("ppg.csv.gz", compressed[:-8] if truncate else compressed)
        package.writestr("hr.csv.gz", gzip.compress(
            b"source_timestamp_ms,received_wall_ms,received_elapsed_ns,bpm,hr_status,ibi_ms,ibi_status\n"
            b"1000,9000,8000,60,1,1000;1001,0;-1\n"))
        package.writestr("acc.csv.gz", gzip.compress(
            b"source_timestamp_ms,received_wall_ms,received_elapsed_ns,x_raw,y_raw,z_raw\n"
            b"1000,9000,8000,1,2,3\n"))
    data.seek(0)
    return data


class InspectionTests(unittest.TestCase):
    def test_batch_timing(self):
        rows = "".join(f"{1000+i*40},9000,8000,100,0\n" for i in range(250))
        result = module.inspect_session(archive(rows))
        self.assertEqual(result["streams"]["ppg"]["gaps"], 0)
        self.assertEqual(result["streams"]["ppg"]["observed_hz"], 25)
        self.assertEqual(result["duration_seconds"], 120)
        self.assertEqual(result["battery_percentage_points"], 1)

    def test_quality_flags(self):
        result = module.inspect_session(archive("1000,9000,8000,100,-1\n1040,9000,8000,100,0\n"))
        self.assertEqual(result["streams"]["ppg"]["valid"], 1)
        self.assertEqual(result["streams"]["ppg"]["invalid"], 1)
        self.assertEqual(result["streams"]["hr"]["ibi_valid"], 1)
        self.assertEqual(result["streams"]["hr"]["ibi_count"], 2)

    def test_missing_stream_data(self):
        result = module.inspect_session(archive(""))
        self.assertFalse(result["all_three_streams_have_valid_samples"])
        self.assertEqual(result["streams"]["ppg"]["observed_hz"], 0)

    def test_duplicate_and_backwards(self):
        rows = "".join(f"{t},9000,8000,100,0\n" for t in [1000, 1040, 1040, 1020, 2040])
        result = module.inspect_session(archive(rows))["streams"]["ppg"]
        self.assertEqual((result["duplicates"], result["backwards"], result["gaps"]), (1, 1, 1))

    def test_interrupted_gzip_is_reported(self):
        rows = "".join(f"{1000+i*40},9000,8000,100,0\n" for i in range(3000))
        result = module.inspect_session(archive(rows, truncate=True))["streams"]["ppg"]
        self.assertIsNotNone(result["read_error"])
        self.assertGreater(result["samples"], 0)

    def test_zero_ppg_is_not_a_valid_signal(self):
        result = module.inspect_session(archive("1000,9000,8000,0,0\n1040,9000,8000,0,0\n"))
        ppg = result["streams"]["ppg"]
        self.assertEqual(ppg["valid"], 0)
        self.assertEqual(ppg["zero_samples"], 2)
        self.assertFalse(ppg["signal_changes"])
        self.assertFalse(result["expected_streams_have_valid_samples"])

    def test_nonzero_flatline_is_reported(self):
        result = module.inspect_session(archive("1000,9000,8000,100,0\n1040,9000,8000,100,0\n"))
        self.assertFalse(result["ppg_signal_changes"])

    def test_heart_rate_profile_does_not_require_ppg(self):
        result = module.inspect_session(archive("", metadata_extra={
            "profile": "hr_only", "expected_streams": ["hr", "acc"], "on_wrist": True}))
        self.assertTrue(result["expected_streams_have_valid_samples"])
        self.assertFalse(result["all_three_streams_have_valid_samples"])
        self.assertIsNone(result["ppg_signal_changes"])
        self.assertTrue(result["on_wrist"])

    def test_ir_signal_is_reported_when_green_is_zero(self):
        rows = "1000,9000,8000,0,0,100,0,200,0\n1010,9000,8000,0,0,110,0,200,0\n"
        result = module.inspect_session(archive(rows, multichannel=True, metadata_extra={
            "profile": "ppg_probe", "expected_streams": ["ppg"]}))
        ppg = result["streams"]["ppg"]
        self.assertEqual(ppg["observed_hz"], 100)
        self.assertEqual(ppg["valid"], 2)
        self.assertEqual(ppg["channels"]["green"]["valid"], 0)
        self.assertTrue(ppg["channels"]["ir"]["signal_changes"])
        self.assertFalse(ppg["channels"]["red"]["signal_changes"])
        self.assertEqual(ppg["channels"]["red"]["samples"], 2)
        self.assertTrue(result["ppg_signal_changes"])
        self.assertTrue(result["expected_streams_have_valid_samples"])

    def test_startup_zero_and_bad_status_do_not_establish_variation(self):
        rows = "1000,9000,8000,0,0\n1040,9000,8000,100,0\n1080,9000,8000,200,-1\n1120,9000,8000,100,0\n"
        ppg = module.inspect_session(archive(rows))["streams"]["ppg"]
        self.assertEqual(ppg["value_minimum"], 0)
        self.assertEqual(ppg["value_maximum"], 200)
        self.assertEqual(ppg["valid"], 2)
        self.assertFalse(ppg["signal_changes"])

    def test_multichannel_all_bad_statuses_are_invalid(self):
        ppg = module.inspect_session(archive(
            "1000,9000,8000,100,-1,200,-1,300,-1\n", multichannel=True))["streams"]["ppg"]
        self.assertEqual(ppg["valid"], 0)
        self.assertFalse(ppg["signal_changes"])


if __name__ == "__main__":
    unittest.main()
