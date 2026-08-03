-- Bar length is a property of the uploaded file, not of the system: the same symbol can be
-- uploaded again as 1-minute bars in a separate dataset/group. Everything that talks about "봉"
-- (maxHoldBars, the moving averages, the LLM prompt, the UI copy) means a different amount of
-- time depending on this. The engine itself stays interval-agnostic — it only counts bars.
--
-- Uploads infer this from the parsed timestamps (BarSeries.inferIntervalMinutes); every dataset
-- uploaded before this migration was 3-minute data, which is also the column default.
ALTER TABLE dataset ADD COLUMN bar_interval_minutes INT NOT NULL DEFAULT 3 AFTER bar_count;
