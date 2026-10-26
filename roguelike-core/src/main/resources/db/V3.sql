CREATE TABLE leaderboard_snapshots(period TEXT NOT NULL, scope TEXT NOT NULL, metric TEXT NOT NULL, player TEXT NOT NULL, value INTEGER NOT NULL, captured INTEGER NOT NULL, PRIMARY KEY(period,scope,metric,player));
CREATE INDEX statistics_ranking ON statistics(epoch,metric,day);
CREATE INDEX runs_owner_history ON runs(player,epoch,ended);
CREATE INDEX market_history_asset ON market_history(epoch,asset,created);
