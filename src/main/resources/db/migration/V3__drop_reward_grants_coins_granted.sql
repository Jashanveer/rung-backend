-- Remove coins_granted column that was dropped from the RewardGrant entity
ALTER TABLE reward_grants DROP COLUMN IF EXISTS coins_granted;
