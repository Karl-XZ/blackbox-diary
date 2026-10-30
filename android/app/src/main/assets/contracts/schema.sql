-- BlackBox Diary V1.1: core relational contract, schema user_version = 1.
-- PRODUCTION: initialize a keyed SQLCipher connection and verify cipher_version
-- before executing any schema or data SQL. Plain sqlite3 is for synthetic tests only.
-- User text must never be written to a plaintext production database.
PRAGMA foreign_keys = ON;
PRAGMA temp_store = MEMORY;
PRAGMA secure_delete = ON;

CREATE TABLE app_meta (
  singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
  content_epoch INTEGER NOT NULL DEFAULT 1 CHECK (content_epoch > 0),
  db_generation INTEGER NOT NULL DEFAULT 1 CHECK (db_generation > 0),
  cache_generation INTEGER NOT NULL DEFAULT 1 CHECK (cache_generation > 0),
  ingest_seq INTEGER NOT NULL DEFAULT 0,
  maintenance_state TEXT NOT NULL DEFAULT 'NORMAL'
    CHECK (maintenance_state IN ('NORMAL','DELETE_PENDING','REBUILDING','KEY_DESTROY_PENDING')),
  pipeline_version TEXT NOT NULL DEFAULT '1.1'
);
INSERT INTO app_meta(singleton) VALUES(1);

CREATE TABLE clock_anchor (
  id TEXT PRIMARY KEY,
  boot_id TEXT NOT NULL,
  elapsed_ns INTEGER NOT NULL CHECK (elapsed_ns >= 0),
  utc_ms INTEGER NOT NULL,
  zone_id TEXT NOT NULL,
  quality TEXT NOT NULL CHECK (quality IN ('VALID','CLOCK_CHANGED','UNCERTAIN'))
);

CREATE TABLE diary_day (
  id TEXT PRIMARY KEY,
  local_date TEXT NOT NULL UNIQUE,
  has_capture_gap INTEGER NOT NULL DEFAULT 0 CHECK (has_capture_gap IN (0,1)),
  has_processing_failure INTEGER NOT NULL DEFAULT 0 CHECK (has_processing_failure IN (0,1)),
  has_pending_processing INTEGER NOT NULL DEFAULT 0 CHECK (has_pending_processing IN (0,1)),
  used_fallback INTEGER NOT NULL DEFAULT 0 CHECK (used_fallback IN (0,1))
);

CREATE TABLE day_slice (
  id TEXT PRIMARY KEY,
  day_id TEXT NOT NULL REFERENCES diary_day(id) ON DELETE CASCADE,
  zone_id TEXT NOT NULL,
  start_utc_ms INTEGER NOT NULL,
  end_utc_ms INTEGER NOT NULL CHECK (end_utc_ms > start_utc_ms),
  anchor_id TEXT NOT NULL REFERENCES clock_anchor(id),
  UNIQUE(day_id, zone_id, start_utc_ms)
);

CREATE TABLE capture_intent (
  id TEXT PRIMARY KEY,
  channel TEXT NOT NULL CHECK (channel IN ('MICROPHONE','SCREEN')),
  start_utc_ms INTEGER NOT NULL,
  end_utc_ms INTEGER CHECK (end_utc_ms IS NULL OR end_utc_ms >= start_utc_ms),
  enabled_by TEXT NOT NULL CHECK (enabled_by IN ('USER','USER_SCHEDULE'))
);

CREATE TABLE capture_exclusion (
  id TEXT PRIMARY KEY,
  intent_id TEXT NOT NULL REFERENCES capture_intent(id) ON DELETE CASCADE,
  start_utc_ms INTEGER NOT NULL,
  end_utc_ms INTEGER NOT NULL CHECK (end_utc_ms > start_utc_ms),
  reason TEXT NOT NULL CHECK (reason IN ('USER_PAUSE','TIMED_PAUSE','SCHEDULED_EXCLUSION','OWN_APP_PRIVACY'))
);

