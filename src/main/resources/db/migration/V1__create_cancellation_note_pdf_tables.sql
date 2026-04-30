CREATE TABLE cancellation_note_pdf_documents (
    id UUID PRIMARY KEY,
    cancellation_note_id VARCHAR(100) NOT NULL UNIQUE,
    cancellation_note_number VARCHAR(50) NOT NULL,
    document_path VARCHAR(500),
    document_url VARCHAR(1000),
    file_size BIGINT,
    mime_type VARCHAR(100) NOT NULL DEFAULT 'application/pdf',
    xml_embedded BOOLEAN NOT NULL DEFAULT false,
    status VARCHAR(20) NOT NULL,
    error_message TEXT,
    retry_count INTEGER DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP
);

CREATE INDEX idx_cn_pdf_cancellation_note_id ON cancellation_note_pdf_documents(cancellation_note_id);
CREATE INDEX idx_cn_pdf_cancellation_note_number ON cancellation_note_pdf_documents(cancellation_note_number);
CREATE INDEX idx_cn_pdf_status ON cancellation_note_pdf_documents(status);

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    topic VARCHAR(255),
    partition_key VARCHAR(255),
    headers TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INTEGER DEFAULT 0,
    error_message VARCHAR(1000),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP
);

CREATE INDEX idx_outbox_aggregate ON outbox_events(aggregate_id, aggregate_type);
CREATE INDEX idx_outbox_pending_created ON outbox_events(status, created_at)
    WHERE status = 'PENDING';
