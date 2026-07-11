CREATE TABLE account_transactions (
    id UUID PRIMARY KEY,
    type VARCHAR(10) NOT NULL,
    amount NUMERIC(19,2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    source_account_id UUID NULL,
    target_account_id UUID NULL,
    description VARCHAR(255) NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_account_transactions_source_account
        FOREIGN KEY (source_account_id) REFERENCES accounts (id),
    CONSTRAINT fk_account_transactions_target_account
        FOREIGN KEY (target_account_id) REFERENCES accounts (id),
    CONSTRAINT chk_account_transactions_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_account_transactions_amount_scale CHECK (SCALE(amount) <= 2),
    CONSTRAINT chk_account_transactions_currency CHECK (currency IN ('RUB', 'USD', 'EUR')),
    CONSTRAINT chk_account_transactions_type CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER')),
    CONSTRAINT chk_account_transactions_structure CHECK (
        (type = 'DEPOSIT' AND source_account_id IS NULL AND target_account_id IS NOT NULL)
        OR (type = 'WITHDRAWAL' AND source_account_id IS NOT NULL AND target_account_id IS NULL)
        OR (
            type = 'TRANSFER'
            AND source_account_id IS NOT NULL
            AND target_account_id IS NOT NULL
            AND source_account_id <> target_account_id
        )
    )
);

CREATE INDEX idx_account_transactions_target_account_id
    ON account_transactions (target_account_id);
CREATE INDEX idx_account_transactions_source_account_id
    ON account_transactions (source_account_id);
CREATE INDEX idx_account_transactions_created_at
    ON account_transactions (created_at);
