package com.pocketsteward.app.projects

/** Matches SQLite's UTF-8 BINARY keyset order, including names outside the BMP. */
object ProjectDiscoveryOrder : Comparator<String> {
    override fun compare(left: String, right: String): Int {
        var a = 0; var b = 0
        while (a < left.length && b < right.length) {
            val x = Character.codePointAt(left, a); val y = Character.codePointAt(right, b)
            if (x != y) return x.compareTo(y)
            a += Character.charCount(x); b += Character.charCount(y)
        }
        return (left.length - a).compareTo(right.length - b)
    }
}
