package com.moveinsync.config.server.dto;

import com.moveinsync.config.core.model.ConfigDelta;

public record RollbackResponse(
        long newVersion,
        long targetVersion,
        ConfigDelta diff
) {}
