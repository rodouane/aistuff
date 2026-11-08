package com.rod.hex

import android.app.Application

/** Point d'entrée : regroupe les briques partagées par l'écran et le service. */
class HexApp : Application() {
    lateinit var prefs: Prefs
    lateinit var memory: Memory
    lateinit var budget: Budget
    lateinit var net: Net
    lateinit var glyphs: Glyphs
    lateinit var weather: Weather

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        memory = Memory(this)
        budget = Budget(prefs)
        net = Net(this)
        glyphs = Glyphs(this)
        weather = Weather(this)
        glyphs.start()
    }

    companion object {
        fun of(ctx: android.content.Context) = ctx.applicationContext as HexApp
    }
}
