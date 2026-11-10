package cy.pharmacies.app.data

import java.text.Normalizer

private val COMBINING_MARKS = Regex("""\p{Mn}+""")

/**
 * Lowercase with accents removed and final sigma folded, for comparing Greek text:
 * "Λευκωσία", "ΛΕΥΚΩΣΙΑ" and "λευκωσια" all become "λευκωσια". The ministry's files
 * aren't consistent about accents ("Επίθετο" in one, "Επιθετο" in another).
 */
fun foldGreek(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")
        .lowercase()
        .replace('ς', 'σ')
