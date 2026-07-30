package com.example.spotifyvoice

sealed class SpotifyAction {
    object Play : SpotifyAction()
    object Pause : SpotifyAction()
    object Next : SpotifyAction()
    object Previous : SpotifyAction()
    
    object ShuffleOn : SpotifyAction()
    object ShuffleOff : SpotifyAction()
    object Repeat : SpotifyAction()
    
    data class PlaySpecific(val query: String, val type: String = "any") : SpotifyAction()
    data class AddToQueue(val query: String) : SpotifyAction()
    data class AddCurrentTrackToPlaylist(val playlistName: String) : SpotifyAction()
    
    data class SeekRelative(val offsetMs: Long) : SpotifyAction()
    
    object VolumeUp : SpotifyAction()
    object VolumeUpDouble : SpotifyAction()
    object VolumeDown : SpotifyAction()
    object VolumeDownDouble : SpotifyAction()
    
    object RestartTrack : SpotifyAction()
    
    object WhatIsPlaying : SpotifyAction()
    
    object Unknown : SpotifyAction()
}

class CommandParser {

    fun parse(input: String): SpotifyAction {
        var lower = input.lowercase().trim()

        // Füllwörter entfernen, um die Auswertung zuverlässiger zu machen
        val fillWords = listOf("bitte", "kannst du", "mach mal", "mach", "tu", "einmal", "kurz")
        for (word in fillWords) {
            lower = lower.replace(Regex("\\b$word\\b"), "").trim()
        }
        // Mehrfache Leerzeichen entfernen
        lower = lower.replace(Regex("\\s+"), " ")

        // 4. Lautstärke
        if (containsAny(lower, "zweimal lauter", "viel lauter", "deutlich lauter", "doppelt so laut", "sehr viel lauter")) return SpotifyAction.VolumeUpDouble
        if (containsAny(lower, "lauter", "ton lauter", "etwas lauter", "bisschen lauter")) return SpotifyAction.VolumeUp
        if (containsAny(lower, "zweimal leiser", "viel leiser", "deutlich leiser", "doppelt so leise", "sehr viel leiser", "viel zu laut")) return SpotifyAction.VolumeDownDouble
        if (containsAny(lower, "leiser", "ton leiser", "etwas leiser", "bisschen leiser")) return SpotifyAction.VolumeDown

        // 6. Spulen / Seek
        val seekRegex = Regex("(\\d+|eine|einer|ein|zwei|drei|vier|fünf|zehn|zwanzig|dreißig)\\s+(sekunde|sekunden|minute|minuten)\\s+(weiter|vor|vorspulen|nach vorne|zurück|rückwärts|zurückspulen)")
        val seekMatch = seekRegex.find(lower)
        if (seekMatch != null) {
            val amount = parseNumber(seekMatch.groupValues[1])
            val unit = seekMatch.groupValues[2]
            val direction = seekMatch.groupValues[3]
            
            var offsetMs = amount * if (unit.startsWith("minute")) 60000L else 1000L
            if (direction in listOf("zurück", "rückwärts", "zurückspulen")) {
                offsetMs = -offsetMs
            }
            return SpotifyAction.SeekRelative(offsetMs)
        }

        // 5. Statusabfragen
        if (containsAny(lower, "was läuft", "wie heißt", "wer singt", "welcher song", "von wem ist", "titelanzeige", "welches lied")) {
            return SpotifyAction.WhatIsPlaying
        }

        // 2. Wiedergabemodus
        if (containsAny(lower, "shuffle an", "zufall an", "smart shuffle", "durcheinander", "mischen an")) return SpotifyAction.ShuffleOn
        if (containsAny(lower, "shuffle aus", "zufall aus", "mischen aus", "normal abspielen", "der reihe nach")) return SpotifyAction.ShuffleOff
        if (containsAny(lower, "wiederholen", "schleife", "repeat", "nochmal von vorn", "dauerschleife")) return SpotifyAction.Repeat
        // Fallback: Wenn nur "shuffle" oder "zufall" gesagt wird, schalten wir es an (außer es hieß "aus")
        if (containsAny(lower, "zufallswiedergabe", "mischen", "shuffle")) return SpotifyAction.ShuffleOn

        // 3. Warteschlange (z.B. "packe song in die Warteschlange")
        // Wir fügen noch mehr Verben wie "stecke", "schiebe", "lege", "leg", "mach" hinzu, da "packe" oft akustisch falsch verstanden wird.
        val queueRegex = Regex("^(?:packe?|setze?|reihe|füge|tue?|stecke?|schiebe?|lege?|leg|mach)\\s+(.+?)\\s+(?:in\\s*(?:die)?\\s*warteschlange|auf\\s*(?:die)?\\s*liste|zur\\s*warteschlange(?:\\s*hinzu)?|ein|als\\s*nächstes)(?:\\s*rein)?$")
        val queueMatch = queueRegex.matchEntire(lower)
        if (queueMatch != null) {
            val query = queueMatch.groupValues[1].trim()
            if (query.isNotEmpty()) return SpotifyAction.AddToQueue(optimizeQuery(query))
        }
        
        // Alternativer Queue-Satzbau: "song in warteschlange" ohne erkennbares Verb am Anfang
        val queueRegex2 = Regex("^(.+?)\\s+(?:in\\s*(?:die)?\\s*warteschlange|auf\\s*(?:die)?\\s*liste|zur\\s*warteschlange(?:\\s*hinzu)?)$")
        val queueMatch2 = queueRegex2.matchEntire(lower)
        if (queueMatch2 != null) {
            var query = queueMatch2.groupValues[1].trim()
            val misheardPacke = listOf("backe ", "kacke ", "paco ", "parke ")
            for (misheard in misheardPacke) {
                if (query.startsWith(misheard)) {
                    query = query.removePrefix(misheard).trim()
                }
            }
            if (query.isNotEmpty()) return SpotifyAction.AddToQueue(optimizeQuery(query))
        }

        // 7. Aktuellen Song zu Playlist hinzufügen
        val addCurrentRegex = Regex("^(?:füge|packe?|tue?|schiebe?|lege?|speichere?)\\s+(?:diesen\\s+song|dieses\\s+lied|den\\s+track|das\\s+lied|den\\s+song|ihn)\\s+(?:in|zu|auf|zur)\\s+(?:meine|meiner|die)?\\s*playlist\\s+(.+?)(?:\\s+hinzu)?$")
        val addCurrentMatch = addCurrentRegex.matchEntire(lower)
        if (addCurrentMatch != null) {
            val playlistName = addCurrentMatch.groupValues[1].trim()
            if (playlistName.isNotEmpty()) return SpotifyAction.AddCurrentTrackToPlaylist(playlistName)
        }

        // 1. Grundlegende Wiedergabesteuerung
        if (containsAny(lower, "pause", "stopp", "halt", "anhalten", "pausieren", "unterbrechen", "musik aus")) return SpotifyAction.Pause
        if (containsAny(lower, "nächstes", "skippen", "überspringen", "next", "nächster", "lied weiter")) return SpotifyAction.Next
        if (containsAny(lower, "vorheriges", "zurück", "letztes", "vorheriger", "eins zurück")) return SpotifyAction.Previous
        if (containsAny(lower, "von vorne", "nochmal", "lied neu starten", "neu starten")) return SpotifyAction.RestartTrack

        // 3. Gezieltes Abspielen (Muss vor dem generischen "spiele" geprüft werden)
        // Erweitert für "meine Playlist"
        val playlistRegex = Regex("^(?:spiele|starte|hör|höre|ich möchte)\\s+(?:die\\s+)?(meine\\s+)?playlist\\s+(.+?)(?:\\s+ab|\\s+hören)?$")
        val playlistMatch = playlistRegex.matchEntire(lower)
        if (playlistMatch != null) {
             val isMeine = playlistMatch.groupValues[1].isNotBlank()
             val query = playlistMatch.groupValues[2].trim()
             if (query.isNotEmpty()) {
                 return SpotifyAction.PlaySpecific(query, if (isMeine) "my_playlist" else "playlist")
             }
        }

        val playRegex = Regex("^(?:spiele|starte|hör|höre|ich möchte)\\s+(?:den song\\s+|das lied\\s+|den künstler\\s+|die band\\s+|den track\\s+|das album\\s+)?(.+?)(?:\\s+ab|\\s+hören)?$")
        val playMatch = playRegex.matchEntire(lower)
        if (playMatch != null) {
             val query = playMatch.groupValues[1].trim()
             // Verhindern, dass leere Queries oder nur "spiele" zurückgegeben werden
             if (query.isNotEmpty() && query != "spiele" && query != "starte" && query != "ab") {
                 return SpotifyAction.PlaySpecific(optimizeQuery(query), "any")
             }
        }

        // Generisches Abspielen (Falls nichts spezifisches verlangt wurde)
        if (containsAny(lower, "spiele", "start", "fortsetzen", "musik an", "abspielen", "lass laufen", "weiter") || Regex("\\bplay\\b").containsMatchIn(lower)) return SpotifyAction.Play

        return SpotifyAction.Unknown
    }

