CREATE TABLE trade_assets(trade_id TEXT NOT NULL REFERENCES trades(id), player TEXT NOT NULL, asset TEXT NOT NULL, quantity INTEGER NOT NULL CHECK(quantity>0), PRIMARY KEY(trade_id,player,asset));
CREATE TABLE item_escrow(id TEXT PRIMARY KEY, trade_id TEXT NOT NULL REFERENCES trades(id), owner TEXT NOT NULL, recipient TEXT, epoch INTEGER NOT NULL, item BLOB NOT NULL, state TEXT NOT NULL);
CREATE INDEX escrow_recipient ON item_escrow(recipient,state,epoch);
ALTER TABLE trades ADD COLUMN revision INTEGER NOT NULL DEFAULT 0;
