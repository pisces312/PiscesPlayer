package com.pisces312.piscesplayer.data.midilibrary

/**
 * Two-level shadow matching between user MIDI files and built-in tracks (plan §2-E):
 *
 * - **L1**: identical file SHA-256 → same file;
 * - **L2**: identical [MidiFingerprint] music hash → same music, metadata-only difference.
 *
 * A matched built-in is "shadowed" by the user's copy: hidden where the MIDI library lists
 * built-ins, played from the user's file elsewhere, with built-in display metadata kept.
 * Pure function over hash maps so it is unit-testable; see [MidiLibraryRepository] for the
 * maps' provenance (index `sha256` + runtime-computed music hashes).
 */
object MidiShadowMatcher {

    /**
     * Returns built-in track id → winning user entry. L1 matches win over L2; when several
     * user files match the same built-in, the first in [files] wins (a false negative for
     * the others — safe).
     */
    fun match(
        files: List<MidiFileEntry>,
        builtInFileHashes: Map<Long, String>,
        builtInMusicHashes: Map<Long, String>
    ): Map<Long, MidiFileEntry> {
        if (files.isEmpty() || (builtInFileHashes.isEmpty() && builtInMusicHashes.isEmpty())) {
            return emptyMap()
        }
        val byFileHash = HashMap<String, Long>(builtInFileHashes.size)
        builtInFileHashes.forEach { (id, hash) ->
            if (hash.isNotBlank()) byFileHash.putIfAbsent(hash.lowercase(), id)
        }
        val byMusicHash = HashMap<String, Long>(builtInMusicHashes.size)
        builtInMusicHashes.forEach { (id, hash) ->
            if (hash.isNotBlank()) byMusicHash.putIfAbsent(hash, id)
        }

        val result = LinkedHashMap<Long, MidiFileEntry>()
        val pending = ArrayList<MidiFileEntry>()
        for (f in files) {
            val l1 = byFileHash[f.fileSha256.lowercase()]
            if (l1 != null) {
                if (l1 !in result) result[l1] = f
            } else {
                pending += f
            }
        }
        for (f in pending) {
            val musicHash = f.musicHash ?: continue
            val l2 = byMusicHash[musicHash] ?: continue
            if (l2 !in result) result[l2] = f
        }
        return result
    }
}
