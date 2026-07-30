#!/usr/bin/env python3
import os
import sys
import sqlite3
import json
import argparse
import subprocess

def pull_adb_db(destination):
    print("Attempting to pull models.db from Android device via ADB...")
    # Standard location for app databases on Android
    device_path = "/data/data/com.velavoice.app/databases/models.db"
    try:
        result = subprocess.run(
            ["adb", "pull", device_path, destination],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True
        )
        if result.returncode == 0:
            print(f"Successfully pulled models.db to {destination}")
            return True
        else:
            # Try alternative path for newer Android/scoped storage setup
            device_path_alt = "/data/user/0/com.velavoice.app/databases/models.db"
            result_alt = subprocess.run(
                ["adb", "pull", device_path_alt, destination],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True
            )
            if result_alt.returncode == 0:
                print(f"Successfully pulled models.db (alt path) to {destination}")
                return True
            print("ADB pull failed. Ensure device/emulator is connected and app is installed.")
            print(f"ADB output: {result.stderr.strip() or result.stdout.strip()}")
            return False
    except Exception as e:
        print(f"Failed to execute ADB command: {e}")
        return False

def export_corrections(db_path, output_path):
    if not os.path.exists(db_path):
        # If default local file doesn't exist, try pulling from ADB first
        if db_path == "models.db":
            pulled = pull_adb_db(db_path)
            if not pulled:
                print(f"Error: Database file not found at local '{db_path}' and ADB pull failed.")
                sys.exit(1)
        else:
            print(f"Error: Database file not found at '{db_path}'")
            sys.exit(1)

    print(f"Opening database: {db_path}")
    try:
        conn = sqlite3.connect(db_path)
        cursor = conn.cursor()
        
        # Check if table exists
        cursor.execute("SELECT name FROM sqlite_master WHERE type='table' AND name='corrections'")
        if not cursor.fetchone():
            print("Error: The 'corrections' table does not exist in the database yet.")
            conn.close()
            sys.exit(1)

        cursor.execute("SELECT audio_id, original_transcription, corrected_transcription FROM corrections")
        rows = cursor.fetchall()
        
        if not rows:
            print("No corrections found in the database.")
            conn.close()
            return

        print(f"Exporting {len(rows)} corrections to {output_path}...")
        with open(output_path, 'w', encoding='utf-8') as f:
            for row in rows:
                item = {
                    "audio_id": row[0],
                    "original": row[1],
                    "corrected": row[2]
                }
                f.write(json.dumps(item) + '\n')
                
        print("SUCCESS: Export completed successfully!")
        conn.close()
    except Exception as e:
        print(f"ERROR: Export failed: {e}")
        sys.exit(1)

def main():
    parser = argparse.ArgumentParser(description="Export transcription corrections from models.db SQLite database for model fine-tuning.")
    parser.add_argument("--db", default="models.db", help="Path to models.db file (default: models.db - will try adb pull if local file doesn't exist)")
    parser.add_argument("--out", default="fine_tune_dataset.jsonl", help="Path to output JSONL file (default: fine_tune_dataset.jsonl)")
    parser.add_argument("--pull", action="store_true", help="Force pull database from device using ADB before exporting")
    
    args = parser.parse_args()
    
    if args.pull:
        pull_adb_db(args.db)
        
    export_corrections(args.db, args.out)

if __name__ == "__main__":
    main()