    private fun optimizeQuery(query: String): String {
        // Wandelt "X von Y" um in "track:X artist:Y" für präzisere Spotify-Suchen.
        // Verwendet (.*) für X, um das *letzte* "von" zu finden, falls X selbst ein "von" enthält (z.B. "Haus von Nikolaus von Rammstein")
        val vonRegex = Regex("^(.*)\\s+(?:von|by)\\s+(.+)$", RegexOption.IGNORE_CASE)
        val match = vonRegex.matchEntire(query)
        if (match != null) {
            val trackOrAlbum = match.groupValues[1].trim()
            val artist = match.groupValues[2].trim()
            
            val genericWords = listOf("lieder", "lied", "songs", "song", "tracks", "track", "musik", "etwas", "was")
            if (genericWords.contains(trackOrAlbum.lowercase())) {
                return artist // Wenn nach "Lieder von Rammstein" gefragt wird, nur nach Rammstein suchen
            }
            return "track:$trackOrAlbum artist:$artist"
        }
        return query
    }

    private fun containsAny(input: String, vararg keywords: String): Boolean {
        return keywords.any { input.contains(it) }
    }

    private fun parseNumber(word: String): Long {
        return when (word) {
            "eine", "einer", "ein" -> 1L
            "zwei" -> 2L
            "drei" -> 3L
            "vier" -> 4L
            "fünf" -> 5L
            "zehn" -> 10L
            "zwanzig" -> 20L
            "dreißig" -> 30L
            else -> word.toLongOrNull() ?: 0L
        }
    }
}
