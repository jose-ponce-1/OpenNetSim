package com.opennetsim.ui

/** Enlaces del diálogo «Acerca de» y cuándo puede mostrarse el de donaciones. */
object SupportLinks {
    const val SOURCE_CODE_URL = "https://github.com/jose-ponce-1/OpenNetSim"

    /** Página de Ko-fi del autor (la misma que `.github/FUNDING.yml`). */
    const val KO_FI_URL = "https://ko-fi.com/joseponce"

    /** Instaladores de Google Play (el segundo aparece en versiones antiguas de la tienda). */
    private val GOOGLE_PLAY_INSTALLERS = setOf("com.android.vending", "com.google.android.feedback")

    /**
     * La política de pagos de Google Play no permite llevar al usuario, desde la app, a un medio de pago
     * distinto del de la tienda: instalada desde Play, la app no muestra el enlace de donaciones. Instalada
     * desde el APK de GitHub (o sin instalador conocido), sí.
     */
    fun donationLinkAllowed(installerPackage: String?): Boolean = installerPackage !in GOOGLE_PLAY_INSTALLERS
}
