-- liquibase formatted sql

-- changeset groombook:1 labels:v1 comment:Создание таблицы clients
CREATE TABLE clients
(
    id             BIGSERIAL PRIMARY KEY,
    telegram_id    BIGINT       NOT NULL,
    name           VARCHAR(100) NOT NULL,
    phone          VARCHAR(20)  NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    status_reason  TEXT,
    no_show_count  INT          NOT NULL DEFAULT 0,
    created_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_clients_telegram_id UNIQUE (telegram_id),
    CONSTRAINT uq_clients_phone       UNIQUE (phone),
    CONSTRAINT chk_clients_status CHECK (status IN ('ACTIVE', 'FLAGGED', 'REQUIRES_APPROVAL', 'BLOCKED'))
);

-- changeset groombook:2 labels:v1 comment:Создание таблицы pets
CREATE TABLE pets
(
    id               BIGSERIAL PRIMARY KEY,
    client_id        BIGINT       NOT NULL REFERENCES clients (id),
    name             VARCHAR(100) NOT NULL,
    type             VARCHAR(20)  NOT NULL,
    breed            VARCHAR(100),
    difficulty       VARCHAR(20)  NOT NULL DEFAULT 'EASY',
    difficulty_note  TEXT,
    is_active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_pets_type       CHECK (type IN ('DOG', 'CAT', 'OTHER')),
    CONSTRAINT chk_pets_difficulty CHECK (difficulty IN ('EASY', 'MEDIUM', 'HARD', 'REFUSED'))
);

-- changeset groombook:3 labels:v1 comment:Создание таблицы schedule_templates
CREATE TABLE schedule_templates
(
    id                   BIGSERIAL PRIMARY KEY,
    name                 VARCHAR(100) NOT NULL,
    is_active            BOOLEAN      NOT NULL DEFAULT FALSE,
    active_from          DATE,
    active_until         DATE,
    slot_duration_hours  SMALLINT     NOT NULL DEFAULT 2,
    created_at           TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_slot_duration CHECK (slot_duration_hours IN (1, 2, 3))
);

-- changeset groombook:4 labels:v1 comment:Создание таблицы template_days
CREATE TABLE template_days
(
    id           BIGSERIAL PRIMARY KEY,
    template_id  BIGINT   NOT NULL REFERENCES schedule_templates (id) ON DELETE CASCADE,
    day_of_week  SMALLINT NOT NULL,
    is_working   BOOLEAN  NOT NULL DEFAULT TRUE,
    start_time   TIME,
    end_time     TIME,

    CONSTRAINT uq_template_days        UNIQUE (template_id, day_of_week),
    CONSTRAINT chk_day_of_week         CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT chk_working_day_times   CHECK (
        (is_working = FALSE) OR
        (is_working = TRUE AND start_time IS NOT NULL AND end_time IS NOT NULL)
        )
);

-- changeset groombook:5 labels:v1 comment:Создание таблицы day_overrides
CREATE TABLE day_overrides
(
    id             BIGSERIAL PRIMARY KEY,
    date           DATE         NOT NULL,
    override_type  VARCHAR(20)  NOT NULL,
    is_working     BOOLEAN      NOT NULL,
    start_time     TIME,
    end_time       TIME,
    reason         VARCHAR(200),

    CONSTRAINT uq_day_overrides_date  UNIQUE (date),
    CONSTRAINT chk_override_type      CHECK (override_type IN
                                             ('HOLIDAY', 'VACATION', 'CUSTOM_HOURS', 'EXTRA_WORKING_DAY'))
);

-- changeset groombook:6 labels:v1 comment:Создание таблицы time_slots
CREATE TABLE time_slots
(
    id            BIGSERIAL PRIMARY KEY,
    date          DATE        NOT NULL,
    start_time    TIME        NOT NULL,
    end_time      TIME        NOT NULL,
    status        VARCHAR(20) NOT NULL DEFAULT 'FREE',
    block_reason  VARCHAR(200),
    is_manual     BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMP   NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_time_slots_date_start  UNIQUE (date, start_time),
    CONSTRAINT chk_slot_status           CHECK (status IN ('FREE', 'BOOKED', 'BLOCKED', 'MANUAL_BOOKING')),
    CONSTRAINT chk_slot_times            CHECK (end_time > start_time)
);

-- changeset groombook:7 labels:v1 comment:Создание таблицы bookings
CREATE TABLE bookings
(
    id              BIGSERIAL PRIMARY KEY,
    slot_id         BIGINT      NOT NULL REFERENCES time_slots (id),
    client_id       BIGINT      NOT NULL REFERENCES clients (id),
    pet_id          BIGINT      NOT NULL REFERENCES pets (id),
    booking_type    VARCHAR(20) NOT NULL DEFAULT 'STANDARD',
    status          VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    client_comment  TEXT,
    master_note     TEXT,
    no_show         BOOLEAN     NOT NULL DEFAULT FALSE,
    gcal_event_id   VARCHAR(200),
    created_at      TIMESTAMP   NOT NULL DEFAULT NOW(),
    confirmed_at    TIMESTAMP,
    completed_at    TIMESTAMP,

    CONSTRAINT chk_booking_type   CHECK (booking_type IN ('STANDARD', 'MANUAL')),
    CONSTRAINT chk_booking_status CHECK (status IN (
                                                    'PENDING', 'CONFIRMED', 'COMPLETED', 'NO_SHOW',
                                                    'CANCELLED_BY_CLIENT', 'CANCELLED_BY_MASTER'
        ))
);
