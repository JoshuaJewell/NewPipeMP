# Retag existing library

In **Settings → Downloads → Retag existing library**:

1. Stop playback and downloads. In particular, pause Download library.
2. Choose playlists. **Checked playlists are excluded** from supplying album tags.
   Exclude broad collections, favourites, and other catch-all lists.
3. Inspect **Preview album changes**. Each proposed file shows its new album and
   playlist position. The preview also lists skipped tracks with reasons.
4. Apply. Refresh VLC's library afterwards if it retains the old metadata.

The selected exclusions are remembered. Each file is considered once. A track must
belong to exactly one eligible playlist; multiple eligible playlists are ambiguous,
including different playlists with identical names. Ambiguous tracks, tracks without
an eligible playlist, missing files, and files matched to multiple track identities
are left unchanged. Repeats within one playlist use the first position. All bookmarked
playlists must load successfully before proceeding, to avoid treating incomplete
membership as unambiguous.

Retagging uses available offline mappings and conservative filename matching in the
configured audio folder, without the downloader's loose substring/suffix fallback.
Review proposed file URIs before applying. This is not an album lookup service: the
chosen playlist's name becomes Album and its order supplies Track number.

Supported containers: MP3, M4A, FLAC and Opus. Only album, track number, and obsolete
track-total fields are modified. Other tags, ratings, artwork and source links are
preserved. No audio is downloaded, re-encoded or moved; M3U playlists are unchanged.
Unsupported containers are reported, not rewritten. Already-correct files are not
rewritten. Direct-file modification time is restored when possible; document providers
may update it. Provider-side modification times are not generally controllable.

## Recovery and storage

Keep a separate backup if you want to undo completed album changes. The recovery
mechanism is for interrupted writes, **not a permanent backup of all original tags**.

Each edit is staged in internal app storage, with a byte-for-byte original backup.
Allow free internal space for approximately twice the largest individual audio file.
The proposed tags are read back before the destination is touched. The original is
checked for concurrent modification, then a durable journal is published. After
writing, the entire destination is read back and SHA-256 checked against the staged
file. Only then is the original backup removed. A failed provider write restores and
verifies the original. A failed restoration aborts the job and retains the journal
and backup, rather than continuing to another file.

If Android kills the app during a write, open **Retag existing library** again to
restore the interrupted file before planning another operation. Do not clear app data
or uninstall while a recovery copy is needed. Recovery requires access to the original
folder; restore its permission if necessary. Successfully completed earlier files
remain retagged. A completed job's report is available from **Last report**, including
when the settings screen was recreated during work.

The job runs sequentially on an IO scheduler. Keep the app open; it is not a foreground
service and Android may stop it in the background. Download library is prevented from
starting while retagging or recovery is active. Other players/download clients must
be stopped by the user; concurrent writes cannot be made safe across independent apps.

## Regression coverage

- Playlist exclusions, ambiguous membership, repeated tracks, same-name playlists,
  missing files and shared file URIs.
- Tagging failure, provider truncation/write failure, recovery after interruption,
  retained backups on recovery failure, completed-journal cleanup and concurrent edits.
- Conservative matching excludes loose filename suffix matches.
- Album/rating/title/artist/source round trips for MP3, M4A, FLAC and Opus; Opus
  additionally covers preservation of multi-page embedded artwork and unrelated comments.
