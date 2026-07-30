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

    # 5. Clean up files
    os.remove(test_db)
    os.remove(test_out)
    print("SUCCESS: All export_corrections python script tests passed successfully!")

if __name__ == "__main__":
    run_tests()
