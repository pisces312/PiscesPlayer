import subprocess
from pathlib import Path

adb = r"D:/dev/android_sdk/platform-tools/adb.exe"
pkg = "com.pisces312.piscesplayer.debug"
out_dir = Path(r"D:\Temp\pulled_db")
out_dir.mkdir(exist_ok=True)

for name in ("pixelplayer_database", "pixelplayer_database-wal", "pixelplayer_database-shm"):
    dest = out_dir / name
    data = subprocess.check_output(
        [adb, "exec-out", f"run-as {pkg} cat databases/{name}"],
        stderr=subprocess.DEVNULL,
    )
    dest.write_bytes(data)
    print(name, len(data))

import sqlite3

db = sqlite3.connect(out_dir / "pixelplayer_database")
print("user_version", db.execute("PRAGMA user_version").fetchone()[0])
print("songs cols", [r[1] for r in db.execute("PRAGMA table_info(songs)")])
print("favorites cols", [r[1] for r in db.execute("PRAGMA table_info(favorites)")])
print("lyrics cols", [r[1] for r in db.execute("PRAGMA table_info(lyrics)")])
print("eng cols", [r[1] for r in db.execute("PRAGMA table_info(song_engagements)")])
print("lyrics rows", db.execute("SELECT song_id, content, source FROM lyrics").fetchall())
print("favorites rows", db.execute("SELECT song_id, rating FROM favorites").fetchall())
print(
    "eng rows",
    db.execute("SELECT song_id, play_count, typeof(song_id) FROM song_engagements").fetchall(),
)
print("songs rows", db.execute("SELECT id, title FROM songs").fetchall())
print(
    "ai_cache cols",
    [r[1] for r in db.execute("PRAGMA table_info(ai_cache)")],
)
