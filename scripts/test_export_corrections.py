#!/usr/bin/env python3
import os
import sqlite3
import json
import subprocess
import sys

def assert_eq(actual, expected, message):
    if actual != expected:
        print(f"[FAIL] Assertion Failed: {message} ({actual} != {expected})")
        sys.exit(1)
    else:
        print(f"[PASS]: {message}")

def run_tests():
    print("Running export_corrections Python script integration tests...")
    
    test_db = "test_models.db"
    test_out = "test_dataset.jsonl"
    
    # Cleanup previous leftovers
    if os.path.exists(test_db):
        os.remove(test_db)
    if os.path.exists(test_out):
        os.remove(test_out)

    # 1. Create helper database with corrections table
    conn = sqlite3.connect(test_db)
    cursor = conn.cursor()
    cursor.execute("""
        CREATE TABLE corrections (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            audio_id TEXT NOT NULL,
            original_transcription TEXT NOT NULL,
            corrected_transcription TEXT NOT NULL,
            edits TEXT NOT NULL,
            edit_distance INTEGER NOT NULL,
            timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
            user_id TEXT,
            confidence_score REAL
        )
    """)
    
    # 2. Ingest test rows
    cursor.execute("""
        INSERT INTO corrections (audio_id, original_transcription, corrected_transcription, edits, edit_distance)
        VALUES ('audio_001', 'hello world', 'hello big world', '[]', 1)
    """)
    cursor.execute("""
        INSERT INTO corrections (audio_id, original_transcription, corrected_transcription, edits, edit_distance)
        VALUES ('audio_002', 'whisper engine', 'whisper cleaner engine', '[]', 1)
    """)
    conn.commit()
    conn.close()

    # 3. Invoke script using subprocess
    script_path = os.path.join(os.path.dirname(__file__), "export_corrections.py")
    result = subprocess.run(
        [sys.executable, script_path, "--db", test_db, "--out", test_out],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True
    )
    
    assert_eq(result.returncode, 0, "Script executed with exit code 0")
    
    # 4. Parse output records
    assert_eq(os.path.exists(test_out), True, "JSONL dataset dataset output file exists")
    
    with open(test_out, 'r', encoding='utf-8') as f:
        lines = f.readlines()
        
    assert_eq(len(lines), 2, "Exported exactly 2 corrections records")
    
    record1 = json.loads(lines[0])
    assert_eq(record1["audio_id"], "audio_001", "Record 1 audio_id matches")
    assert_eq(record1["original"], "hello world", "Record 1 original matches")
    assert_eq(record1["corrected"], "hello big world", "Record 1 corrected matches")
    
    record2 = json.loads(lines[1])
    assert_eq(record2["audio_id"], "audio_002", "Record 2 audio_id matches")
    assert_eq(record2["original"], "whisper engine", "Record 2 original matches")
    assert_eq(record2["corrected"], "whisper cleaner engine", "Record 2 corrected matches")

    # 5. Quarantine exclusion (map #130 ticket #136): rows flagged quarantined
    # are local-only and must never be exported for training.
    q_db = "test_quarantine.db"
    q_out = "test_quarantine_dataset.jsonl"
    for stale in (q_db, q_out):
        if os.path.exists(stale):
            os.remove(stale)
    q_conn = sqlite3.connect(q_db)
    q_cur = q_conn.cursor()
    q_cur.execute("""
        CREATE TABLE corrections (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            audio_id TEXT NOT NULL,
            original_transcription TEXT NOT NULL,
            corrected_transcription TEXT NOT NULL,
            edits TEXT NOT NULL,
            edit_distance INTEGER NOT NULL,
            timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
            user_id TEXT,
            confidence_score REAL,
            quarantined INTEGER DEFAULT 0
        )
    """)
    q_cur.execute("""
        INSERT INTO corrections (audio_id, original_transcription, corrected_transcription, edits, edit_distance, quarantined)
        VALUES ('audio_public_1', 'hello world', 'hello big world', '[]', 1, 0)
    """)
    q_cur.execute("""
        INSERT INTO corrections (audio_id, original_transcription, corrected_transcription, edits, edit_distance, quarantined)
        VALUES ('audio_quarantined_1', 'my password is hunter2', 'my password is hunter2!', '[]', 1, 1)
    """)
    q_conn.commit()
    q_conn.close()

    q_result = subprocess.run(
        [sys.executable, script_path, "--db", q_db, "--out", q_out],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True
    )
    assert_eq(q_result.returncode, 0, "Quarantine export script executed with exit code 0")

    with open(q_out, 'r', encoding='utf-8') as f:
        q_lines = f.readlines()

    assert_eq(len(q_lines), 1, "Exactly 1 non-quarantined record exported (quarantine excluded)")
    q_record = json.loads(q_lines[0])
    assert_eq(q_record["audio_id"], "audio_public_1", "Exported record is the public one")
    assert "hunter2" not in open(q_out, encoding='utf-8').read(), "Quarantined content absent from export"

    os.remove(q_db)
    os.remove(q_out)

    # 6. All-quarantined (map #130 PR #138 fix): when every row is quarantined
    # the run must not leave a stale output file behind for a later consumer.
    aq_db = "test_all_quarantined.db"
    aq_out = "test_all_quarantined_dataset.jsonl"
    for stale in (aq_db, aq_out):
        if os.path.exists(stale):
            os.remove(stale)
    aq_conn = sqlite3.connect(aq_db)
    aq_cur = aq_conn.cursor()
    aq_cur.execute("""
        CREATE TABLE corrections (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            audio_id TEXT NOT NULL,
            original_transcription TEXT NOT NULL,
            corrected_transcription TEXT NOT NULL,
            edits TEXT NOT NULL,
            edit_distance INTEGER NOT NULL,
            timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
            user_id TEXT,
            confidence_score REAL,
            quarantined INTEGER DEFAULT 0
        )
    """)
    aq_cur.execute("""
        INSERT INTO corrections (audio_id, original_transcription, corrected_transcription, edits, edit_distance, quarantined)
        VALUES ('audio_quarantined_only', 'my password is hunter2', 'my password is hunter2!', '[]', 1, 1)
    """)
    aq_conn.commit()
    aq_conn.close()
    # Pre-create a stale output file simulating an earlier successful export.
    with open(aq_out, 'w', encoding='utf-8') as f:
        f.write('{"audio_id": "stale_from_earlier_run"}\n')

    aq_result = subprocess.run(
        [sys.executable, script_path, "--db", aq_db, "--out", aq_out],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True
    )
    assert_eq(aq_result.returncode, 0, "All-quarantined export script executed with exit code 0")
    assert_eq(os.path.exists(aq_out), False, "All-quarantined run removes the stale output file (no stale dataset)")

    # 6b. All-quarantined with unremovable stale output (map #130 PR #138
    # review): if the stale file cannot be removed, the run must fail loudly
    # (nonzero exit) so no caller treats the stale dataset as this run's result.
    aq_fail_db = "test_all_quarantined_unremovable.db"
    aq_fail_out = "test_all_quarantined_unremovable_dataset.jsonl"
    for stale in (aq_fail_db, aq_fail_out):
        if os.path.exists(stale):
            if os.path.isdir(stale):
                os.rmdir(stale)
            else:
                os.remove(stale)
    fail_conn = sqlite3.connect(aq_fail_db)
    fail_cur = fail_conn.cursor()
    fail_cur.execute("""
        CREATE TABLE corrections (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            audio_id TEXT NOT NULL,
            original_transcription TEXT NOT NULL,
            corrected_transcription TEXT NOT NULL,
            edits TEXT NOT NULL,
            edit_distance INTEGER NOT NULL,
            timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
            user_id TEXT,
            confidence_score REAL,
            quarantined INTEGER DEFAULT 0
        )
    """)
    fail_cur.execute("""
        INSERT INTO corrections (audio_id, original_transcription, corrected_transcription, edits, edit_distance, quarantined)
        VALUES ('audio_quarantined_only', 'my password is hunter2', 'my password is hunter2!', '[]', 1, 1)
    """)
    fail_conn.commit()
    fail_conn.close()
    # A directory at the output path makes os.remove raise, simulating an
    # unremovable stale file.
    os.mkdir(aq_fail_out)
    fail_result = subprocess.run(
        [sys.executable, script_path, "--db", aq_fail_db, "--out", aq_fail_out],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True
    )
    assert_eq(fail_result.returncode != 0, True, "Unremovable stale output fails the export (nonzero exit)")
    os.rmdir(aq_fail_out)
    os.remove(aq_fail_db)

    os.remove(aq_db)
    if os.path.exists(aq_out):
        os.remove(aq_out)

    # 7. Clean up files
    os.remove(test_db)
    os.remove(test_out)
    print("SUCCESS: All export_corrections python script tests passed successfully!")

if __name__ == "__main__":
    run_tests()
