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


def archive(ppg_rows, truncate=False):
    data = io.BytesIO()
    with zipfile.ZipFile(data, "w") as package:
        metadata = dict(started_elapsed_ms=1000, snapshot_elapsed_ms=121000,
                        battery_start_percent=80, battery_snapshot_percent=79,
                        session_state="completed", mode="test")
        package.writestr("metadata.json", json.dumps(metadata))
        content = "source_timestamp_ms,received_wall_ms,received_elapsed_ns,green_adc,green_status\n" + ppg_rows
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


if __name__ == "__main__":
    unittest.main()
