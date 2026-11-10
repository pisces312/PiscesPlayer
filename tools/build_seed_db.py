"""Build a Room v14 (or v13) seed database for upgrade smoke testing."""
import json
import sqlite3
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "app" / "schemas" / "com.pisces312.piscesplayer.data.database.PiscesPlayerDatabase"


def build(version: int, out_path: Path) -> None:
    schema = json.loads((BASE / f"{version}.json").read_text(encoding="utf-8"))
    if out_path.exists():
        out_path.unlink()
    conn = sqlite3.connect(out_path)
    conn.execute("PRAGMA foreign_keys=OFF")
    for entity in schema["database"]["entities"]:
        sql = entity["createSql"].replace("${TABLE_NAME}", entity["tableName"])
        conn.execute(sql)
        for ix in entity.get("indices") or []:
            conn.execute(ix["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
        for trigger in entity.get("triggers") or []:
            conn.execute(trigger["createSql"].replace("${TABLE_NAME}", entity["tableName"]))

    # Seed rows covering: favorites, lyrics backfill source, TEXT song_id tables, camelCase cols.
    conn.execute(
        "INSERT INTO songs (id, title, artist_name, artist_id, album_name, album_id, "
        "content_uri_string, duration, file_path, parent_directory_path, is_favorite, lyrics) "
        "VALUES (1, 'LocalOne', 'ArtistA', 1, 'AlbumA', 1, 'content://1', 100, '/a/1.mp3', '/a', 1, 'embedded-body')"
    )
    conn.execute(
        "INSERT INTO songs (id, title, artist_name, artist_id, album_name, album_id, "
        "content_uri_string, duration, file_path, parent_directory_path, is_favorite, lyrics) "
        "VALUES (2, 'LocalTwo', 'ArtistA', 1, 'AlbumA', 1, 'content://2', 100, '/a/2.mp3', '/a', 0, NULL)"
    )
    conn.execute(
        "INSERT INTO songs (id, title, artist_name, artist_id, album_name, album_id, "
        "content_uri_string, duration, file_path, parent_directory_path, source_type) "
        "VALUES (-9000000000001, 'CloudOne', 'CloudArtist', -11000000000001, 'CloudAlbum', "
        "-10000000000001, 'navidrome://ext-1', 200, '/srv/1', '/srv', 5)"
    )
    conn.execute("INSERT INTO favorites (songId, isFavorite, timestamp, rating) VALUES (1, 1, 11, 5)")
    conn.execute("INSERT INTO favorites (songId, isFavorite, timestamp, rating) VALUES (-9000000000001, 1, 12, 4)")
    conn.execute("INSERT INTO lyrics (songId, content, isSynced, source) VALUES (2, 'manual', 1, 'manual')")
    conn.execute(
        "INSERT INTO song_engagements (song_id, play_count, total_play_duration_ms, last_played_timestamp) "
        "VALUES ('1', 3, 3000, 111)"
    )
    conn.execute("INSERT INTO playlist_songs (playlist_id, song_id, sort_order) VALUES ('p1', '1', 0)")
    conn.execute(
        "INSERT INTO audio_bookmarks (id, song_id, song_title, artist_name, album_art_uri, title, timestamp_ms, created_time) "
        "VALUES (1, '1', 'LocalOne', 'ArtistA', NULL, 'mark', 50, 1)"
    )
    conn.execute(
        "INSERT INTO offline_tracks (download_id, attempt_id, song_id, source_uri, provider, title, mime_type, "
        "local_path, state, bytes_downloaded, total_bytes, created_at, updated_at, error_message) "
        "VALUES ('d1', 'a1', '1', 'navidrome://1', 'navidrome', 'T', 'audio/mpeg', '/off/1', 'done', 1, 1, 1, 1, NULL)"
    )
    conn.execute("INSERT INTO ai_cache (promptHash, responseJson, timestamp) VALUES ('h1', '{}', 1)")
    conn.execute(
        "INSERT INTO ai_usage (id, timestamp, provider, model, promptType, promptTokens, outputTokens, thoughtTokens) "
        "VALUES (1, 1, 'p', 'm', 'mix', 10, 20, 0)"
    )
    conn.execute(f"PRAGMA user_version={version}")
    conn.commit()
    conn.close()
    print(f"wrote {out_path} (user_version={version})")


if __name__ == "__main__":
    ver = int(sys.argv[1])
    out = Path(sys.argv[2])
    build(ver, out)
