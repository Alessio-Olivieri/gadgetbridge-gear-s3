#!/usr/bin/env python3
"""Verify fresh private DB pulls read-only and print (never execute) a debug recovery command."""
import argparse
import hashlib
import re
import shlex
import sqlite3
import struct
import sys
import time
from pathlib import Path


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("db", type=Path)
    parser.add_argument("repeat", type=Path)
    parser.add_argument("journal", type=Path)
    parser.add_argument("sequence", type=Path)
    parser.add_argument("--device", required=True)
    parser.add_argument("--challenge", required=True)
    args = parser.parse_args()
    require(re.fullmatch(r"(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}", args.device), "invalid device")
    require(re.fullmatch(r"[0-9a-f-]{36}", args.challenge), "invalid challenge")
    now = time.time()
    for path in (args.db, args.repeat, args.journal, args.sequence):
        require(0 <= now - path.stat().st_mtime <= 60, "pulls are not fresh; repeat all four pulls")
    first = args.db.read_bytes()
    require(first == args.repeat.read_bytes(), "DB pulls differ; wait for Calendar to be idle")
    journal = args.journal.read_bytes()
    require(not journal or len(journal) >= 28 and not any(journal[:28]), "journal may be active")
    sequence = args.sequence.read_bytes()
    key = b"sequenceNumber"
    require(len(sequence) == 4 + len(key) + 8, "unexpected watch sequence format")
    require(struct.unpack_from("<I", sequence)[0] == len(key) and sequence[4:4+len(key)] == key,
            "unexpected watch sequence key")
    require(struct.unpack_from("<II", sequence, 4+len(key)) == (2, 0), "watch sequence is not zero")
    with sqlite3.connect(args.db.resolve().as_uri() + "?mode=ro&immutable=1", uri=True) as db:
        require(db.execute("PRAGMA integrity_check").fetchone()[0] == "ok", "DB integrity failure")
        require(db.execute("SELECT count(*) FROM event_table").fetchone()[0] == 0, "watch events are not empty")
        require(db.execute("SELECT count(*) FROM reminder_table").fetchone()[0] == 0, "watch reminders are not empty")
    digest = hashlib.sha256(first).hexdigest()
    command = ["adb", "shell", "am", "broadcast", "-n",
               "nodomain.freeyourgadget.gadgetbridge/.service.devices.samsung.gear.sap.SapCalendarEmptyMirrorBootstrapReceiver",
               "-a", "nodomain.freeyourgadget.gadgetbridge.debug.BOOTSTRAP_GEAR_S3_EMPTY_CALENDAR_MIRROR",
               "--es", "device", args.device.upper(), "--es", "challenge", args.challenge,
               "--es", "watch_database_sha256", digest, "--ei", "watch_count", "0",
               "--el", "watch_sequence", "0", "--el", "verified_at_ms", str(int(time.time() * 1000))]
    print("VERIFIED empty-watch count=0 reminders=0 sequence=0 database_sha256=" + digest)
    print(shlex.join(command))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, sqlite3.Error):
        print("REFUSED empty-watch verification failed; no Calendar traffic sent", file=sys.stderr)
        sys.exit(2)
