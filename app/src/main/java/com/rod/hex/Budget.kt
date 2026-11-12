package com.rod.hex

import java.time.YearMonth

/**
 * Compteur de dépenses API du mois. Prix en dollars par million de tokens,
 * compté 1 $ = 1 € pour rester prudent.
 */
class Budget(private val prefs: Prefs) {
    private data class Price(val input: Double, val output: Double, val cacheWrite: Double, val cacheRead: Double)

    private val prices = mapOf(
        "claude-haiku-5-5" to Price(0.10, 0.50, 0.125, 0.01),
        "claude-sonnet-5-5" to Price(2.0, 10.0, 2.5, 0.10),
        "claude-opus-5-5" to Price(4.0, 20.0, 5.0, 0.20),
    )
    /** Une recherche web : 10 $ les 1000. */
    private val perSearch = 0.01

    private fun monthKey() = "spend_" + YearMonth.now().toString()
    private fun searchKey() = "searches_" + YearMonth.now().toString()

    val spentEur: Double get() = prefs.raw().getFloat(monthKey(), 0f).toDouble()
    val searchesThisMonth: Int get() = prefs.raw().getInt(searchKey(), 0)
    val blocked: Boolean get() = spentEur >= prefs.budgetCapEur
    val nearCap: Boolean get() = spentEur >= prefs.budgetCapEur * 0.8

    @Synchronized
    fun record(model: String, input: Long, output: Long, cacheWrite: Long, cacheRead: Long, searches: Long = 0) {
        val p = prices[model] ?: prices.getValue("claude-opus-5-5")
        val cost = (input * p.input + output * p.output + cacheWrite * p.cacheWrite + cacheRead * p.cacheRead) / 1_000_000.0 +
            searches * perSearch
        prefs.raw().edit()
            .putFloat(monthKey(), (spentEur + cost).toFloat())
            .putInt(searchKey(), searchesThisMonth + searches.toInt())
            .apply()
    }
}
