CREATE TABLE customer_codes (
    code text PRIMARY KEY CHECK (length(btrim(code)) > 0 AND code = btrim(code)),
    active boolean NOT NULL,
    version bigint NOT NULL CHECK (version > 0)
);

CREATE TABLE time_entries (
    id uuid PRIMARY KEY,
    work_date date NOT NULL,
    start_time time NOT NULL,
    end_time time NOT NULL,
    deducted_minutes integer NOT NULL,
    customer_code text NOT NULL REFERENCES customer_codes(code),
    comment text NOT NULL,
    version bigint NOT NULL CHECK (version > 0),
    deleted boolean NOT NULL DEFAULT false,
    CHECK (end_time > start_time),
    CHECK (extract(second FROM start_time) = 0 AND extract(second FROM end_time) = 0),
    CHECK (deducted_minutes >= 0 AND deducted_minutes <= extract(epoch FROM (end_time - start_time)) / 60),
    EXCLUDE USING gist (tsrange(work_date + start_time, work_date + end_time, '[)') WITH &&)
        WHERE (NOT deleted)
);
CREATE INDEX time_entries_work_date_idx ON time_entries (work_date, start_time) WHERE NOT deleted;

-- Serialize this single-user service's writes across processes. The lock lasts
-- for the transaction, covering validation and persistence, including empty days.
CREATE TABLE app_write_lock (id integer PRIMARY KEY CHECK (id = 1));
INSERT INTO app_write_lock (id) VALUES (1);
