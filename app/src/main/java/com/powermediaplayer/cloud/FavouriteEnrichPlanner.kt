package com.powermediaplayer.cloud

/** #16 — how to enrich a just-favourited Drive item. */
sealed interface EnrichPlan {
    object Skip : EnrichPlan
    object FromDownload : EnrichPlan
    data class FromLocal(val path: String) : EnrichPlan
}

/**
 * #16 D6 — decide how to enrich a just-favourited Drive item. Dedup + an
 * already-enriched check skip needless work; an existing durable offline copy is
 * extracted in place (no re-download). No size/network gate (user directive).
 */
object FavouriteEnrichPlanner {
    fun plan(alreadyEnriched: Boolean, offlineLocalPath: String?, inFlight: Boolean): EnrichPlan =
        when {
            alreadyEnriched || inFlight -> EnrichPlan.Skip
            offlineLocalPath != null -> EnrichPlan.FromLocal(offlineLocalPath)
            else -> EnrichPlan.FromDownload
        }
}
