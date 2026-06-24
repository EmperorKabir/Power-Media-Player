package com.powermediaplayer.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

/** #16 — favourite-enrich decision (dedup / offline-reuse / skip-if-enriched). */
class FavouriteEnrichPlannerTest {
    @Test fun alreadyEnriched_skips() = assertEquals(
        EnrichPlan.Skip,
        FavouriteEnrichPlanner.plan(alreadyEnriched = true, offlineLocalPath = null, inFlight = false)
    )

    @Test fun notEnriched_noOffline_downloads() = assertEquals(
        EnrichPlan.FromDownload,
        FavouriteEnrichPlanner.plan(alreadyEnriched = false, offlineLocalPath = null, inFlight = false)
    )

    @Test fun notEnriched_withOffline_reusesLocal() = assertEquals(
        EnrichPlan.FromLocal("/data/offline/x.m4b"),
        FavouriteEnrichPlanner.plan(alreadyEnriched = false, offlineLocalPath = "/data/offline/x.m4b", inFlight = false)
    )

    @Test fun inFlight_skips_evenIfNotEnriched() = assertEquals(
        EnrichPlan.Skip,
        FavouriteEnrichPlanner.plan(alreadyEnriched = false, offlineLocalPath = null, inFlight = true)
    )
}