CREATE TABLE capture_session (
  id TEXT PRIMARY KEY,
  intent_id TEXT NOT NULL REFERENCES capture_intent(id),
  anchor_id TEXT NOT NULL REFERENCES clock_anchor(id),
  start_utc_ms INTEGER NOT NULL,
  end_utc_ms INTEGER CHECK (end_utc_ms IS NULL OR end_utc_ms >= start_utc_ms),
  state TEXT NOT NULL,
  stop_reason TEXT,
  last_confirmed_elapsed_ns INTEGER NOT NULL
);

CREATE TABLE capture_tick (
  id TEXT PRIMARY KEY,
  session_id TEXT NOT NULL REFERENCES capture_session(id) ON DELETE CASCADE,
  slice_id TEXT NOT NULL REFERENCES day_slice(id) ON DELETE CASCADE,
  start_utc_ms INTEGER NOT NULL,
  end_utc_ms INTEGER NOT NULL CHECK (end_utc_ms > start_utc_ms),
  elapsed_start_ns INTEGER NOT NULL,
  elapsed_end_ns INTEGER NOT NULL CHECK (elapsed_end_ns > elapsed_start_ns),
  valid_ms INTEGER NOT NULL CHECK (valid_ms >= 0),
  outcome TEXT NOT NULL CHECK (outcome IN (
    'VALID_FRAME','DUPLICATE_FRAME','VALID_PCM','NO_SPEECH',
    'BLOCKED_OR_BLANK','CAPTURE_ERROR','SYSTEM_SILENCED','GAP','UNKNOWN')),
  reason TEXT,
  CHECK (valid_ms <= (elapsed_end_ns - elapsed_start_ns) / 1000000),
  UNIQUE(session_id, slice_id, elapsed_start_ns)
);

CREATE TABLE media_chunk (
  id TEXT PRIMARY KEY,
  session_id TEXT REFERENCES capture_session(id) ON DELETE SET NULL,
  day_id TEXT NOT NULL REFERENCES diary_day(id),
  origin_key TEXT NOT NULL UNIQUE,
  path_token TEXT NOT NULL UNIQUE,
  first_utc_ms INTEGER NOT NULL,
  end_utc_ms INTEGER,
  expires_utc_ms INTEGER NOT NULL,
  created_elapsed_ns INTEGER NOT NULL,
  ttl_ms INTEGER NOT NULL DEFAULT 86400000 CHECK (ttl_ms > 0 AND ttl_ms <= 86400000),
  key_generation INTEGER NOT NULL,
  wrapped_dek BLOB,
  sealed_record_count INTEGER NOT NULL DEFAULT 0,
  byte_count INTEGER NOT NULL DEFAULT 0,
  ciphertext_sha256 TEXT,
  state TEXT NOT NULL CHECK (state IN (
    'WRITING','SEALED','QUEUED','PROCESSING','COMMITTED','DELETE_PENDING',
    'DELETED','FAILED_RETRYABLE','EXPIRED_UNPROCESSED','TOMBSTONED')),
  CHECK (expires_utc_ms > first_utc_ms),
  CHECK (expires_utc_ms <= first_utc_ms + ttl_ms),
  CHECK (wrapped_dek IS NOT NULL OR state IN ('DELETED','EXPIRED_UNPROCESSED','TOMBSTONED'))
);

CREATE TABLE artifact (
  id TEXT PRIMARY KEY,
  kind TEXT NOT NULL CHECK (kind IN (
    'SOURCE_EVENT','SUMMARY','FACT_CLAIM','DIARY_REVISION','DIARY_BLOCK',
    'SEARCH_DOCUMENT','EXPORT_CACHE','VECTOR','QA_CACHE')),
  owner_day_id TEXT REFERENCES diary_day(id),
  revision INTEGER NOT NULL DEFAULT 1 CHECK (revision > 0),
  created_epoch INTEGER NOT NULL,
  ingest_seq INTEGER NOT NULL,
  lifecycle TEXT NOT NULL DEFAULT 'ACTIVE'
    CHECK (lifecycle IN ('ACTIVE','TOMBSTONED','INVALIDATED')),
  created_utc_ms INTEGER NOT NULL
);

