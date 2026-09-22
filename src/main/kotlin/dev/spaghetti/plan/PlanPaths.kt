package dev.spaghetti.plan

/** Picks file paths that never collide with each other or with a file that already exists on disk. */
internal object PlanPaths {

    /** `css/styles.css`; if that is taken, `css/about-styles.css` (named after the page); then `css/about-styles-2.css`... */
    fun uniquePath(dir: String, base: String, ext: String, page: String, used: MutableSet<String>, exists: (String) -> Boolean): String {
        val candidates = sequence {
            yield("$dir/$base.$ext")
            var n = 1
            while (true) {
                yield(if (n == 1) "$dir/$page-$base.$ext" else "$dir/$page-$base-$n.$ext")
                n++
            }
        }
        val path = candidates.first { it !in used && !exists(it) }
        used += path
        return path
    }
}
