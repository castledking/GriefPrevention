package com.griefprevention.persistence;

import org.jetbrains.annotations.ApiStatus;

/**
 * Shared GriefPrevention datastore schema constants.
 */
@ApiStatus.Internal
public final class ClaimDataSchema
{
    /** Matches the current upstream flat-file/database schema version. */
    public static final int CURRENT_VERSION = 14;

    private ClaimDataSchema()
    {
    }
}