CREATE TABLE source_event (
  artifact_id TEXT PRIMARY KEY REFERENCES artifact(id) ON DELETE CASCADE,
  source TEXT NOT NULL CHECK (source IN ('SCREEN','AUDIO','USER_NOTE','SYSTEM')),
  slice_id TEXT NOT NULL REFERENCES day_slice(id),
  origin_chunk_id TEXT REFERENCES media_chunk(id) ON DELETE SET NULL,
  origin_key TEXT NOT NULL UNIQUE,
  observed_start_ms INTEGER NOT NULL,
  observed_end_ms INTEGER NOT NULL CHECK (observed_end_ms >= observed_start_ms),
  text_nfc TEXT NOT NULL,
  text_sha256 TEXT NOT NULL CHECK (length(text_sha256) = 64),
  recognizer_version TEXT,
  raw_score REAL,
  quality_tier TEXT NOT NULL CHECK (quality_tier IN ('HIGH','REVIEW','UNUSABLE')),
  quality_flags_json TEXT NOT NULL DEFAULT '[]',
  calibration_profile_id TEXT,
  metadata_json TEXT NOT NULL DEFAULT '{}'
);

CREATE TABLE lineage_edge (
  parent_id TEXT NOT NULL REFERENCES artifact(id) ON DELETE CASCADE,
  child_id TEXT NOT NULL REFERENCES artifact(id) ON DELETE CASCADE,
  relation TEXT NOT NULL CHECK (relation IN ('DERIVED_FROM','CONTAINS','INDEXES')),
  PRIMARY KEY(parent_id, child_id, relation),
  CHECK (parent_id <> child_id)
);
-- The domain write facade must additionally reject cycles with recursive reachability.

CREATE TABLE fact_claim (
  artifact_id TEXT PRIMARY KEY REFERENCES artifact(id) ON DELETE CASCADE,
  claim_type TEXT NOT NULL CHECK (claim_type IN (
    'SCREEN_TEXT_OBSERVED','AUDIO_TEXT_OBSERVED','USER_NOTE_REPORTED',
    'SYSTEM_GAP_RECORDED','PROCESSING_STATUS')),
  template_id TEXT NOT NULL,
  slot_bindings_json TEXT NOT NULL,
  validation_state TEXT NOT NULL CHECK (validation_state IN ('CANDIDATE','APPROVED','REJECTED')),
  rejection_code TEXT,
  validator_version TEXT NOT NULL,
  observed_start_ms INTEGER,
  observed_end_ms INTEGER
);

CREATE TABLE evidence_ref (
  id TEXT PRIMARY KEY,
  claim_id TEXT NOT NULL REFERENCES fact_claim(artifact_id) ON DELETE CASCADE,
  event_id TEXT NOT NULL REFERENCES source_event(artifact_id),
  event_revision INTEGER NOT NULL,
  start_cp INTEGER NOT NULL CHECK (start_cp >= 0),
  end_cp INTEGER NOT NULL CHECK (end_cp > start_cp),
  quote_nfc TEXT NOT NULL,
  text_sha256 TEXT NOT NULL CHECK (length(text_sha256) = 64),
  UNIQUE(claim_id, event_id, start_cp, end_cp)
);

