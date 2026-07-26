# Beatmap Submission System (BSS)

In-game beatmap uploading for bancho.jar, built on top of the
[`io.github.7mochi:osz2`](https://github.com/7mochi/osz2.jar) library.

## Endpoints

All of them live on the `osu.` host, next to the existing `/web/` handlers.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/web/osu-osz2-bmsubmit-getid.php` | Reserves the set id and the difficulty ids, reports the remaining quota and whether a full upload is required. |
| POST | `/web/osu-osz2-bmsubmit-upload.php` | Receives the osz2 package (`t=1`) or a bsdiff patch (`t=0`). |
| POST | `/web/osu-osz2-bmsubmit-post.php` | Stores the description typed in the editor. |
| GET | `/web/osu-osz2-bmsubmit-getfile.php` | Returns the stored package so the editor can build a patch. |
| GET | `/web/osu-get-beatmap-topic.php` | Returns the stored description. |

## How it avoids clashing with the mirror

Everything submitted locally is allocated an id **at or above `BSS_ID_OFFSET`**
(one billion by default), far beyond any official osu! id. That single rule is
what separates the two worlds:

- `/d/{id}` streams the set from disk **only** when the id is in the local
  range *and* an active row exists in `bss_mapsets`. Every other id keeps the
  original redirect to `DIRECT_DL`, unchanged.
- `/d/{id}n` (no-video) works the same way and falls back to the other variant
  if only one was generated.
- `OsuMapDownloader` refuses to request local ids from `osu.ppy.sh`, since
  those maps exist nowhere else.
- osu!direct prepends local sets to the first page of results and leaves the
  mirror results untouched.

## Storage layout

```
data/osz2/{setId}.osz2     canonical package, also the patch base
data/osz/{setId}.osz       download with video
data/osz/{setId}n.osz      download without video
data/maps/{beatmapId}.osu  extracted difficulty, reused by scoring and pp
```

## Setup

1. Apply `sql/bss.sql` to the database.
2. Add the new variables to `.env` (see `.env.example`):
   `BSS_ENABLED`, `BSS_ID_OFFSET`, `BSS_MAX_PENDING_SETS`, `BSS_MAX_UPLOAD_SIZE_MB`.
3. Keep the nginx `client_max_body_size` at or above `BSS_MAX_UPLOAD_SIZE_MB`
   (it is raised to `120M` in `nginx/bancho.conf`).

## Notes

- Star ratings of submitted difficulties are written as `0` and left to the
  existing recalculation pipeline, which is the only component that owns
  difficulty values.
- Max combo is approximated from the object counts in the `.osu` file
  (sliders counted as two).
- The numeric result codes of the legacy submission protocol were never
  published by ppy. They are collected in `BssStatusCode` so a single edit is
  enough to re-tune them for a specific client build.
