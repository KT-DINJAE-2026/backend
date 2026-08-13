import json
import sys
import tempfile
import unittest
from pathlib import Path

import pyarrow as pa
import pyarrow.parquet as pq


PIPELINE_DIR = Path(__file__).resolve().parents[1]
if str(PIPELINE_DIR) not in sys.path:
    sys.path.insert(0, str(PIPELINE_DIR))

from finalize_metropolitan_dataset import finalize_dataset  # noqa: E402


SCHEMA = pa.schema([
    ("route_id", pa.string()),
    ("board_stop_id", pa.string()),
    ("alight_stop_id", pa.string()),
    ("hour", pa.int64()),
    ("sample_count_route_segment_hour", pa.int64()),
])


class FinalizeMetropolitanDatasetTests(unittest.TestCase):

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.input_dir = self.root / "daily"
        self.output_dir = self.root / "final"
        self.input_dir.mkdir()
        self._write_day(
            "20240101",
            [
                ("R1", "S1", "S2", 8, 2),
                ("R1", "S1", "S2", 8, 2),
                ("R2", "S3", None, 9, 1),
            ],
        )
        self._write_day(
            "20240102",
            [
                ("R1", "S1", "S2", 8, 3),
                ("R1", "S1", "S2", 8, 3),
                ("R1", "S1", "S2", 8, 3),
            ],
        )

    def tearDown(self):
        self.temp_dir.cleanup()

    def _write_day(self, date, rows):
        path = self.input_dir / f"roster_{date}.parquet"
        columns = list(zip(*rows))
        table = pa.Table.from_arrays(
            [pa.array(values, type=field.type)
             for values, field in zip(columns, SCHEMA)],
            schema=SCHEMA,
        )
        pq.write_table(table, path, compression="zstd")
        path.with_suffix(".parquet.manifest.json").write_text(
            json.dumps({
                "service_date": date,
                "id_namespace": "national-standard-id",
                "sample_count_scope": "single-service-day",
            }),
            encoding="utf-8",
        )

    def test_recomputes_counts_across_days_without_modifying_inputs(self):
        before = {
            path.name: path.read_bytes()
            for path in self.input_dir.glob("*.parquet")
        }

        manifest = finalize_dataset(
            self.input_dir, self.output_dir, batch_size=2
        )

        day1 = pq.read_table(
            self.output_dir / "roster_20240101.parquet"
        ).to_pandas()
        day2 = pq.read_table(
            self.output_dir / "roster_20240102.parquet"
        ).to_pandas()
        self.assertEqual([5, 5, 1], day1["sample_count_route_segment_hour"].tolist())
        self.assertEqual([5, 5, 5], day2["sample_count_route_segment_hour"].tolist())
        self.assertEqual(6, manifest["row_count"])
        self.assertEqual(2, manifest["route_segment_hour_group_count"])
        self.assertEqual(
            before,
            {path.name: path.read_bytes()
             for path in self.input_dir.glob("*.parquet")},
        )

    def test_rejects_output_in_input_directory(self):
        with self.assertRaisesRegex(ValueError, "출력 디렉터리는 입력과 달라야"):
            finalize_dataset(self.input_dir, self.input_dir)


if __name__ == "__main__":
    unittest.main()