CREATE TABLE summary_chunk (
  artifact_id TEXT PRIMARY KEY REFERENCES artifact(id) ON DELETE CASCADE,
  level TEXT NOT NULL CHECK (level IN ('WINDOW','PERIOD','CROSS_DAY')),
  range_start_ms INTEGER NOT NULL,
  range_end_ms INTEGER NOT NULL CHECK (range_end_ms > range_start_ms),
  body_text TEXT NOT NULL,
  root_event_ids_json TEXT NOT NULL,
  model_fingerprint TEXT NOT NULL,
  input_fingerprint TEXT NOT NULL UNIQUE
);

CREATE TABLE diary_revision (
  artifact_id TEXT PRIMARY KEY REFERENCES artifact(id) ON DELETE CASCADE,
  day_id TEXT NOT NULL REFERENCES diary_day(id),
  revision_no INTEGER NOT NULL CHECK (revision_no > 0),
  parent_revision_id TEXT REFERENCES diary_revision(artifact_id) ON DELETE SET NULL,
  editor TEXT NOT NULL CHECK (editor IN ('AI','USER','SYSTEM_REBUILD')),
  title_text TEXT NOT NULL,
  state TEXT NOT NULL CHECK (state IN ('CANDIDATE','PUBLISHED','ARCHIVED','INVALIDATED')),
  source_watermark INTEGER NOT NULL,
  used_fallback INTEGER NOT NULL DEFAULT 0,
  UNIQUE(day_id, revision_no)
);
CREATE UNIQUE INDEX one_published_revision_per_day
  ON diary_revision(day_id) WHERE state = 'PUBLISHED';

CREATE TABLE diary_block (
  artifact_id TEXT PRIMARY KEY REFERENCES artifact(id) ON DELETE CASCADE,
  revision_id TEXT NOT NULL REFERENCES diary_revision(artifact_id) ON DELETE CASCADE,
  ordinal INTEGER NOT NULL,
  origin TEXT NOT NULL CHECK (origin IN ('AI_TEMPLATE','USER_EDITED_AI','USER_AUTHORED')),
  user_note_root_id TEXT REFERENCES source_event(artifact_id),
  template_id TEXT,
  body_text TEXT NOT NULL,
  UNIQUE(revision_id, ordinal)
);

CREATE TABLE search_document (
  id INTEGER PRIMARY KEY,
  artifact_id TEXT NOT NULL UNIQUE REFERENCES artifact(id) ON DELETE CASCADE,
  source_artifact_id TEXT NOT NULL REFERENCES artifact(id),
  day_id TEXT NOT NULL REFERENCES diary_day(id),
  source TEXT NOT NULL,
  body_text TEXT NOT NULL,
  folded_text TEXT NOT NULL,
  tokens TEXT NOT NULL,
  offset_map_json TEXT NOT NULL,
  indexed_epoch INTEGER NOT NULL
);
CREATE VIRTUAL TABLE search_fts USING fts5(
  tokens, content='search_document', content_rowid='id', tokenize='unicode61'
);
INSERT INTO search_fts(search_fts, rank) VALUES('secure-delete', 1);
CREATE TRIGGER search_insert AFTER INSERT ON search_document BEGIN
  INSERT INTO search_fts(rowid, tokens) VALUES(new.id, new.tokens);
  INSERT OR IGNORE INTO lineage_edge(parent_id, child_id, relation)
    VALUES(new.source_artifact_id, new.artifact_id, 'INDEXES');
END;
CREATE TRIGGER search_delete AFTER DELETE ON search_document BEGIN
  INSERT INTO search_fts(search_fts, rowid, tokens) VALUES('delete', old.id, old.tokens);
END;
CREATE TRIGGER search_update AFTER UPDATE OF tokens ON search_document BEGIN
  INSERT INTO search_fts(search_fts, rowid, tokens) VALUES('delete', old.id, old.tokens);
  INSERT INTO search_fts(rowid, tokens) VALUES(new.id, new.tokens);
END;
CREATE TRIGGER diary_block_container AFTER INSERT ON diary_block BEGIN
  INSERT OR IGNORE INTO lineage_edge(parent_id, child_id, relation)
    VALUES(new.revision_id, new.artifact_id, 'CONTAINS');
