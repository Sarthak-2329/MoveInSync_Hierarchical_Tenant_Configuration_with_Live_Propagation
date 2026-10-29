package com.moveinsync.config.server.dto;

import com.moveinsync.config.core.model.ConfigDelta;

public record RollbackRequest(String author, String reason) {}
