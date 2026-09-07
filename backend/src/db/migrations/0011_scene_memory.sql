ALTER TABLE conversation_memories ADD COLUMN mid_term TEXT NOT NULL DEFAULT '';
ALTER TABLE conversation_memories ADD COLUMN scene_state TEXT;
ALTER TABLE conversation_memories ADD COLUMN emotion_state TEXT;
ALTER TABLE conversation_memories ADD COLUMN personality_state TEXT;
ALTER TABLE conversation_memories ADD COLUMN invalidated_from_position INTEGER;
ALTER TABLE conversation_memories ADD COLUMN psychology_state TEXT;
