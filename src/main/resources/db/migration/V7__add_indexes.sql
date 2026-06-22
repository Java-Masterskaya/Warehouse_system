-- Фильтр и поиск по товарам
CREATE INDEX idx_items_category ON items(category) WHERE is_active = TRUE;
CREATE INDEX idx_items_name_lower ON items(lower(name)) WHERE is_active = TRUE;

-- История движений (частый запрос по item_id + дата)
CREATE INDEX idx_movements_item_id_created ON stock_movements(item_id, created_at DESC);

-- Алерты по item_id
CREATE INDEX idx_stock_alerts_item_id ON stock_alerts(item_id);

-- Логин: поиск по username
CREATE INDEX idx_users_username ON users(username) WHERE is_active = TRUE;