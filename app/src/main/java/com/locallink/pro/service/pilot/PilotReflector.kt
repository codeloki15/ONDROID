package com.locallink.pro.service.pilot

/**
 * What an action actually did, judged against where the task needs to go.
 *
 * Modelled on Mobile-Agent-v2's three-way reflection verdict. [NO_CHANGE] is decided
 * mechanically by the controller — the screen signature before and after are identical — so a
 * reflector is only ever asked to separate [MATCHED] from [WRONG_PAGE] and never has to return it.
 */
enum class Reflection { MATCHED, WRONG_PAGE, NO_CHANGE }

/**
 * Second opinion on where a step landed.
 *
 * Without this the pilot can only notice a wrong turn once it has repeated itself three times and
 * the stuck guard fires — three model calls after the mistake, on a screen it never wanted. A
 * reflector catches an ad, an interstitial, a sign-in wall or the wrong tab on the step it happens.
 *
 * Advisory by design: the controller treats a failure, a timeout or an unparseable answer as
 * [Reflection.MATCHED], because wrongly rolling back a correct run is worse than missing a wrong one.
 */
fun interface PilotReflector {
    suspend fun reflect(
        task: String,
        /** What was just done, in the controller's own words — e.g. `tapped id 5 (Search)`. */
        actionNote: String,
        before: List<PilotElement>,
        after: List<PilotElement>,
    ): Reflection
}
