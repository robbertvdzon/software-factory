package nl.vdzon.softwarefactory.web.views.pages

import nl.vdzon.softwarefactory.web.views.shared.HtmlLayout
import nl.vdzon.softwarefactory.web.views.shared.e

/** Loginpagina — bewust zonder de dashboard-shell (geen nav vóór het inloggen). */
internal class LoginView(private val layout: HtmlLayout) {

    fun render(error: Boolean = false, next: String = "/dashboard"): String =
        """
        <!doctype html>
        <html lang="nl">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          ${layout.favicon}
          <title>Login - Software Factory</title>
          ${layout.cssLink}
        </head>
        <body>
          <main class="login-wrap">
            <section class="login-card">
              <div class="login-mark">SF</div>
              <h1>Software Factory</h1>
              <p class="muted">Login op het dashboard</p>
              ${if (error) """<p class="alert bad">Ongeldige gebruikersnaam of wachtwoord.</p>""" else ""}
              <form method="post" action="/login" class="login-form">
                <input type="hidden" name="next" value="${next.e()}">
                <label>Gebruikersnaam<input name="username" value="admin" autocomplete="username"></label>
                <label>Wachtwoord<input name="password" type="password" autocomplete="current-password"></label>
                <button class="button primary" type="submit">Inloggen</button>
              </form>
            </section>
          </main>
        </body>
        </html>
        """.trimIndent()
}
