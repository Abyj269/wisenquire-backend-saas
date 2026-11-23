package com.leads.model.enums;

/** Only two roles are needed across every feature (suggest/improve/translate/score/summarize) — none of them do tool-calling. */
public enum AiChatRole {
    USER,
    ASSISTANT
}
