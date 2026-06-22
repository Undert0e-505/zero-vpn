package com.zerovpn.app.oci

import java.net.InetAddress

data class OciRegion(
    val id: String,
    val label: String,
)

object OciRegions {
    val common: List<OciRegion> = listOf(
        OciRegion("us-ashburn-1", "US East (Ashburn)"),
        OciRegion("us-phoenix-1", "US West (Phoenix)"),
        OciRegion("ca-toronto-1", "Canada Southeast (Toronto)"),
        OciRegion("ca-montreal-1", "Canada Southeast (Montreal)"),
        OciRegion("uk-london-1", "UK South (London)"),
        OciRegion("uk-cardiff-1", "UK West (Cardiff)"),
        OciRegion("eu-frankfurt-1", "Germany Central (Frankfurt)"),
        OciRegion("eu-amsterdam-1", "Netherlands Northwest (Amsterdam)"),
        OciRegion("eu-zurich-1", "Switzerland North (Zurich)"),
        OciRegion("eu-madrid-1", "Spain Central (Madrid)"),
        OciRegion("eu-milan-1", "Italy Northwest (Milan)"),
        OciRegion("eu-stockholm-1", "Sweden Central (Stockholm)"),
        OciRegion("eu-paris-1", "France Central (Paris)"),
        OciRegion("eu-marseille-1", "France South (Marseille)"),
        OciRegion("ap-tokyo-1", "Japan East (Tokyo)"),
        OciRegion("ap-osaka-1", "Japan Central (Osaka)"),
        OciRegion("ap-seoul-1", "South Korea Central (Seoul)"),
        OciRegion("ap-chuncheon-1", "South Korea North (Chuncheon)"),
        OciRegion("ap-mumbai-1", "India West (Mumbai)"),
        OciRegion("ap-hyderabad-1", "India South (Hyderabad)"),
        OciRegion("ap-singapore-1", "Singapore (Singapore)"),
        OciRegion("ap-sydney-1", "Australia East (Sydney)"),
        OciRegion("ap-melbourne-1", "Australia Southeast (Melbourne)"),
        OciRegion("sa-saopaulo-1", "Brazil East (Sao Paulo)"),
        OciRegion("sa-vinhedo-1", "Brazil Southeast (Vinhedo)"),
        OciRegion("me-jeddah-1", "Saudi Arabia West (Jeddah)"),
        OciRegion("me-dubai-1", "UAE East (Dubai)"),
        OciRegion("il-jerusalem-1", "Israel Central (Jerusalem)"),
        OciRegion("mx-queretaro-1", "Mexico Central (Queretaro)"),
        OciRegion("af-johannesburg-1", "South Africa Central (Johannesburg)"),
    )

    private val regionKeys: Map<String, String> = mapOf(
        "IAD" to "us-ashburn-1",
        "PHX" to "us-phoenix-1",
        "YYZ" to "ca-toronto-1",
        "YUL" to "ca-montreal-1",
        "LHR" to "uk-london-1",
        "CWL" to "uk-cardiff-1",
        "FRA" to "eu-frankfurt-1",
        "AMS" to "eu-amsterdam-1",
        "CDG" to "eu-paris-1",
        "MRS" to "eu-marseille-1",
        "MAD" to "eu-madrid-1",
        "ZRH" to "eu-zurich-1",
        "LIN" to "eu-milan-1",
        "ARN" to "eu-stockholm-1",
        "BOM" to "ap-mumbai-1",
        "HYD" to "ap-hyderabad-1",
        "NRT" to "ap-tokyo-1",
        "KIX" to "ap-osaka-1",
        "ICN" to "ap-seoul-1",
        "YNY" to "ap-chuncheon-1",
        "SIN" to "ap-singapore-1",
        "SYD" to "ap-sydney-1",
        "MEL" to "ap-melbourne-1",
        "JED" to "me-jeddah-1",
        "DXB" to "me-dubai-1",
        "GRU" to "sa-saopaulo-1",
        "VCP" to "sa-vinhedo-1",
        "JRS" to "il-jerusalem-1",
        "QRO" to "mx-queretaro-1",
        "JNB" to "af-johannesburg-1",
    )

    fun labelFor(region: String): String =
        common.firstOrNull { it.id == region }?.let { "${it.label} - ${it.id}" } ?: region

    fun normalizeRegionHint(value: String?): String? {
        val hint = value?.trim()?.takeIf { it.isNotBlank() && it != "null" } ?: return null
        val lower = hint.lowercase()
        common.firstOrNull { it.id == lower }?.let { return it.id }
        regionKeys[hint.uppercase()]?.let { return it }
        common.firstOrNull { lower.contains(it.id) }?.let { return it.id }
        return hint
    }
}

object OciEndpoints {
    fun realm(region: String): String = OciRequestSigner.getRealmForRegion(region)

    fun identityHost(region: String): String = "identity.$region.oci.${realm(region)}"

    fun iaasHost(region: String): String = "iaas.$region.${realm(region)}"

    fun loginHost(region: String): String = "login.$region.${realm(region)}"

    fun identityEndpoint(region: String): String = "https://${identityHost(region)}"

    fun iaasEndpoint(region: String): String = "https://${iaasHost(region)}"

    fun resolveHost(host: String): Result<List<String>> = runCatching {
        InetAddress.getAllByName(host).mapNotNull { it.hostAddress }.ifEmpty {
            listOf("resolved")
        }
    }
}
