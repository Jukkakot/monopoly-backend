package fi.monopoly.server.session;

import fi.monopoly.domain.session.SessionStatus;

import java.util.List;

public record SessionSummary(String sessionId, List<String> playerNames, SessionStatus status) {}
