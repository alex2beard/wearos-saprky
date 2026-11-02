#!/usr/bin/env python3
"""Summarize a Sleep Lab ZIP using source timestamps, without classifying sleep stages."""
import argparse
import csv
import gzip
import io
import json
import zipfile


def inspect_stream(archive, name, kind):
    result = dict(samples=0, valid=0, invalid=0, duplicates=0, backwards=0,
                  gaps=0, max_gap_ms=0, ibi_count=0, ibi_valid=0, ibi_list_mismatches=0,
                  read_error=None, zero_samples=0, value_minimum=None, value_maximum=None,
                  status_counts={})
    previous = first = last = None
    threshold = 5000 if kind == "hr" else 200
    try:
        with archive.open(name) as packed, gzip.GzipFile(fileobj=packed) as raw:
            with io.TextIOWrapper(raw, encoding="utf-8", newline="") as text:
                for row in csv.DictReader(text):
                    timestamp = int(row["source_timestamp_ms"])
                    result["samples"] += 1
                    if kind == "ppg":
                        value = int(row["green_adc"]) if row["green_adc"] else None
                        valid = row["green_status"] == "0" and value is not None and value != 0
                        if value is not None:
                            result["zero_samples"] += int(value == 0)
                            result["value_minimum"] = value if result["value_minimum"] is None else min(result["value_minimum"], value)
                            result["value_maximum"] = value if result["value_maximum"] is None else max(result["value_maximum"], value)
                        status = row["green_status"]
                        result["status_counts"][status] = result["status_counts"].get(status, 0) + 1
                    elif kind == "hr":
                        valid = row["hr_status"] == "1" and int(row["bpm"] or 0) > 0
                        status = row["hr_status"]
                        result["status_counts"][status] = result["status_counts"].get(status, 0) + 1
                        intervals = row["ibi_ms"].split(";") if row["ibi_ms"] else []
                        statuses = row["ibi_status"].split(";") if row["ibi_status"] else []
                        result["ibi_count"] += len(intervals)
                        result["ibi_list_mismatches"] += int(len(intervals) != len(statuses))
                        result["ibi_valid"] += sum(
                            int(value or 0) > 0 and status == "0"
                            for value, status in zip(intervals, statuses))
                    else:
                        valid = all(row[field] != "" for field in ["x_raw", "y_raw", "z_raw"])
                    result["valid" if valid else "invalid"] += 1
                    if first is None:
                        first = timestamp
                    if previous is not None:
                        gap = timestamp - previous
                        result["duplicates"] += int(gap == 0)
                        result["backwards"] += int(gap < 0)
                        result["gaps"] += int(gap > threshold)
                        result["max_gap_ms"] = max(result["max_gap_ms"], gap)
                    last = previous = timestamp
    except (EOFError, OSError, UnicodeError, csv.Error, ValueError, KeyError) as error:
        result["read_error"] = str(error)
    result["first_timestamp_ms"] = first
    result["last_timestamp_ms"] = last
    span = last - first if first is not None and last is not None else 0
    result["observed_hz"] = (result["samples"] - 1) * 1000 / span if span > 0 else 0
    if kind == "ppg":
        result["signal_changes"] = result["samples"] > 1 and result["value_minimum"] != result["value_maximum"]
    return result


def inspect_session(path):
    with zipfile.ZipFile(path) as archive:
        metadata = json.loads(archive.read("metadata.json"))
        streams = {kind: inspect_stream(archive, kind + ".csv.gz", kind) for kind in ["ppg", "hr", "acc"]}
    start = metadata.get("started_elapsed_ms", 0)
    end = metadata.get("snapshot_elapsed_ms", start)
    first_battery = metadata.get("battery_start_percent", -1)
    last_battery = metadata.get("battery_snapshot_percent", -1)
    expected = metadata.get("expected_streams", ["ppg", "hr", "acc"])
    if not isinstance(expected, list) or not expected or any(stream not in streams for stream in expected):
        raise ValueError("Invalid expected_streams in metadata")
    return {
        "schema": 1,
        "session_state": metadata.get("session_state"),
        "mode": metadata.get("mode"),
        "profile": metadata.get("profile", "combined"),
        "expected_streams": expected,
        "on_wrist": metadata.get("on_wrist"),
        "device": {key: metadata.get(key) for key in ["model", "android_sdk", "firmware", "sensor_service_version"]},
        "duration_seconds": (end - start) / 1000,
        "battery_percentage_points": first_battery - last_battery
            if min(first_battery, last_battery) >= 0 else None,
        "stop_reason": metadata.get("stop_reason"),
        "sdk_error": metadata.get("error"),
        "streams": streams,
        "all_three_streams_have_valid_samples": all(item["valid"] > 0 for item in streams.values()),
        "expected_streams_have_valid_samples": all(streams[stream]["valid"] > 0 for stream in expected),
        "ppg_signal_changes": streams["ppg"]["signal_changes"] if "ppg" in expected else None,
        "sleep_classification": "not_implemented",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("session_zip")
    options = parser.parse_args()
    print(json.dumps(inspect_session(options.session_zip), indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