END;

CREATE TABLE processing_job (
  id TEXT PRIMARY KEY,
  kind TEXT NOT NULL,
  expected_epoch INTEGER NOT NULL,
  input_fingerprint TEXT NOT NULL,
  pipeline_version TEXT NOT NULL,
  state TEXT NOT NULL CHECK (state IN (
    'QUEUED','RUNNING','SUCCEEDED','FAILED_RETRYABLE','FAILED_FINAL',
    'CANCELLED','STALE','EXPIRED')),
  attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  lease_until_utc_ms INTEGER,
  next_due_utc_ms INTEGER,
  error_code TEXT,
  UNIQUE(kind, input_fingerprint, expected_epoch, pipeline_version)
);

CREATE TABLE job_input (
  job_id TEXT NOT NULL REFERENCES processing_job(id) ON DELETE CASCADE,
  ordinal INTEGER NOT NULL,
  artifact_id TEXT REFERENCES artifact(id),
  chunk_id TEXT REFERENCES media_chunk(id),
  expected_revision INTEGER,
  PRIMARY KEY(job_id, ordinal),
  CHECK ((artifact_id IS NOT NULL) + (chunk_id IS NOT NULL) = 1)
);

CREATE TABLE deletion_operation (
  id TEXT PRIMARY KEY,
  idempotency_key TEXT NOT NULL UNIQUE,
  requested_utc_ms INTEGER NOT NULL,
  scope_json TEXT NOT NULL,
  cutoff_ingest_seq INTEGER NOT NULL,
  new_epoch INTEGER NOT NULL,
  old_generation INTEGER NOT NULL,
  new_generation INTEGER,
  state TEXT NOT NULL CHECK (state IN (
    'HIDDEN','QUIESCING','PURGING','REBUILDING','VERIFYING',
    'SWITCHED','KEY_DESTROY_PENDING','COMPLETED','BLOCKED')),
  checkpoint_json TEXT NOT NULL DEFAULT '{}',
  error_code TEXT
);

CREATE TABLE privacy_tombstone (
  origin_key TEXT PRIMARY KEY,
  operation_id TEXT NOT NULL REFERENCES deletion_operation(id),
  deleted_epoch INTEGER NOT NULL,
  root_artifact_id TEXT,
  created_utc_ms INTEGER NOT NULL
);

CREATE TABLE event_link (
  event_id TEXT NOT NULL REFERENCES source_event(artifact_id) ON DELETE CASCADE,
  related_event_id TEXT NOT NULL REFERENCES source_event(artifact_id) ON DELETE CASCADE,
  relation TEXT NOT NULL CHECK (relation IN ('TEMPORAL_OVERLAP','USER_LINKED')),
  PRIMARY KEY(event_id, related_event_id, relation),
  CHECK (event_id <> related_event_id)
);

CREATE INDEX artifact_day_lifecycle ON artifact(owner_day_id, lifecycle, ingest_seq);
CREATE INDEX lineage_reverse ON lineage_edge(child_id, parent_id);
CREATE INDEX source_event_observed ON source_event(observed_start_ms, source);
CREATE INDEX capture_tick_slice ON capture_tick(slice_id, start_utc_ms);
CREATE INDEX media_expiry_state ON media_chunk(state, expires_utc_ms);
CREATE INDEX search_day_source ON search_document(day_id, source, id);
CREATE INDEX jobs_due ON processing_job(state, next_due_utc_ms);
CREATE INDEX evidence_event ON evidence_ref(event_id);
PRAGMA user_version = 1;

-- Deletion closure example, bind :root_id, execute inside the maintenance protocol.
-- WITH RECURSIVE affected(id) AS (
--   SELECT :root_id
--   UNION
--   SELECT e.child_id FROM lineage_edge e JOIN affected a ON e.parent_id=a.id
-- ) SELECT id FROM affected;
