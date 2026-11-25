-- Seed data (adapted from Addendum A). active_order_count matches the orders below.
INSERT INTO agents (id, name, active_order_count, status, version) VALUES
  ('AGT-001', 'Priya Sharma', 3, 'BUSY',      0),
  ('AGT-002', 'Rahul Verma',  0, 'AVAILABLE', 0),
  ('AGT-003', 'Ananya Iyer',  2, 'BUSY',      0),
  ('AGT-004', 'Kiran Nair',   0, 'AVAILABLE', 0),
  ('AGT-005', 'Deepak Mehta', 3, 'BUSY',      0);

INSERT INTO orders (id, description, assigned_agent_id, status, created_at, version) VALUES
  ('ORD-001', 'Electronics - Koramangala to Indiranagar', 'AGT-001', 'ASSIGNED', CURRENT_TIMESTAMP, 0),
  ('ORD-002', 'Groceries - HSR Layout to BTM',            'AGT-001', 'ASSIGNED', CURRENT_TIMESTAMP, 0),
  ('ORD-003', 'Pharma - Whitefield to Marathahalli',      'AGT-003', 'ASSIGNED', CURRENT_TIMESTAMP, 0),
  ('ORD-004', 'Documents - MG Road to Jayanagar',         'AGT-005', 'ASSIGNED', CURRENT_TIMESTAMP, 0),
  ('ORD-005', 'Food - Bellandur to Electronic City',      'AGT-005', 'ASSIGNED', CURRENT_TIMESTAMP, 0),
  ('ORD-006', 'Apparel - Malleshwaram to Rajajinagar',    'AGT-005', 'ASSIGNED', CURRENT_TIMESTAMP, 0),
  ('ORD-007', 'Books - Banashankari to JP Nagar',         'AGT-003', 'ASSIGNED', CURRENT_TIMESTAMP, 0),
  ('ORD-008', 'Hardware - Peenya to Yeshwanthpur',        'AGT-001', 'ASSIGNED', CURRENT_TIMESTAMP, 0);
