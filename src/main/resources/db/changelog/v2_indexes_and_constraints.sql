-- liquibase formatted sql

-- changeset groombook:8 labels:v2 comment:Индексы для clients
CREATE INDEX idx_clients_telegram_id ON clients (telegram_id);
CREATE INDEX idx_clients_phone       ON clients (phone);
CREATE INDEX idx_clients_status      ON clients (status);

-- changeset groombook:9 labels:v2 comment:Индексы для pets
CREATE INDEX idx_pets_client_id        ON pets (client_id);
CREATE INDEX idx_pets_client_active    ON pets (client_id, is_active);

-- changeset groombook:10 labels:v2 comment:Индексы для time_slots
CREATE INDEX idx_slots_date            ON time_slots (date);
CREATE INDEX idx_slots_date_status     ON time_slots (date, status);
CREATE INDEX idx_slots_status          ON time_slots (status);

-- changeset groombook:11 labels:v2 comment:Индексы для bookings
CREATE INDEX idx_bookings_client_id    ON bookings (client_id);
CREATE INDEX idx_bookings_slot_id      ON bookings (slot_id);
CREATE INDEX idx_bookings_status       ON bookings (status);
CREATE INDEX idx_bookings_created_at   ON bookings (created_at);
CREATE INDEX idx_bookings_pet_id       ON bookings (pet_id);

-- changeset groombook:12 labels:v2 comment:Partial unique index - один активный шаблон
-- Гарантирует что только один шаблон может быть активным одновременно на уровне БД
CREATE UNIQUE INDEX idx_one_active_template
    ON schedule_templates (is_active)
    WHERE is_active = TRUE;

-- changeset groombook:13 labels:v2 comment:Partial unique index - одна активная бронь на слот
-- Гарантирует что один слот не может быть забронирован дважды активными бронями
CREATE UNIQUE INDEX idx_one_active_booking_per_slot
    ON bookings (slot_id)
    WHERE status IN ('PENDING', 'CONFIRMED');

-- changeset groombook:14 labels:v2 comment:Индекс для day_overrides
CREATE INDEX idx_overrides_date ON day_overrides (date);

-- changeset groombook:15 endDelimiter:\n--SQL labels:v2 comment:Функция автообновления updated_at и триггер для clients
CREATE OR REPLACE FUNCTION update_updated_at_column()
    RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_clients_updated_at
    BEFORE UPDATE ON clients
    FOR EACH ROW
EXECUTE FUNCTION update_updated_at_column();
--SQL
